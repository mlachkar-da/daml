// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.pruning

import cats.data.{EitherT, NonEmptyList, ValidatedNec}
import cats.syntax.contravariantSemigroupal.*
import cats.syntax.functor.*
import cats.syntax.parallel.*
import cats.syntax.traverse.*
import cats.syntax.validated.*
import com.daml.error.*
import com.daml.nameof.NameOf.functionFullName
import com.digitalasset.canton.concurrent.{FutureSupervisor, Threading}
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.config.RequireTypes.{PositiveInt, PositiveNumeric}
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.data.{CantonTimestamp, CantonTimestampSecond}
import com.digitalasset.canton.error.CantonErrorGroups.ParticipantErrorGroup.AcsCommitmentErrorGroup
import com.digitalasset.canton.error.{Alarm, AlarmErrorCode, CantonError}
import com.digitalasset.canton.lifecycle.{FlagCloseable, FutureUnlessShutdown, Lifecycle}
import com.digitalasset.canton.logging.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.participant.event.{
  AcsChange,
  AcsChangeListener,
  ContractMetadataAndTransferCounter,
  RecordTime,
}
import com.digitalasset.canton.participant.metrics.PruningMetrics
import com.digitalasset.canton.participant.pruning.AcsCommitmentProcessor.Errors.MismatchError.AcsCommitmentAlarm
import com.digitalasset.canton.participant.store.*
import com.digitalasset.canton.protocol.ContractIdSyntax.*
import com.digitalasset.canton.protocol.messages.{
  AcsCommitment,
  CommitmentPeriod,
  ProtocolMessage,
  SignedProtocolMessage,
}
import com.digitalasset.canton.protocol.{LfContractId, LfHash, WithContractHash}
import com.digitalasset.canton.sequencing.client.SendAsyncClientError.RequestRefused
import com.digitalasset.canton.sequencing.client.{SendType, SequencerClient}
import com.digitalasset.canton.sequencing.protocol.{Batch, OpenEnvelope, Recipients, SendAsyncError}
import com.digitalasset.canton.store.SequencerCounterTrackerStore
import com.digitalasset.canton.topology.processing.EffectiveTime
import com.digitalasset.canton.topology.{DomainId, ParticipantId}
import com.digitalasset.canton.tracing.{TraceContext, Traced}
import com.digitalasset.canton.util.EitherUtil.RichEither
import com.digitalasset.canton.util.FutureInstances.*
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.util.*
import com.digitalasset.canton.util.retry.Policy
import com.digitalasset.canton.version.ProtocolVersion
import com.digitalasset.canton.{LfPartyId, TransferCounter, TransferCounterO}
import com.google.common.annotations.VisibleForTesting
import com.google.protobuf.ByteString

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.collection.immutable.{Map, SortedSet}
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.math.Ordering.Implicits.*

/** Computes, sends, receives and compares ACS commitments
  *
  *  In more detail:
  *
  *  <ol>
  *   <li>The class computes the participant's ACS commitments (for each of the participant's "counter-participants", i.e.,
  *     participants who host a stakeholder of some contract in participant's ACS). The commitments are computed at
  *     specified (sequencer) times that are configured by the domain and are uniform for all participants connected to
  *     the domain. We refer to them as "commitment ticks". The commitments must be computed "online", i.e., after the
  *     the state of the ACS at a commitment tick becomes known.
  *
  *   <li>After the commitments for a tick are computed, they should be distributed to the counter-participants; but
  *     this is best-effort.
  *   </li>
  *
  *   <li>The class processes the ACS commitments from counter-participants (method `processBatch`):
  *
  *     <ol>
  *      <li>it checks that the commitments are properly signed
  *      </li>
  *      <li>it checks that they match the locally computed ACS commitments
  *      </li>
  *     </ol>
  *   </li>
  *
  *   <li>The class must define crash recovery points, such that the class itself combined with startup procedures of
  *      the node jointly ensure that the participant doesn't neglect to send its ACS commitments or process the remote
  *      ones. We allow the participant to send the same commitments multiple times in case of a crash, and we do allow
  *      the participant to not send some commitments in some edge cases due to crashes.
  *   </li>
  *
  *   <li>Finally, the class supports pruning: it computes the safe timestamps for participant pruning, such
  *     that, after pruning, non-repudiation still holds for any contract in the ACS
  *   </li>
  *  </ol>
  *
  *  The first four pieces of class functionality must be appropriately synchronized:
  *
  *  <ol>
  *   <li>ACS commitments for a tick cannot be completely processed before the local commitment for that tick is computed.
  *      Note that the class cannot make many assumptions on the received commitments: the counter-participants can send
  *      them in any order, and they can either precede or lag behind the local commitment computations.
  *   </li>
  *
  *   <li>The recovery points must be chosen such that the participant computes its local commitments correctly, and
  *     never misses to compute a local commitment for every tick. Otherwise, the participant will start raising false
  *     alarms when remote commitments are received (either because it computes the wrong thing, or because it doesn't
  *     compute anything at all and thus doesn't expect to receive anything).
  *   </li>
  *  </ol>
  *
  *  Additionally, the startup procedure must ensure that:
  *
  *  <ol>
  *    <li> [[processBatch]] is called for every sequencer message that contains commitment messages and whose handling
  *    hasn't yet completed sucessfully
  *    <li> [[publish]] is called for every change to the ACS after
  *    [[com.digitalasset.canton.participant.store.IncrementalCommitmentStore.watermark]]. where the request counter
  *    is to be used as a tie-breaker.
  *    </li>
  *  </ol>
  *
  *  Finally, the class requires the reconciliation interval to be a multiple of 1 second.
  *
  * The ``commitmentPeriodObserver`` is called whenever a commitment is computed for a period, except if the participant crashes.
  * If [[publish]] is called multiple times for the same timestamp (once before a crash and once after the recovery),
  * the observer may also be called twice for the same period.
  */
