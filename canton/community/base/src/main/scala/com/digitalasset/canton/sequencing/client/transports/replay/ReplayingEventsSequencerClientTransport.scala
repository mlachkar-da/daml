// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.sequencing.client.transports.replay

import cats.data.EitherT
import com.digitalasset.canton.DiscardOps
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.metrics.SequencerClientMetrics
import com.digitalasset.canton.sequencing.client.SequencerClient.ReplayStatistics
import com.digitalasset.canton.sequencing.client.*
import com.digitalasset.canton.sequencing.client.transports.SequencerClientTransport
import com.digitalasset.canton.sequencing.client.transports.replay.ReplayingEventsSequencerClientTransport.ReplayingSequencerSubscription
import com.digitalasset.canton.sequencing.handshake.HandshakeRequestError
import com.digitalasset.canton.sequencing.protocol.{
  AcknowledgeRequest,
  HandshakeRequest,
  HandshakeResponse,
  SignedContent,
  SubmissionRequest,
  SubscriptionRequest,
  TopologyStateForInitRequest,
  TopologyStateForInitResponse,
}
import com.digitalasset.canton.sequencing.{SequencerClientRecorder, SerializedEventHandler}
import com.digitalasset.canton.topology.store.StoredTopologyTransactionsX
import com.digitalasset.canton.tracing.{TraceContext, Traced}
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.util.{ErrorUtil, FutureUtil, MonadUtil}
import com.digitalasset.canton.version.ProtocolVersion

import java.nio.file.Path
import java.time.{Duration as JDuration, Instant}
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

/** Transport implementation for replaying messages from a file.
  * @param replayPath points to a file containing events to be replayed.
  *                   The events must be serialized versions of `TracedSignedSerializedSequencedEvent`.
  */
class ReplayingEventsSequencerClientTransport(
    protocolVersion: ProtocolVersion,
    replayPath: Path,
    metrics: SequencerClientMetrics,
    override protected val timeouts: ProcessingTimeout,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit executionContext: ExecutionContext)
    extends SequencerClientTransport
    with NamedLogging {

  /** Does nothing. */
  override def sendAsync(
      request: SubmissionRequest,
      timeout: Duration,
  )(implicit
      traceContext: TraceContext
  ): EitherT[Future, SendAsyncClientError, Unit] = EitherT.rightT(())

  /** Does nothing. */
  override def sendAsyncUnauthenticated(
      request: SubmissionRequest,
      timeout: Duration,
  )(implicit
      traceContext: TraceContext
  ): EitherT[Future, SendAsyncClientError, Unit] = EitherT.rightT(())

  /** Does nothing */
  override def sendAsyncSigned(
      request: SignedContent[SubmissionRequest],
      timeout: Duration,
  )(implicit traceContext: TraceContext): EitherT[Future, SendAsyncClientError, Unit] =
    EitherT.rightT(())

  /** Does nothing */
  override def acknowledge(request: AcknowledgeRequest)(implicit
      traceContext: TraceContext
  ): Future[Unit] = Future.unit

  /** Does nothing */
  override def acknowledgeSigned(request: SignedContent[AcknowledgeRequest])(implicit
      traceContext: TraceContext
  ): EitherT[Future, String, Unit] =
    EitherT.rightT(())

  /** Replays all events in `replayPath` to the handler. */
  override def subscribe[E](request: SubscriptionRequest, handler: SerializedEventHandler[E])(
      implicit traceContext: TraceContext
  ): ReplayingSequencerSubscription[E] = {
    logger.info("Loading messages for replaying...")
    val messages = ErrorUtil.withThrowableLogging {
      SequencerClientRecorder.loadEvents(replayPath, logger)
    }
    logger.info(s"Start feeding ${messages.size} messages to the subscription...")
    val startTime = CantonTimestamp.now()
    val replayF = MonadUtil
      .sequentialTraverse_(messages) { e =>
        logger.debug(
          s"Replaying event with sequencer counter ${e.counter} and timestamp ${e.timestamp}"
        )(e.traceContext)
        for {
          unitOrErr <- handler(e)
        } yield unitOrErr match {
          case Left(err) =>
            logger.error(s"The sequencer handler returned an error: $err")
          case Right(()) =>
        }
      }
      .map { _ =>
        val duration = JDuration.between(startTime.toInstant, Instant.now)
        logger.info(
          show"Finished feeding ${messages.size} messages within $duration to the subscription."
        )
        SequencerClient.replayStatistics.add(
          ReplayStatistics(replayPath, messages.size, startTime, duration)
        )
      }

    FutureUtil.doNotAwait(replayF, "An exception has occurred while replaying messages.")
    new ReplayingSequencerSubscription(timeouts, loggerFactory)
  }

  override def subscribeUnauthenticated[E](
      request: SubscriptionRequest,
      handler: SerializedEventHandler[E],
  )(implicit traceContext: TraceContext): SequencerSubscription[E] = subscribe(request, handler)

  /** Will never request a retry. */
  override def subscriptionRetryPolicy: SubscriptionErrorRetryPolicy =
    SubscriptionErrorRetryPolicy.never

  /** Will always succeed. */
  override def handshake(request: HandshakeRequest)(implicit
      traceContext: TraceContext
  ): EitherT[Future, HandshakeRequestError, HandshakeResponse] =
    EitherT.rightT(HandshakeResponse.Success(protocolVersion))

  override def downloadTopologyStateForInit(request: TopologyStateForInitRequest)(implicit
      traceContext: TraceContext
  ): EitherT[Future, String, TopologyStateForInitResponse] =
    EitherT.rightT[Future, String](
      TopologyStateForInitResponse(
        topologyTransactions = Traced(StoredTopologyTransactionsX.empty)
      )
    )
}

object ReplayingEventsSequencerClientTransport {

  /** Does nothing until closed or completed. */
  class ReplayingSequencerSubscription[E](
      override protected val timeouts: ProcessingTimeout,
      override protected val loggerFactory: NamedLoggerFactory,
  )(implicit val executionContext: ExecutionContext)
      extends SequencerSubscription[E] {
    override private[canton] def complete(reason: SubscriptionCloseReason[E])(implicit
        traceContext: TraceContext
    ): Unit = {
      closeReasonPromise.trySuccess(reason).discard[Boolean]
      close()
    }
  }
}