@SuppressWarnings(Array("org.wartremover.warts.Var"))
class AcsCommitmentProcessor(
    domainId: DomainId,
    participantId: ParticipantId,
    val sequencerClient: SequencerClient,
    domainCrypto: SyncCryptoClient[SyncCryptoApi],
    sortedReconciliationIntervalsProvider: SortedReconciliationIntervalsProvider,
    store: AcsCommitmentStore,
    commitmentPeriodObserver: (ExecutionContext, TraceContext) => FutureUnlessShutdown[Unit],
    metrics: PruningMetrics,
    protocolVersion: ProtocolVersion,
    override protected val timeouts: ProcessingTimeout,
    futureSupervisor: FutureSupervisor,
    activeContractStore: ActiveContractStore,
    contractStore: ContractStore,
    enableAdditionalConsistencyChecks: Boolean,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends AcsChangeListener
    with FlagCloseable
    with NamedLogging {

  import AcsCommitmentProcessor.*

  // As the commitment computation is in the worst case expected to last the same order of magnitude as the
  // reconciliation interval, wait for at least that long
  override protected def closingTimeout: FiniteDuration = {
    // If we don't have any, nothing around commitment processing happened, so we take 0
    val latestReconciliationInterval =
      sortedReconciliationIntervalsProvider.getApproximateLatestReconciliationInterval
        .map(_.intervalLength.toScala)
        .getOrElse(Duration.Zero)

    super.closingTimeout.max(latestReconciliationInterval)
  }

  /** The parallelism to use when computing commitments */
  private val threadCount: PositiveNumeric[Int] = {
    val count = Threading.detectNumberOfThreads(noTracingLogger)
    noTracingLogger.info(s"Will use parallelism $count when computing ACS commitments")
    PositiveNumeric.tryCreate(count)
  }

  /* The sequencer timestamp for which we are ready to process remote commitments.
     Continuously updated as new local commitments are computed.
     All received remote commitments with the timestamp lower than this one will either have been processed or queued.
     Note that since access to this variable isn't synchronized, we don't guarantee that every remote commitment will
     be processed once this moves. However, such commitments will not be lost, as they will be put in the persistent
     buffer and get picked up by `processBuffered` eventually.
   */
  @volatile private var readyForRemote: Option[CantonTimestampSecond] = None

  /* End of the last period until which we have processed, sent and persisted all local and remote commitments.
     It's accessed only through chained futures, such that all accesses are synchronized  */
  @volatile private[this] var endOfLastProcessedPeriod: Option[CantonTimestampSecond] = None

  /* An in-memory, mutable running ACS snapshot, updated on every call to [[publish]]  */
  val runningCommitments: Future[RunningCommitments] = {
    store.runningCommitments.get()(TraceContext.empty).map { case (rt, snapshot) =>
      new RunningCommitments(
        rt,
        TrieMap(snapshot.toSeq.map { case (parties, h) =>
          parties -> LtHash16.tryCreate(h)
        }: _*),
      )
    }
  }

  private val timestampsWithPotentialTopologyChanges =
    new AtomicReference[List[Traced[EffectiveTime]]](List())

  /** Queue to serialize the access to the DB, to avoid serialization failures at SERIALIZABLE level */
  private val dbQueue: SimpleExecutionQueue =
    new SimpleExecutionQueue(
      "acs-commitment-processor-queue",
      futureSupervisor,
      timeouts,
      loggerFactory,
      logTaskTiming = true,
    )

  /** Queue to serialize the publication of ACS changes */
  private val publishQueue: SimpleExecutionQueue =
    new SimpleExecutionQueue(
      "acs-commitment-processor-publish-queue",
      futureSupervisor,
      timeouts,
      loggerFactory,
      logTaskTiming = true,
    )

  private def getReconciliationIntervals(validAt: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[SortedReconciliationIntervals] = performUnlessClosingF(functionFullName)(
    sortedReconciliationIntervalsProvider.reconciliationIntervals(validAt)
  )

  // Ensure we queue the initialization as the first task in the queue. We don't care about initialization having
  // completed by the time we return - only that no other task is queued before initialization.
  private[this] val initFuture: FutureUnlessShutdown[Unit] = {
    import TraceContext.Implicits.Empty.*
    val executed = dbQueue.executeUS(
      performUnlessClosingF("acs-commitment-processor-init") {
        for {
          lastComputed <- store.lastComputedAndSent
          _ = lastComputed.foreach { ts =>
            logger.info(s"Last computed and sent timestamp: $ts")
            endOfLastProcessedPeriod = Some(ts)
          }
          snapshot <- runningCommitments
          _ = logger.info(
            s"Initialized from stored snapshot at ${snapshot.watermark} (might be incomplete)"
          )

          _ <- lastComputed.fold(Future.unit)(processBuffered)

          _ = logger.info("Initialized the ACS commitment processor queue")
        } yield ()
      },
      "ACS commitment processor initialization",
    )
    FutureUtil.logOnFailureUnlessShutdown(
      executed,
      "Failed to initialize the ACS commitment processor.",
    )
  }

  @volatile private[this] var lastPublished: Option[RecordTime] = None

  def initializeTicksOnStartup(
      timestamps: List[EffectiveTime]
  )(implicit traceContext: TraceContext) = {
    // assuming timestamps to be ordered
    val cur = timestampsWithPotentialTopologyChanges.getAndSet(timestamps.map(Traced(_)))
    ErrorUtil.requireArgument(
      cur.isEmpty,
      s"Bad initialization attempt of timestamps with ticks, as we've already scheduled ${cur.length} ",
    )
  }

  def scheduleTopologyTick(effectiveTime: Traced[EffectiveTime]): Unit =
    timestampsWithPotentialTopologyChanges.updateAndGet { cur =>
      // only append if this timestamp is higher than the last one (relevant during init)
      if (cur.lastOption.forall(_.value < effectiveTime.value)) cur :+ effectiveTime
      else cur
    }.discard

  override def publish(toc: RecordTime, acsChange: AcsChange)(implicit
      traceContext: TraceContext
  ): Unit = {
    @tailrec
    def go(): Unit =
      timestampsWithPotentialTopologyChanges.get().headOption match {
        // no upcoming topology change queued
        case None => publishTick(toc, acsChange)
        // pre-insert topology change queued
        case Some(traced @ Traced(effectiveTime)) if effectiveTime.value <= toc.timestamp =>
          // remove the tick from our update
          timestampsWithPotentialTopologyChanges.updateAndGet(_.drop(1))
          // only update if this is a separate timestamp
          if (
            effectiveTime.value < toc.timestamp && lastPublished.exists(
              _.timestamp < effectiveTime.value
            )
          ) {
            publishTick(
              RecordTime(timestamp = effectiveTime, tieBreaker = 0),
              AcsChange.empty,
            )(traced.traceContext)
          }
          // now, iterate (there might have been several effective time updates)
          go()
        case Some(_) =>
          publishTick(toc, acsChange)
      }
    go()
  }

  /** Event processing consists of two steps: one (primarily) for computing local commitments, and one for handling remote ones.
    * This is the "local" processing, however, it does also process remote commitments in one case: when they arrive before the corresponding
    * local ones have been computed (in which case they are buffered).
    *
    * The caller(s) must jointly ensure that:
    * 1. [[publish]] is called with a strictly lexicographically increasing combination of timestamp/tiebreaker within
    *    a "crash-epoch". I.e., the timestamp/tiebreaker combination may only decrease across participant crashes/restarts.
    *    Note that the tie-breaker can change non-monotonically between two calls to publish. The tie-breaker is introduced
    *    to handle repair requests, as these may cause several changes to have the same timestamp.
    *    Actual ACS changes (e.g., due to transactions) use their request counter as the tie-breaker, while other
    *    updates (e.g., heartbeats) that only update the current time can set the tie-breaker to 0
    * 2. after publish is first called within a participant's "crash-epoch" with timestamp `ts` and tie-breaker `tb`, all subsequent changes
    *    to the ACS are also published (no gaps), and in the record order
    * 3. on startup, [[publish]] is called for all changes that are later than the watermark returned by
    *    [[com.digitalasset.canton.participant.store.IncrementalCommitmentStore.watermark]]. It may also be called for
    *    changes that are earlier than this timestamp (these calls will be ignored).
    *
    * Processing is implemented as a [[com.digitalasset.canton.util.SimpleExecutionQueue]] and driven by publish calls
    * made by the RecordOrderPublisher.
    *
    * ACS commitments at a tick become computable once an event with a timestamp larger than the tick appears
    */
  private def publishTick(toc: RecordTime, acsChange: AcsChange)(implicit
      traceContext: TraceContext
  ): Unit = {
    if (!lastPublished.forall(_ < toc))
      throw new IllegalStateException(
        s"Publish called with non-increasing record time, $toc (old was $lastPublished)"
      )
    lastPublished = Some(toc)
    lazy val msg =
      s"Publishing ACS change at $toc, ${acsChange.activations.size} activated, ${acsChange.deactivations.size} archived"
    logger.debug(msg)

    def processCompletedPeriod(
        snapshot: RunningCommitments
    )(completedPeriod: CommitmentPeriod, cryptoSnapshot: SyncCryptoApi): Future[Unit] = {
      val snapshotRes = snapshot.snapshot()
      logger.debug(show"Commitment snapshot for completed period $completedPeriod: $snapshotRes")
      for {
        // Detect possible inconsistencies of the running commitments and the ACS state
        // Runs only when enableAdditionalConsistencyChecks is true
        // Should not be enabled in production
        _ <- checkRunningCommitmentsAgainstACS(
          snapshotRes.active,
          activeContractStore,
          contractStore,
          enableAdditionalConsistencyChecks,
          completedPeriod.toInclusive.forgetRefinement,
        )
        msgs <- commitmentMessages(completedPeriod, snapshotRes.active, cryptoSnapshot)
        _ = logger.debug(
          show"Commitment messages for $completedPeriod: ${msgs.fmap(_.message.commitment)}"
        )
        _ <- storeAndSendCommitmentMessages(completedPeriod, msgs)
        _ <- store.markOutstanding(completedPeriod, msgs.keySet)
        _ <- persistRunningCommitments(snapshotRes)
        // The ordering here is important; we shouldn't move `readyForRemote` before we mark the periods as outstanding,
        // as otherwise we can get a race where an incoming commitment doesn't "clear" the outstanding period
        _ = indicateReadyForRemote(completedPeriod.toInclusive)
        _ <- processBuffered(completedPeriod.toInclusive)
        _ <- indicateLocallyProcessed(completedPeriod)
      } yield {
        // Run the observer asynchronously so that it does not block the further generation / processing of ACS commitments
        // Since this runs after we mark the period as locally processed, there are no guarantees that the observer
        // actually runs (e.g., if the participant crashes before be spawn this future).
        FutureUtil.doNotAwait(
          commitmentPeriodObserver(ec, traceContext).onShutdown {
            logger.info("Skipping commitment period observer due to shutdown")
          },
          "commitment period observer failed",
        )
      }
    }

    def performPublish(
        acsSnapshot: RunningCommitments,
        reconciliationIntervals: SortedReconciliationIntervals,
        cryptoSnapshotO: Option[SyncCryptoApi],
        periodEndO: Option[CantonTimestampSecond],
    ): FutureUnlessShutdown[Unit] = {
      // Check whether this change pushes us to a new commitment period; if so, the previous one is completed
      val completedPeriodAndCryptoO = for {
        periodEnd <- periodEndO
        completedPeriod <- reconciliationIntervals
          .commitmentPeriodPreceding(periodEnd, endOfLastProcessedPeriod)
        cryptoSnapshot <- cryptoSnapshotO
      } yield {
        (completedPeriod, cryptoSnapshot)
      }

      for {
        // Important invariant:
        // - let t be the tick of [[com.digitalasset.canton.participant.store.AcsCommitmentStore#lastComputedAndSent]];
        //   assume that t is not None
        // - then, we must have already computed and stored the local commitments at t
        // - let t' be the next tick after t; then the watermark of the running commitments must never move beyond t';
        //   otherwise, we lose the ability to compute the commitments at t'
        // Hence, the order here is critical for correctness; if the change moves us beyond t', first compute
        // the commitments at t', and only then update the snapshot
        _ <- completedPeriodAndCryptoO match {
          case Some((commitmentPeriod, cryptoSnapshot)) =>
            performUnlessClosingF(functionFullName)(
              processCompletedPeriod(acsSnapshot)(commitmentPeriod, cryptoSnapshot)
            )
          case None =>
            FutureUnlessShutdown.pure(
              logger.debug("This change does not lead to a new commitment period.")
            )
        }

        _ <- FutureUnlessShutdown.outcomeF(updateSnapshot(toc, acsChange))
      } yield ()
    }

    // On the `publishQueue`, obtain the running commitment, the reconciliation parameters, and topology snapshot,
    // and check whether this is a replay of something we've already seen. If not, then do publish the change,
    // which runs on the `dbQueue`.
    val fut = publishQueue
      .executeUS(
        for {
          acsSnapshot <- performUnlessClosingF(functionFullName)(runningCommitments)
          reconciliationIntervals <- getReconciliationIntervals(toc.timestamp)
          periodEndO = reconciliationIntervals
            .tickBefore(toc.timestamp)
          cryptoSnapshotO <- periodEndO.traverse(periodEnd =>
            domainCrypto.awaitSnapshotUS(periodEnd.forgetRefinement)
          )
        } yield {
          if (acsSnapshot.watermark >= toc) {
            logger.debug(s"ACS change at $toc is a replay, treating it as a no-op")
            // This is a replay of an already processed ACS change, ignore
            FutureUnlessShutdown.unit
          } else {
            // Serialize the access to the DB only after having obtained the reconciliation intervals and topology snapshot.
            // During crash recovery, the topology client may only be able to serve the intervals and snapshots
            // for re-published ACS changes after some messages have been processed,
            // which may include ACS commitments that go through the same queue.
            dbQueue.executeUS(
              Policy.noisyInfiniteRetryUS(
                performPublish(acsSnapshot, reconciliationIntervals, cryptoSnapshotO, periodEndO),
                this,
                timeouts.storageMaxRetryInterval.asFiniteApproximation,
                s"publish ACS change at $toc",
                s"Disconnect and reconnect to the domain $domainId if this error persists.",
              ),
              s"publish ACS change at $toc",
            )
          }
        },
        s"publish ACS change at $toc",
      )
      .flatten

    FutureUtil.doNotAwait(
      fut.onShutdown(
        logger.info("Giving up on producing ACS commitment due to shutdown")
      ),
      failureMessage = s"Producing ACS commitments failed.",
      // If this happens, then the failure is fatal or there is some bug in the queuing or retrying.
      // Unfortunately, we can't do anything anymore to reliably prevent corruption of the running snapshot in the DB,
      // as the data may already be corrupted by now.
    )
  }

  def processBatch(
      timestamp: CantonTimestamp,
      batch: Traced[List[OpenEnvelope[SignedProtocolMessage[AcsCommitment]]]],
  ): FutureUnlessShutdown[Unit] =
    batch.withTraceContext(implicit traceContext => processBatchInternal(timestamp, _))

  /** Process incoming commitments.
    *
    * The caller(s) must jointly ensure that all incoming commitments are passed to this method, in their order
    * of arrival. Upon startup, the method must be called on all incoming commitments whose processing hasn't
    * finished yet, including those whose processing has been aborted due to shutdown.
    */
  def processBatchInternal(
      timestamp: CantonTimestamp,
      batch: List[OpenEnvelope[SignedProtocolMessage[AcsCommitment]]],
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[Unit] = {

    if (batch.lengthCompare(1) != 0) {
      Errors.InternalError.MultipleCommitmentsInBatch(domainId, timestamp, batch.length).discard
    }

    val future = for {
      _ <- initFuture
      _ <- batch.parTraverse_ { envelope =>
        getReconciliationIntervals(
          envelope.protocolMessage.message.period.toInclusive.forgetRefinement
        )
          // TODO(#10790) Investigate whether we can validate and process the commitments asynchronously.
          .flatMap { reconciliationIntervals =>
            validateEnvelope(timestamp, envelope, reconciliationIntervals) match {
              case Right(()) =>
                checkSignedMessage(timestamp, envelope.protocolMessage)

              case Left(errors) =>
                errors.toList.foreach(logger.error(_))
                FutureUnlessShutdown.unit
            }
          }
      }
    } yield ()

    FutureUtil.logOnFailureUnlessShutdown(
      future,
      failureMessage = s"Failed to process incoming commitment.",
      onFailure = _ => {
        // Close ourselves so that we don't process any more messages
        close()
      },
    )
  }

  private def validateEnvelope(
      timestamp: CantonTimestamp,
      envelope: OpenEnvelope[SignedProtocolMessage[AcsCommitment]],
      reconciliationIntervals: SortedReconciliationIntervals,
  ): Either[NonEmptyList[String], Unit] = {
    val payload = envelope.protocolMessage.message

    def validate(valid: Boolean, error: => String): ValidatedNec[String, Unit] =
      if (valid) ().validNec else error.invalidNec

    val validRecipients = validate(
      envelope.recipients == Recipients.cc(participantId),
      s"At $timestamp, (purportedly) ${payload.sender} sent an ACS commitment to me, but addressed the message to ${envelope.recipients}",
    )

    val validCounterParticipant = validate(
      payload.counterParticipant == participantId,
      s"At $timestamp, (purportedly) ${payload.sender} sent an ACS commitment to me, but the commitment lists ${payload.counterParticipant} as the counterparticipant",
    )

    val commitmentPeriodEndsInPast = validate(
      payload.period.toInclusive <= timestamp,
      s"Received an ACS commitment with a future beforeAndAt timestamp. (Purported) sender: ${payload.sender}. Timestamp: ${payload.period.toInclusive}, receive timestamp: $timestamp",
    )

    val commitmentPeriodEndsAtTick =
      reconciliationIntervals.isAtTick(payload.period.toInclusive) match {
        case Some(true) => ().validNec
        case Some(false) =>
          s"finish time of received commitment period is not on a tick: ${payload.period}".invalidNec
        case None =>
          s"Unable to determine whether finish time of received commitment period is on a tick: ${payload.period}".invalidNec
      }

    (
      validRecipients,
      validCounterParticipant,
      commitmentPeriodEndsInPast,
      commitmentPeriodEndsAtTick,
    ).mapN((_, _, _, _) => ()).toEither.left.map(_.toNonEmptyList)
  }

  private def persistRunningCommitments(
      res: CommitmentSnapshot
  )(implicit traceContext: TraceContext): Future[Unit] = {
    store.runningCommitments
      .update(res.recordTime, res.delta, res.deleted)
      .map(_ => logger.debug(s"Persisted ACS commitments at ${res.recordTime}"))
  }

  private def updateSnapshot(rt: RecordTime, acsChange: AcsChange)(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    logger.debug(
      s"Applying ACS change at $rt: ${acsChange.activations.size} activated, ${acsChange.deactivations.size} archived"
    )
    for {
      snapshot <- runningCommitments
      _ = snapshot.update(rt, acsChange)
    } yield ()
  }

  private def indicateLocallyProcessed(
      period: CommitmentPeriod
  )(implicit traceContext: TraceContext): Future[Unit] = {
    endOfLastProcessedPeriod = Some(period.toInclusive)
    for {
      // delete the processed buffered commitments (safe to do at any point after `processBuffered` completes)
      _ <- store.queue.deleteThrough(period.toInclusive.forgetRefinement)
      // mark that we're done with processing this period; safe to do at any point after the commitment has been sent
      // and the outstanding commitments stored
      _ <- store.markComputedAndSent(period)
    } yield {
      logger.info(
        s"Deleted buffered commitments and set last computed and sent timestamp set to ${period.toInclusive}"
      )
    }
  }

  private def checkSignedMessage(
      timestamp: CantonTimestamp,
      message: SignedProtocolMessage[AcsCommitment],
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[Unit] = {
    logger.debug(
      s"Checking commitment (purportedly by) ${message.message.sender} for period ${message.message.period}"
    )
    for {
      validSig <- FutureUnlessShutdown.outcomeF(checkCommitmentSignature(message))

      commitment = message.message

      // If signature passes, store such that we can prove Byzantine behavior if necessary
      _ <-
        if (validSig) for {
          _ <- FutureUnlessShutdown.outcomeF(store.storeReceived(message))
          _ <- checkCommitment(commitment)
        } yield ()
        else FutureUnlessShutdown.unit
    } yield {
      if (!validSig) {
        AcsCommitmentAlarm
          .Warn(
            s"""Received wrong signature for ACS commitment at timestamp $timestamp; purported sender: ${commitment.sender}; commitment: $commitment"""
          )
          .report()
      }
    }
  }

  private def checkCommitmentSignature(
      message: SignedProtocolMessage[AcsCommitment]
  )(implicit traceContext: TraceContext): Future[Boolean] =
    for {
      cryptoSnapshot <- domainCrypto.awaitSnapshot(
        message.message.period.toInclusive.forgetRefinement
      )
      result <- message.verifySignature(cryptoSnapshot, message.typedMessage.content.sender).value
    } yield result
      .tapLeft(err => logger.error(s"Commitment signature verification failed with $err"))
      .isRight

  private def checkCommitment(
      commitment: AcsCommitment
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[Unit] =
    dbQueue
      .execute(
        // Make sure that the ready-for-remote check is atomic with buffering the commitment
        {
          val readyToCheck = readyForRemote.exists(_ >= commitment.period.toInclusive)

          if (readyToCheck) {
            // Do not sequentialize the checking
            Future.successful(checkMatchAndMarkSafe(List(commitment)))
          } else {
            logger.debug(s"Buffering $commitment for later processing")
            store.queue.enqueue(commitment).map((_: Unit) => Future.successful(()))
          }
        },
        s"check commitment readiness at ${commitment.period} by ${commitment.sender}",
      )
      .flatMap(FutureUnlessShutdown.outcomeF)

  private def indicateReadyForRemote(timestamp: CantonTimestampSecond): Unit = {
    readyForRemote.foreach(oldTs =>
      assert(
        oldTs <= timestamp,
        s"out of order timestamps in the commitment processor: $oldTs and $timestamp",
      )
    )
    readyForRemote = Some(timestamp)
  }

  private def processBuffered(
      timestamp: CantonTimestampSecond
  )(implicit traceContext: TraceContext): Future[Unit] = {
    logger.debug(s"Processing buffered commitments until $timestamp")
    for {
      toProcess <- store.queue.peekThrough(timestamp.forgetRefinement)
      _ <- checkMatchAndMarkSafe(toProcess)
    } yield {
      logger.debug(
        s"Checked buffered remote commitments up to $timestamp and ready to check further ones without buffering"
      )
    }
  }

  /* Logs all necessary messages and returns whether the remote commitment matches the local ones */
  private def matches(
      remote: AcsCommitment,
      local: Iterable[(CommitmentPeriod, AcsCommitment.CommitmentType)],
      lastPruningTime: Option[CantonTimestamp],
  )(implicit traceContext: TraceContext): Boolean = {
    if (local.isEmpty) {
      if (lastPruningTime.forall(_ < remote.period.toInclusive.forgetRefinement)) {
        Errors.MismatchError.NoSharedContracts.Mismatch(domainId, remote).report()
      } else
        logger.info(s"Ignoring incoming commitment for a pruned period: $remote")
      false
    } else {
      local.filter(_._2 != remote.commitment) match {
        case Nil => {
          lazy val logMsg: String =
            s"Commitment correct for sender ${remote.sender} and period ${remote.period}"
          logger.debug(logMsg)
          true
        }
        case mismatches => {
          Errors.MismatchError.CommitmentsMismatch
            .Mismatch(domainId, remote, mismatches.toSeq)
            .report()
          false
        }
      }
    }
  }

  private def checkMatchAndMarkSafe(
      remote: List[AcsCommitment]
  )(implicit traceContext: TraceContext): Future[Unit] = {
    logger.debug(s"Processing ${remote.size} remote commitments")
    remote.parTraverse_ { cmt =>
      for {
        commitments <- store.getComputed(cmt.period, cmt.sender)
        lastPruningTime <- store.pruningStatus
        _ <-
          if (matches(cmt, commitments, lastPruningTime.map(_.timestamp))) {
            store.markSafe(cmt.sender, cmt.period, sortedReconciliationIntervalsProvider)
          } else Future.unit
      } yield ()
    }
  }

  private def signCommitment(
      crypto: SyncCryptoApi,
      counterParticipant: ParticipantId,
      cmt: AcsCommitment.CommitmentType,
      period: CommitmentPeriod,
  )(implicit traceContext: TraceContext): Future[SignedProtocolMessage[AcsCommitment]] = {
    val payload = AcsCommitment.create(
      domainId,
      participantId,
      counterParticipant,
      period,
      cmt,
      protocolVersion,
    )
    SignedProtocolMessage.trySignAndCreate(payload, crypto, protocolVersion)
  }

  /* Compute commitment messages to be sent for the ACS at the given timestamp. The snapshot is assumed to be ordered
   * by contract IDs (ascending or descending both work, but must be the same at all participants) */
  private def commitmentMessages(
      period: CommitmentPeriod,
      commitmentSnapshot: Map[SortedSet[LfPartyId], AcsCommitment.CommitmentType],
      cryptoSnapshot: SyncCryptoApi,
  )(implicit
      traceContext: TraceContext
  ): Future[Map[ParticipantId, SignedProtocolMessage[AcsCommitment]]] = {
    logger.debug(
      s"Computing commitments for $period, number of stakeholder sets: ${commitmentSnapshot.keySet.size}"
    )
    for {
      cmts <- commitments(
        participantId,
        commitmentSnapshot,
        domainCrypto,
        period.toInclusive,
        Some(metrics),
        threadCount,
      )

      msgs <- cmts
        .collect {
          case (counterParticipant, cmt) if LtHash16.isNonEmptyCommitment(cmt) =>
            signCommitment(cryptoSnapshot, counterParticipant, cmt, period).map(msg =>
              (counterParticipant, msg)
            )
        }
        .toList
        .sequence
        .map(_.toMap)
    } yield msgs
  }

  /** Send the computed commitment messages and store their computed commitments */
  private def storeAndSendCommitmentMessages(
      period: CommitmentPeriod,
      msgs: Map[ParticipantId, SignedProtocolMessage[AcsCommitment]],
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      _ <- msgs.toList.parTraverse_ { case (pid, msg) =>
        store.storeComputed(msg.message.period, pid, msg.message.commitment)
      }
      _ = logger.debug(s"Computed and stored ${msgs.size} commitment messages for period $period")
      batchForm = msgs.toList.map { case (pid, msg) => (msg, Recipients.cc(pid)) }
      batch = Batch.of[ProtocolMessage](protocolVersion, batchForm: _*)
      // send async: we'll get gaps in the commitments if the send fails, but that shouldn't
      // hurt if every sequencer connection is reasonably reliably. The next commitments will
      // go through again and then the counterparticipant has a gap, but we don't really care
      // about them
      _ = if (batch.envelopes.nonEmpty)
        performUnlessClosingEitherT(functionFullName, ()) {
          def message = s"Failed to send commitment message batch for period $period"
          EitherT(
            FutureUtil.logOnFailure(
              sequencerClient
                .sendAsync(batch, SendType.Other, None)
                .leftMap {
                  case RequestRefused(SendAsyncError.ShuttingDown(msg)) =>
                    logger.info(
                      s"${message} as the sequencer is shutting down. Once the sequencer is back, we'll recover."
                    )
                  case other =>
                    logger.warn(s"${message}: ${other}")
                }
                .value,
              message,
            )
          )
        }
    } yield logger.debug(
      s"Request to sequence local commitment messages for period $period sent to sequencer"
    )

  override protected def onClosed(): Unit = {
    Lifecycle.close(dbQueue, publishQueue)(logger)
  }

  @VisibleForTesting
  private[pruning] def flush(): Future[Unit] =
    // flatMap instead of zip because the `publishQueue` pushes tasks into the `queue`,
    // so we must call `queue.flush()` only after everything in the `publishQueue` has been flushed.
    publishQueue.flush().flatMap(_ => dbQueue.flush())
}

object AcsCommitmentProcessor extends HasLoggerName {

  type ProcessorType =
    (
        CantonTimestamp,
        Traced[List[OpenEnvelope[SignedProtocolMessage[AcsCommitment]]]],
    ) => FutureUnlessShutdown[Unit]

  /** A snapshot of ACS commitments per set of stakeholders
    *
    * @param recordTime           The timestamp and tie-breaker of the snapshot
    * @param active       Maps stakeholders to the commitment to their shared ACS, if the shared ACS is not empty
    * @param delta        A sub-map of active with those stakeholders whose commitments have changed since the last snapshot
    * @param deleted      Stakeholder sets whose ACS has gone to empty since the last snapshot (no longer active)
    */
  final case class CommitmentSnapshot(
      recordTime: RecordTime,
      active: Map[SortedSet[LfPartyId], AcsCommitment.CommitmentType],
      delta: Map[SortedSet[LfPartyId], AcsCommitment.CommitmentType],
      deleted: Set[SortedSet[LfPartyId]],
  ) extends PrettyPrinting {
    override def pretty: Pretty[CommitmentSnapshot] = prettyOfClass(
      param("record time", _.recordTime),
      param("active", _.active),
      param("delta (parties)", _.delta.keySet),
      param("deleted", _.deleted),
    )
  }

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  class RunningCommitments(
      initRt: RecordTime,
      commitments: TrieMap[SortedSet[LfPartyId], LtHash16],
  ) extends HasLoggerName {

    private val lock = new Object
    @volatile private var rt: RecordTime = initRt
    private val deltaB = Map.newBuilder[SortedSet[LfPartyId], LtHash16]

    /** The latest (immutable) snapshot. Taking the snapshot also garbage collects empty commitments.
      */
    def snapshot(): CommitmentSnapshot = {

      /* Delete all hashes that have gone empty since the last snapshot and return the corresponding stakeholder sets */
      def garbageCollect(
          candidates: Map[SortedSet[LfPartyId], LtHash16]
      ): Set[SortedSet[LfPartyId]] = {
        val deletedB = Set.newBuilder[SortedSet[LfPartyId]]
        candidates.foreach { case (stkhs, h) =>
          if (h.isEmpty) {
            deletedB += stkhs
            commitments -= stkhs
          }
        }
        deletedB.result()
      }

      blocking {
        lock.synchronized {
          val delta = deltaB.result()
          deltaB.clear()
          val deleted = garbageCollect(delta)
          val activeDelta = (delta -- deleted).fmap(_.getByteString())
          // Note that it's crucial to eagerly (via fmap, as opposed to, say mapValues) snapshot the LtHash16 values,
          // since they're mutable
          CommitmentSnapshot(
            rt,
            commitments.readOnlySnapshot().toMap.fmap(_.getByteString()),
            activeDelta,
            deleted,
          )
        }
      }
    }

    def update(rt: RecordTime, change: AcsChange)(implicit
        loggingContext: NamedLoggingContext
    ): Unit = {
      /*
      The concatenate function is guaranteed to be safe when contract IDs always have the same length.
      Otherwise, a longer contract ID without a transfer counter might collide with a
      shorter contract ID with a transfer counter.
      In the current implementation collisions cannot happen, because either all contracts in a commitment
      have a transfer counter or none, depending on the protocol version.
       */
      def concatenate(
          contractHash: LfHash,
          contractId: LfContractId,
          transferCounter: TransferCounterO,
      ): Array[Byte] =
        (
          contractHash.bytes.toByteString // hash always 32 bytes long per lf.crypto.Hash.underlyingLength
            concat contractId.encodeDeterministically
            concat transferCounter.fold(ByteString.EMPTY)(TransferCounter.encodeDeterministically)
        ).toByteArray

      import com.digitalasset.canton.lfPartyOrdering
      blocking {
        lock.synchronized {
          this.rt = rt
          change.activations.foreach {
            case (cid, WithContractHash(metadataAndTransferCounter, hash)) =>
              val sortedStakeholders =
                SortedSet(metadataAndTransferCounter.contractMetadata.stakeholders.toSeq: _*)
              val h = commitments.getOrElseUpdate(sortedStakeholders, LtHash16())
              h.add(concatenate(hash, cid, metadataAndTransferCounter.transferCounter))
              loggingContext.trace(
                s"Adding to commitment activation cid $cid transferCounter ${metadataAndTransferCounter.transferCounter}"
              )
              deltaB += sortedStakeholders -> h
          }
          change.deactivations.foreach {
            case (cid, WithContractHash(stakeholdersAndTransferCounter, hash)) =>
              val sortedStakeholders =
                SortedSet(stakeholdersAndTransferCounter.stakeholders.toSeq: _*)
              val h = commitments.getOrElseUpdate(sortedStakeholders, LtHash16())
              h.remove(concatenate(hash, cid, stakeholdersAndTransferCounter.transferCounter))
              loggingContext.trace(
                s"Removing from commitment activation cid $cid transferCounter ${stakeholdersAndTransferCounter.transferCounter}"
              )
              deltaB += sortedStakeholders -> h
          }
        }
      }
    }

    def watermark: RecordTime = rt

  }

  /** Compute the ACS commitments at the given timestamp.
    *
    * Extracted as a pure function to be able to test.
    */
  @VisibleForTesting
  private[pruning] def commitments(
      participantId: ParticipantId,
      runningCommitments: Map[SortedSet[LfPartyId], AcsCommitment.CommitmentType],
      domainCrypto: SyncCryptoClient[SyncCryptoApi],
      timestamp: CantonTimestampSecond,
      pruningMetrics: Option[PruningMetrics],
      parallelism: PositiveNumeric[Int],
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[Map[ParticipantId, AcsCommitment.CommitmentType]] = {
    val commitmentTimer = pruningMetrics.map(_.commitments.compute.startAsync())

    for {
      ipsSnapshot <- domainCrypto.ipsSnapshot(timestamp.forgetRefinement)
      // Important: use the keys of the timestamp
      isActiveParticipant <- ipsSnapshot.isParticipantActive(participantId)

      byParticipant <-
        if (isActiveParticipant) {
          val allParties = runningCommitments.keySet.flatten
          ipsSnapshot.activeParticipantsOfParties(allParties.toSeq).flatMap { participantsOf =>
            IterableUtil
              .mapReducePar[(SortedSet[LfPartyId], AcsCommitment.CommitmentType), Map[
                ParticipantId,
                Set[AcsCommitment.CommitmentType],
              ]](parallelism, runningCommitments.toSeq) { case (parties, commitment) =>
                val participants = parties.flatMap(participantsOf.getOrElse(_, Set.empty))
                // Check that we're hosting at least one stakeholder; it can happen that the stakeholder used to be
                // hosted on this participant, but is now disabled
                val pSet =
                  if (participants.contains(participantId)) participants - participantId
                  else Set.empty
                val commitmentS = Set(commitment)
                pSet.map(_ -> commitmentS).toMap
              }(MapsUtil.mergeWith(_, _)(_.union(_)))
              .map(_.getOrElse(Map.empty[ParticipantId, Set[AcsCommitment.CommitmentType]]))
          }
        } else Future.successful(Map.empty[ParticipantId, Set[AcsCommitment.CommitmentType]])
    } yield {
      val res = byParticipant.fmap { hashes =>
        val sumHash = LtHash16()
        hashes.foreach(h => sumHash.add(h.toByteArray))
        sumHash.getByteString()
      }
      commitmentTimer.foreach(_.stop())
      res
    }
  }

  /* Extracted to be able to test more easily */
  @VisibleForTesting
  private[pruning] def safeToPrune_(
      cleanReplayF: Future[CantonTimestamp],
      commitmentsPruningBound: CommitmentsPruningBound,
      earliestInFlightSubmissionF: Future[Option[CantonTimestamp]],
      sortedReconciliationIntervalsProvider: SortedReconciliationIntervalsProvider,
      domainId: DomainId,
  )(implicit
      ec: ExecutionContext,
      loggingContext: NamedLoggingContext,
  ): Future[Option[CantonTimestampSecond]] = {
    for {
      // This logic progressively lowers the timestamp based on the following constraints:
      // 1. Pruning must not delete data needed for recovery (after the clean replay timestamp)
      cleanReplayTs <- cleanReplayF

      // 2. Pruning must not delete events from the event log for which there are still in-flight submissions.
      // We check here the `SingleDimensionEventLog` for the domain; the participant event log must be taken care of separately.
      //
      // Processing of sequenced events may concurrently move the earliest in-flight submission back in time
      // (from timeout to sequencing timestamp), but this can only happen if the corresponding request is not yet clean,
      // i.e., the sequencing timestamp is after `cleanReplayTs`. So this concurrent modification does not affect
      // the calculation below.
      inFlightSubmissionTs <- earliestInFlightSubmissionF

      getTickBeforeOrAt = (ts: CantonTimestamp) =>
        sortedReconciliationIntervalsProvider
          .reconciliationIntervals(ts)(loggingContext.traceContext)
          .map(_.tickBeforeOrAt(ts))
          .flatMap {
            case Some(tick) =>
              loggingContext.info(s"Tick before or at $ts yields $tick on domain $domainId")
              Future.successful(tick)
            case None =>
              Future.failed(
                new RuntimeException(
                  s"Unable to compute tick before or at `$ts` for domain $domainId"
                )
              )
          }

      // Latest potential pruning point is the ACS commitment tick before or at the "clean replay" timestamp
      // and strictly before the earliest timestamp associated with an in-flight submission.
      latestTickBeforeOrAt <- getTickBeforeOrAt(
        cleanReplayTs.min(
          inFlightSubmissionTs.fold(CantonTimestamp.MaxValue)(_.immediatePredecessor)
        )
      )

      // Only acs commitment ticks whose ACS commitment fully matches all counter participant ACS commitments are safe,
      // so look for the most recent such tick before latestTickBeforeOrAt if any.
      tsSafeToPruneUpTo <- commitmentsPruningBound match {
        case CommitmentsPruningBound.Outstanding(noOutstandingCommitmentsF) =>
          noOutstandingCommitmentsF(latestTickBeforeOrAt.forgetRefinement).flatMap(
            _.traverse(getTickBeforeOrAt)
          )
        case CommitmentsPruningBound.LastComputedAndSent(lastComputedAndSentF) =>
          for {
            lastComputedAndSentO <- lastComputedAndSentF
            tickBeforeLastComputedAndSentO <- lastComputedAndSentO.traverse(getTickBeforeOrAt)
          } yield tickBeforeLastComputedAndSentO.map(_.min(latestTickBeforeOrAt))
      }

      _ = loggingContext.info {
        val timestamps = Map(
          "cleanReplayTs" -> cleanReplayTs.toString,
          "inFlightSubmissionTs" -> inFlightSubmissionTs.toString,
          "latestTickBeforeOrAt" -> latestTickBeforeOrAt.toString,
          "tsSafeToPruneUpTo" -> tsSafeToPruneUpTo.toString,
        )

        s"Getting safe to prune commitment tick with data $timestamps on domain $domainId"
      }

      // Sanity check that safe pruning timestamp has not "increased" (which would be a coding bug).
      _ = tsSafeToPruneUpTo.foreach(ts =>
        ErrorUtil.requireState(
          ts <= latestTickBeforeOrAt,
          s"limit $tsSafeToPruneUpTo after $latestTickBeforeOrAt on domain $domainId",
        )
      )
    } yield tsSafeToPruneUpTo
  }

  /*
    Describe how ACS commitments are taken into account for the safeToPrune computation:
   */
  sealed trait CommitmentsPruningBound extends Product with Serializable
  object CommitmentsPruningBound {
    // Not before any outstanding commitment
    final case class Outstanding(
        noOutstandingCommitmentsF: CantonTimestamp => Future[Option[CantonTimestamp]]
    ) extends CommitmentsPruningBound

    // Not before any computed and sent commitment
    final case class LastComputedAndSent(
        lastComputedAndSentF: Future[Option[CantonTimestamp]]
    ) extends CommitmentsPruningBound
  }

  /** The latest commitment tick before or at the given time at which it is safe to prune. */
  def safeToPrune(
      requestJournalStore: RequestJournalStore,
      sequencerCounterTrackerStore: SequencerCounterTrackerStore,
      sortedReconciliationIntervalsProvider: SortedReconciliationIntervalsProvider,
      acsCommitmentStore: AcsCommitmentStore,
      inFlightSubmissionStore: InFlightSubmissionStore,
      domainId: DomainId,
      checkForOutstandingCommitments: Boolean,
  )(implicit
      ec: ExecutionContext,
      loggingContext: NamedLoggingContext,
  ): Future[Option[CantonTimestampSecond]] = {
    implicit val traceContext: TraceContext = loggingContext.traceContext
    val cleanReplayF = SyncDomainEphemeralStateFactory
      .crashRecoveryPruningBoundInclusive(requestJournalStore, sequencerCounterTrackerStore)

    val commitmentsPruningBound =
      if (checkForOutstandingCommitments)
        CommitmentsPruningBound.Outstanding(acsCommitmentStore.noOutstandingCommitments(_))
      else
        CommitmentsPruningBound.LastComputedAndSent(
          acsCommitmentStore.lastComputedAndSent.map(_.map(_.forgetRefinement))
        )

    val earliestInFlightF = inFlightSubmissionStore.lookupEarliest(domainId)
    safeToPrune_(
      cleanReplayF,
      commitmentsPruningBound = commitmentsPruningBound,
      earliestInFlightF,
      sortedReconciliationIntervalsProvider,
      domainId,
    )
  }

  private def checkRunningCommitmentsAgainstACS(
      runningCommitments: Map[SortedSet[LfPartyId], AcsCommitment.CommitmentType],
      activeContractStore: ActiveContractStore,
      contractStore: ContractStore,
      enableAdditionalConsistencyChecks: Boolean,
      toInclusive: CantonTimestamp, // end of interval used to snapshot the ACS
  )(implicit
      ec: ExecutionContext,
      namedLoggingContext: NamedLoggingContext,
  ): Future[Unit] = {

    def withMetadataSeq(cids: Seq[LfContractId]): Future[Seq[StoredContract]] =
      contractStore
        .lookupManyUncached(cids)(namedLoggingContext.traceContext)
        .valueOr { missingContractId =>
          ErrorUtil.internalError(
            new IllegalStateException(
              s"Contract $missingContractId is in the active contract store but not in the contract store"
            )
          )
        }

    def lookupChangeMetadata(
        activations: Map[LfContractId, TransferCounterO]
    ): Future[AcsChange] = {
      for {
        // TODO(i9270) extract magic numbers
        storedActivatedContracts <- MonadUtil.batchedSequentialTraverse(
          parallelism = PositiveInt.tryCreate(20),
          chunkSize = PositiveInt.tryCreate(500),
        )(activations.keySet.toSeq)(withMetadataSeq)
      } yield {
        AcsChange(
          activations = storedActivatedContracts
            .map(c =>
              c.contractId -> WithContractHash.fromContract(
                c.contract,
                ContractMetadataAndTransferCounter(
                  c.contract.metadata,
                  activations(c.contractId),
                ),
              )
            )
            .toMap,
          deactivations = Map.empty,
        )
      }
    }

    if (enableAdditionalConsistencyChecks) {
      for {
        activeContracts <- activeContractStore.snapshot(toInclusive)(
          namedLoggingContext.traceContext
        )
        activations = activeContracts.map { case (cid, (toc, transferCounter)) =>
          (cid, transferCounter)
        }
        change <- lookupChangeMetadata(activations)
      } yield {
        val emptyRunningCommitments =
          new RunningCommitments(RecordTime.MinValue, TrieMap.empty[SortedSet[LfPartyId], LtHash16])
        val toc = new RecordTime(toInclusive, 0)
        emptyRunningCommitments.update(toc, change)
        val acsCommitments = emptyRunningCommitments.snapshot().active
        if (acsCommitments != runningCommitments) {
          Errors.InternalError
            .InconsistentRunningCommitmentAndACS(toc, acsCommitments, runningCommitments)
            .discard
        }
      }
    } else Future.unit
  }

  object Errors extends AcsCommitmentErrorGroup {
    @Explanation(
      """This error indicates that there was an internal error within the ACS commitment processing."""
    )
    @Resolution("Inspect error message for details.")
    object InternalError
        extends ErrorCode(
          id = "ACS_COMMITMENT_INTERNAL_ERROR",
          ErrorCategory.SystemInternalAssumptionViolated,
        ) {

      override protected def exposedViaApi: Boolean = false

      final case class MultipleCommitmentsInBatch(
          domain: DomainId,
          timestamp: CantonTimestamp,
          num: Int,
      )(implicit
          val loggingContext: ErrorLoggingContext
      ) extends CantonError.Impl(
            cause = "Received multiple batched ACS commitments over domain"
          )

      @Explanation(
        """This error indicates that the running commitments at the participant at the tick time do not match
          the state found in the active contract store at the same tick time.
          This error indicates a bug in computing the commitments."""
      )
      @Resolution("Contact customer support.")
      final case class InconsistentRunningCommitmentAndACS(
          toc: RecordTime,
          acsCommitments: Map[SortedSet[LfPartyId], AcsCommitment.CommitmentType],
          runningCommitments: Map[SortedSet[LfPartyId], AcsCommitment.CommitmentType],
      )(implicit val loggingContext: ErrorLoggingContext)
          extends CantonError.Impl(
            cause = "Detected an inconsistency between the running commitment and the ACS"
          )
    }

    object MismatchError extends ErrorGroup {
      @Explanation("""This error indicates that a remote participant has sent a commitment over
          |an ACS for a period, while this participant does not think that there is a shared contract state.
          |This error occurs if a remote participant has manually changed contracts using repair,
          |or due to byzantine behavior, or due to malfunction of the system. The consequence is that
          |the ledger is forked, and some commands that should pass will not.""")
      @Resolution(
        """Please contact the other participant in order to check the cause of the mismatch. Either repair
          |the store of this participant or of the counterparty."""
      )
      object NoSharedContracts extends AlarmErrorCode(id = "ACS_MISMATCH_NO_SHARED_CONTRACTS") {
        final case class Mismatch(domain: DomainId, remote: AcsCommitment)
            extends Alarm(
              cause = "Received a commitment where we have no shared contract with the sender"
            )
      }

      @Explanation("""This error indicates that a remote participant has sent a commitment over
          |an ACS for a period which does not match the local commitment.
          |This error occurs if a remote participant has manually changed contracts using repair,
          |or due to byzantine behavior, or due to malfunction of the system. The consequence is that the ledger is forked,
          |and some commands that should pass will not.""")
      @Resolution(
        """Please contact the other participant in order to check the cause of the mismatch. Either repair
          |the store of this participant or of the counterparty."""
      )
      object CommitmentsMismatch extends AlarmErrorCode(id = "ACS_COMMITMENT_MISMATCH") {
        final case class Mismatch(
            domain: DomainId,
            remote: AcsCommitment,
            local: Seq[(CommitmentPeriod, AcsCommitment.CommitmentType)],
        ) extends Alarm(cause = "The local commitment does not match the remote commitment")
      }

      @Explanation("The participant has detected that another node is behaving maliciously.")
      @Resolution("Contact support.")
      object AcsCommitmentAlarm extends AlarmErrorCode(id = "ACS_COMMITMENT_ALARM") {
        final case class Warn(override val cause: String) extends Alarm(cause)
      }
    }
  }
}
