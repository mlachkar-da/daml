// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.domain.mediator

import cats.data.EitherT
import cats.syntax.option.*
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.*
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CachingConfigs
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, PositiveInt}
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.crypto.provider.symbolic.SymbolicCrypto
import com.digitalasset.canton.data.ViewType.{TransactionViewType, TransferInViewType}
import com.digitalasset.canton.data.*
import com.digitalasset.canton.domain.mediator.ResponseAggregation.ConsortiumVotingState
import com.digitalasset.canton.domain.mediator.store.{
  InMemoryFinalizedResponseStore,
  InMemoryMediatorDeduplicationStore,
  MediatorState,
}
import com.digitalasset.canton.domain.metrics.DomainTestMetrics
import com.digitalasset.canton.error.MediatorError
import com.digitalasset.canton.logging.LogEntry
import com.digitalasset.canton.protocol.*
import com.digitalasset.canton.protocol.messages.Verdict.Approve
import com.digitalasset.canton.protocol.messages.*
import com.digitalasset.canton.sequencing.client.{
  SendAsyncClientError,
  SendCallback,
  SendType,
  SequencerClientSend,
}
import com.digitalasset.canton.sequencing.protocol.*
import com.digitalasset.canton.time.{Clock, DomainTimeTracker, NonNegativeFiniteDuration}
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.topology.client.TopologySnapshot
import com.digitalasset.canton.topology.transaction.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil.{sequentialTraverse, sequentialTraverse_}
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.version.{HasTestCloseContext, ProtocolVersion}
import com.google.protobuf.ByteString
import org.mockito.ArgumentMatchers.eq as eqMatch
import org.scalatest.Assertion
import org.scalatest.wordspec.AsyncWordSpec

import scala.annotation.nowarn
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import scala.language.reflectiveCalls

@nowarn("msg=match may not be exhaustive")
abstract class ConfirmationResponseProcessorTestV5Base(minimumPV: ProtocolVersion)
    extends AsyncWordSpec
    with BaseTest
    with HasTestCloseContext {

  val domainId: DomainId = DomainId(
    UniqueIdentifier.tryFromProtoPrimitive("domain::test")
  )
  val activeMediator1 = MediatorId(UniqueIdentifier.tryCreate("mediator", "one"))
  val activeMediator2 = MediatorId(UniqueIdentifier.tryCreate("mediator", "two"))
  val passiveMediator3 = MediatorId(UniqueIdentifier.tryCreate("mediator", "three"))

  val mediatorGroup: MediatorGroup = MediatorGroup(
    index = NonNegativeInt.zero,
    active = Seq(
      activeMediator1,
      activeMediator2,
    ),
    passive = Seq(
      passiveMediator3
    ),
    threshold = PositiveInt.tryCreate(2),
  )

  def mediatorId: MediatorId
  def mediatorRef: MediatorRef

  lazy val factory: ExampleTransactionFactory =
    new ExampleTransactionFactory()(domainId = domainId, mediatorRef = mediatorRef)
  lazy val fullInformeeTree: FullInformeeTree =
    factory.MultipleRootsAndViewNestings.fullInformeeTree
  lazy val view: TransactionView = factory.MultipleRootsAndViewNestings.view0
  val participant: ParticipantId = ExampleTransactionFactory.submitterParticipant

  lazy val view0Position =
    factory.MultipleRootsAndViewNestings.transactionViewTree0.viewPosition
  lazy val view1Position =
    factory.MultipleRootsAndViewNestings.transactionViewTree1.viewPosition
  lazy val view10Position =
    factory.MultipleRootsAndViewNestings.transactionViewTree10.viewPosition
  lazy val view11Position =
    factory.MultipleRootsAndViewNestings.transactionViewTree11.viewPosition
  lazy val view110Position =
    factory.MultipleRootsAndViewNestings.transactionViewTree110.viewPosition

  val notSignificantCounter: SequencerCounter = SequencerCounter(0)

  val initialDomainParameters: DynamicDomainParameters = TestDomainParameters.defaultDynamic

  private lazy val localVerdictProtocolVersion =
    LocalVerdict.protocolVersionRepresentativeFor(testedProtocolVersion)

  val participantResponseTimeout: NonNegativeFiniteDuration =
    NonNegativeFiniteDuration.tryOfMillis(100L)

  lazy val submitter = ExampleTransactionFactory.submitter
  lazy val signatory = ExampleTransactionFactory.signatory
  lazy val observer = ExampleTransactionFactory.observer

  // Create a topology with several participants so that we can have several root hash messages or Malformed messages
  val participant1 = participant
  val participant2 = ExampleTransactionFactory.signatoryParticipant
  val participant3 = ParticipantId("participant3")

  def identityFactory: TestingIdentityFactoryBase

  def identityFactory2: TestingIdentityFactoryBase

  def identityFactoryNoParticipants: TestingIdentityFactoryBase

  lazy val domainSyncCryptoApi: DomainSyncCryptoClient =
    identityFactory.forOwnerAndDomain(mediatorId, domainId)

  lazy val requestIdTs = CantonTimestamp.Epoch
  lazy val requestId = RequestId(requestIdTs)
  lazy val decisionTime = requestIdTs.plusSeconds(120)

  class Fixture(syncCryptoApi: DomainSyncCryptoClient = domainSyncCryptoApi) {
    val interceptedBatchesQueue: java.util.concurrent.BlockingQueue[
      (Batch[DefaultOpenEnvelope], Option[AggregationRule])
    ] =
      new java.util.concurrent.LinkedBlockingQueue()

    private val sequencerSend: SequencerClientSend = new SequencerClientSend {
      override def sendAsync(
          batch: Batch[DefaultOpenEnvelope],
          sendType: SendType,
          timestampOfSigningKey: Option[CantonTimestamp],
          maxSequencingTime: CantonTimestamp,
          messageId: MessageId,
          aggregationRule: Option[AggregationRule],
          callback: SendCallback,
      )(implicit traceContext: TraceContext): EitherT[Future, SendAsyncClientError, Unit] = {
        interceptedBatchesQueue.add((batch, aggregationRule))
        EitherT.pure(())
      }

      override def generateMaxSequencingTime: CantonTimestamp = ???
    }

    def interceptedBatches: Iterable[Batch[DefaultOpenEnvelope]] =
      interceptedBatchesQueue.asScala.map(_._1)

    def interceptedBatchesWithAggRule
        : Iterable[(Batch[DefaultOpenEnvelope], Option[AggregationRule])] =
      interceptedBatchesQueue.asScala

    val verdictSender: TestVerdictSender =
      new TestVerdictSender(
        syncCryptoApi,
        mediatorId,
        sequencerSend,
        testedProtocolVersion,
        loggerFactory,
      )
    val timeTracker: DomainTimeTracker = mock[DomainTimeTracker]
    val mediatorState = new MediatorState(
      new InMemoryFinalizedResponseStore(loggerFactory),
      new InMemoryMediatorDeduplicationStore(loggerFactory, timeouts),
      mock[Clock],
      DomainTestMetrics.mediator,
      testedProtocolVersion,
      CachingConfigs.defaultFinalizedMediatorRequestsCache,
      timeouts,
      loggerFactory,
    )
    val processor = new ConfirmationResponseProcessor(
      domainId,
      mediatorId,
      verdictSender,
      syncCryptoApi,
      timeTracker,
      mediatorState,
      testedProtocolVersion,
      loggerFactory,
      timeouts,
    )
  }

  lazy val domainSyncCryptoApi2: DomainSyncCryptoClient =
    identityFactory2.forOwnerAndDomain(SequencerId(domainId), domainId)

  def signedResponse(
      confirmers: Set[LfPartyId],
      view: TransactionView,
      viewPosition: ViewPosition,
      verdict: LocalVerdict,
      requestId: RequestId,
  ): Future[SignedProtocolMessage[MediatorResponse]] = {
    val response: MediatorResponse = MediatorResponse.tryCreate(
      requestId,
      participant,
      Some(view.viewHash),
      Some(viewPosition),
      verdict,
      Some(fullInformeeTree.transactionId.toRootHash),
      confirmers,
      factory.domainId,
      testedProtocolVersion,
    )
    val participantCrypto = identityFactory.forOwner(participant)
    SignedProtocolMessage.trySignAndCreate(
      response,
      participantCrypto.tryForDomain(domainId).currentSnapshotApproximation,
      testedProtocolVersion,
    )
  }

  if (testedProtocolVersion >= minimumPV) {
    "ConfirmationResponseProcessor" should {
      def shouldBeViewThresholdBelowMinimumAlarm(
          requestId: RequestId,
          viewPosition: ViewPosition,
      ): LogEntry => Assertion =
        _.shouldBeCantonError(
          MediatorError.MalformedMessage,
          _ shouldBe s"Received a mediator request with id $requestId having threshold 0 for transaction view at $viewPosition, which is below the confirmation policy's minimum threshold of 1. Rejecting request...",
        )

      lazy val rootHashMessages = Seq(
        OpenEnvelope(
          RootHashMessage(
            fullInformeeTree.tree.rootHash,
            domainId,
            testedProtocolVersion,
            TransactionViewType,
            SerializedRootHashMessagePayload.empty,
          ),
          Recipients.cc(MemberRecipient(participant), mediatorRef.toRecipient),
        )(testedProtocolVersion)
      )

      "timestamp of mediator request is propagated" in {
        val sut = new Fixture()
        val testMediatorRequest = new InformeeMessage(fullInformeeTree)(testedProtocolVersion) {
          val (firstFaultyViewPosition: ViewPosition, _) =
            super.informeesAndThresholdByViewPosition.head

          override def informeesAndThresholdByViewPosition
              : Map[ViewPosition, (Set[Informee], NonNegativeInt)] = {
            super.informeesAndThresholdByViewPosition map { case (key, (informees, _)) =>
              (key, (informees, NonNegativeInt.zero))
            }
          }

          override def rootHash: Option[RootHash] = Some(fullInformeeTree.tree.rootHash)
        }
        val requestTimestamp = CantonTimestamp.Epoch.plusSeconds(120)
        for {
          _ <- loggerFactory.assertLogs(
            sut.processor.processRequest(
              RequestId(requestTimestamp),
              SequencerCounter(0),
              requestTimestamp.plusSeconds(60),
              requestTimestamp.plusSeconds(120),
              testMediatorRequest,
              rootHashMessages,
              batchAlsoContainsTopologyXTransaction = false,
            ),
            shouldBeViewThresholdBelowMinimumAlarm(
              RequestId(requestTimestamp),
              testMediatorRequest.firstFaultyViewPosition,
            ),
          )

        } yield {
          val sentResult = sut.verdictSender.sentResults.loneElement
          sentResult.requestId.unwrap shouldBe requestTimestamp
        }
      }

      "request timestamp is propagated to mediator result when response aggregation is performed" should {
        // Send mediator request
        val informeeMessage = new InformeeMessage(fullInformeeTree)(testedProtocolVersion) {
          val faultyViewPosition: ViewPosition =
            super.informeesAndThresholdByViewPosition.collectFirst {
              case (key, (informee, _)) if informee != Set(submitter) => key
            }.value

          override def informeesAndThresholdByViewPosition
              : Map[ViewPosition, (Set[Informee], NonNegativeInt)] = {
            super.informeesAndThresholdByViewPosition map { case (key, (informee, _)) =>
              if (key == faultyViewPosition) (key, (informee, NonNegativeInt.zero))
              else (key, (informee, NonNegativeInt.one))
            }
          }

          override def rootHash: Option[RootHash] = Some(fullInformeeTree.tree.rootHash)
        }

        val requestTimestamp = CantonTimestamp.Epoch.plusSeconds(12345)
        val reqId = RequestId(requestTimestamp)
        val mockSnapshot = mock[DomainSnapshotSyncCryptoApi]
        val mockSignature = SymbolicCrypto.emptySignature

        val mockTopologySnapshot = mock[TopologySnapshot]
        when(
          mockTopologySnapshot.findDynamicDomainParametersOrDefault(
            any[ProtocolVersion],
            anyBoolean,
          )(any[TraceContext])
        )
          .thenReturn(Future.successful(initialDomainParameters))
        when(mockTopologySnapshot.canConfirm(any[ParticipantId], any[LfPartyId], any[TrustLevel]))
          .thenReturn(Future.successful(true))
        when(mockTopologySnapshot.consortiumThresholds(any[Set[LfPartyId]])).thenAnswer {
          parties: Set[LfPartyId] => Future.successful(parties.map(x => x -> PositiveInt.one).toMap)
        }
        when(mockTopologySnapshot.mediatorGroup(any[NonNegativeInt]))
          .thenReturn(Future.successful(Some(mediatorGroup)))
        when(mockSnapshot.ipsSnapshot).thenReturn(mockTopologySnapshot)
        when(mockSnapshot.verifySignatures(any[Hash], any[KeyOwner], any[NonEmpty[Seq[Signature]]]))
          .thenReturn(EitherT.rightT(()))
        when(mockSnapshot.sign(any[Hash])(anyTraceContext))
          .thenReturn(EitherT.rightT[Future, SyncCryptoError](mockSignature))
        when(mockSnapshot.pureCrypto).thenReturn(domainSyncCryptoApi.pureCrypto)

        val mockedSnapshotCrypto = new DomainSyncCryptoClient(
          domainSyncCryptoApi.owner,
          domainSyncCryptoApi.domainId,
          domainSyncCryptoApi.ips,
          domainSyncCryptoApi.crypto,
          CachingConfigs.testing,
          timeouts,
          FutureSupervisor.Noop,
          loggerFactory,
        ) {
          override def awaitSnapshot(timestamp: CantonTimestamp)(implicit
              traceContext: TraceContext
          ): Future[DomainSnapshotSyncCryptoApi] =
            if (timestamp == requestTimestamp) {
              Future.successful(mockSnapshot)
            } else {
              super.snapshot(timestamp)
            }
        }

        val responseF =
          signedResponse(
            Set(submitter),
            view,
            view0Position,
            LocalApprove(testedProtocolVersion),
            reqId,
          )

        def handleEvents(sut: ConfirmationResponseProcessor): Future[Unit] =
          for {
            response <- responseF
            _ <- loggerFactory.assertLogs(
              sut.processRequest(
                reqId,
                notSignificantCounter,
                requestTimestamp.plusSeconds(60),
                requestTimestamp.plusSeconds(120),
                informeeMessage,
                rootHashMessages,
                batchAlsoContainsTopologyXTransaction = false,
              ),
              shouldBeViewThresholdBelowMinimumAlarm(reqId, informeeMessage.faultyViewPosition),
            )
            _ <- sut.processResponse(
              CantonTimestamp.Epoch,
              notSignificantCounter,
              requestTimestamp.plusSeconds(60),
              requestTimestamp.plusSeconds(120),
              response,
              Recipients.cc(mediatorRef.toRecipient),
            )
          } yield ()

        "verifies the response signature with the timestamp from the request" in {
          val sut = new Fixture(mockedSnapshotCrypto)
          for {
            response <- responseF
            _ <- handleEvents(sut.processor)
            _ = verify(mockSnapshot, timeout(1000)).verifySignatures(
              any[Hash],
              any[KeyOwner],
              eqMatch(response.signatures),
            )
          } yield succeed
        }

        "mediator response contains timestamp from the request" in {
          val sut = new Fixture(mockedSnapshotCrypto)
          for {
            _ <- handleEvents(sut.processor)
          } yield {
            val sentResult = sut.verdictSender.sentResults.loneElement
            sentResult.requestId.unwrap shouldBe requestTimestamp
          }
        }
      }

      "accept root hash messages" in {
        val sut = new Fixture(domainSyncCryptoApi2)
        val correctRootHash = RootHash(TestHash.digest("root-hash"))
        // Create a custom informee message with several recipient participants
        val informeeMessage = new InformeeMessage(fullInformeeTree)(testedProtocolVersion) {
          override val informeesAndThresholdByViewPosition
              : Map[ViewPosition, (Set[Informee], NonNegativeInt)] = {
            val submitterI = Informee.create(submitter, NonNegativeInt.one, TrustLevel.Ordinary)
            val signatoryI = Informee.create(signatory, NonNegativeInt.one, TrustLevel.Ordinary)
            val observerI = Informee.create(observer, NonNegativeInt.one, TrustLevel.Ordinary)
            Map(
              ViewPosition.root -> (Set(
                submitterI,
                signatoryI,
                observerI,
              ) -> NonNegativeInt.one)
            )
          }

          override def rootHash: Option[RootHash] = correctRootHash.some
        }
        val allParticipants = NonEmpty(Seq, participant1, participant2, participant3)

        val correctViewType = informeeMessage.viewType
        val rootHashMessage =
          RootHashMessage(
            correctRootHash,
            domainId,
            testedProtocolVersion,
            correctViewType,
            SerializedRootHashMessagePayload.empty,
          )

        val tests = List[(String, Seq[Recipients])](
          "individual messages" -> allParticipants.map(p =>
            Recipients.cc(mediatorRef.toRecipient, MemberRecipient(p))
          ),
          "just one message" -> Seq(
            Recipients.recipientGroups(
              allParticipants.map(p =>
                NonEmpty.mk(Set, MemberRecipient(p), mediatorRef.toRecipient)
              )
            )
          ),
          "mixed" -> Seq(
            Recipients.recipientGroups(
              NonEmpty.mk(
                Seq,
                NonEmpty.mk(Set, MemberRecipient(participant1), mediatorRef.toRecipient),
                NonEmpty.mk(Set, MemberRecipient(participant2), mediatorRef.toRecipient),
              )
            ),
            Recipients.cc(MemberRecipient(participant3), mediatorRef.toRecipient),
          ),
        )

        sequentialTraverse_(tests.zipWithIndex) { case ((_testName, recipients), i) =>
          withClueF("testname") {
            val rootHashMessages =
              recipients.map(r => OpenEnvelope(rootHashMessage, r)(testedProtocolVersion))
            val ts = CantonTimestamp.ofEpochSecond(i.toLong)
            sut.processor.processRequest(
              RequestId(ts),
              notSignificantCounter,
              ts.plusSeconds(60),
              ts.plusSeconds(120),
              informeeMessage,
              rootHashMessages,
              batchAlsoContainsTopologyXTransaction = false,
            )
          }
        }.map(_ => succeed)
      }

      "send rejections when receiving wrong root hash messages" in {
        val sut = new Fixture()

        val informeeMessage = InformeeMessage(fullInformeeTree)(testedProtocolVersion)
        val rootHash = informeeMessage.rootHash.value
        val wrongRootHash =
          RootHash(
            domainSyncCryptoApi.pureCrypto.digest(TestHash.testHashPurpose, ByteString.EMPTY)
          )
        val correctViewType = informeeMessage.viewType
        val wrongViewType = TransferInViewType
        require(correctViewType != wrongViewType)
        val correctRootHashMessage =
          RootHashMessage(
            rootHash,
            domainId,
            testedProtocolVersion,
            correctViewType,
            SerializedRootHashMessagePayload.empty,
          )
        val wrongRootHashMessage = correctRootHashMessage.copy(rootHash = wrongRootHash)
        val wrongViewTypeRHM = correctRootHashMessage.copy(viewType = wrongViewType)
        val otherParticipant = participant2

        def exampleForRequest(
            request: MediatorRequest,
            rootHashMessages: (RootHashMessage[SerializedRootHashMessagePayload], Recipients)*
        ): (
            MediatorRequest,
            List[OpenEnvelope[RootHashMessage[SerializedRootHashMessagePayload]]],
        ) =
          (
            request,
            rootHashMessages.map { case (rootHashMessage, recipients) =>
              OpenEnvelope(rootHashMessage, recipients)(testedProtocolVersion)
            }.toList,
          )

        def example(
            rootHashMessages: (RootHashMessage[SerializedRootHashMessagePayload], Recipients)*
        ) =
          exampleForRequest(informeeMessage, rootHashMessages: _*)

        val batchWithoutRootHashMessages = example()
        val batchWithWrongRootHashMessage =
          example(
            wrongRootHashMessage -> Recipients.cc(
              mediatorRef.toRecipient,
              MemberRecipient(participant),
            )
          )
        val batchWithWrongViewType =
          example(
            wrongViewTypeRHM -> Recipients.cc(mediatorRef.toRecipient, MemberRecipient(participant))
          )
        val batchWithDifferentViewTypes =
          example(
            correctRootHashMessage -> Recipients
              .cc(mediatorRef.toRecipient, MemberRecipient(participant)),
            wrongViewTypeRHM -> Recipients.cc(
              mediatorRef.toRecipient,
              MemberRecipient(otherParticipant),
            ),
          )
        val batchWithRootHashMessageWithTooManyRecipients =
          example(
            correctRootHashMessage -> Recipients.cc(
              mediatorRef.toRecipient,
              MemberRecipient(participant),
              MemberRecipient(otherParticipant),
            )
          )
        val batchWithRootHashMessageWithTooFewRecipients =
          example(correctRootHashMessage -> Recipients.cc(mediatorRef.toRecipient))
        val batchWithRepeatedRootHashMessage = example(
          correctRootHashMessage -> Recipients
            .cc(mediatorRef.toRecipient, MemberRecipient(participant)),
          correctRootHashMessage -> Recipients.cc(
            mediatorRef.toRecipient,
            MemberRecipient(participant),
          ),
        )
        val batchWithDivergingRootHashMessages = example(
          correctRootHashMessage -> Recipients
            .cc(mediatorRef.toRecipient, MemberRecipient(participant)),
          wrongRootHashMessage -> Recipients.cc(
            mediatorRef.toRecipient,
            MemberRecipient(participant),
          ),
        )
        val batchWithSuperfluousRootHashMessage = example(
          correctRootHashMessage -> Recipients
            .cc(mediatorRef.toRecipient, MemberRecipient(participant)),
          correctRootHashMessage -> Recipients.cc(
            mediatorRef.toRecipient,
            MemberRecipient(otherParticipant),
          ),
        )
        val batchWithDifferentPayloads = example(
          correctRootHashMessage -> Recipients
            .cc(mediatorRef.toRecipient, MemberRecipient(participant)),
          correctRootHashMessage.copy(
            payload = SerializedRootHashMessagePayload(ByteString.copyFromUtf8("other paylroosoad"))
          ) -> Recipients
            .cc(mediatorRef.toRecipient, MemberRecipient(otherParticipant)),
        )
        val requestWithoutExpectedRootHashMessage = exampleForRequest(
          new InformeeMessage(fullInformeeTree)(testedProtocolVersion) {
            override def rootHash: Option[RootHash] = None
          },
          correctRootHashMessage -> Recipients.cc(
            mediatorRef.toRecipient,
            MemberRecipient(participant),
          ),
        )

        // format: off
        val testCases
        : Seq[(((MediatorRequest, List[OpenEnvelope[RootHashMessage[SerializedRootHashMessagePayload]]]), String),
          List[(Set[Member], ViewType)])] = List(

          (batchWithoutRootHashMessages -> show"Missing root hash message for informee participants: $participant") -> List.empty,

          (batchWithWrongRootHashMessage -> show"Wrong root hashes: $wrongRootHash") ->
            List(Set[Member](participant) -> correctViewType),

          (batchWithWrongViewType -> show"View types in root hash messages differ from expected view type $correctViewType: $wrongViewType") ->
            List(Set[Member](participant) -> wrongViewType),

          (batchWithDifferentViewTypes -> show"View types in root hash messages differ from expected view type $correctViewType: $wrongViewType") ->
            List(Set[Member](participant) -> correctViewType, Set[Member](otherParticipant) -> wrongViewType),

          (batchWithRootHashMessageWithTooManyRecipients ->
            show"Root hash messages with wrong recipients tree: RecipientsTree(recipient group = Seq(${mediatorRef.toRecipient}, ${MemberRecipient(participant)}, ${MemberRecipient(otherParticipant)}))") ->
            List(Set[Member](participant, otherParticipant) -> correctViewType),

          (batchWithRootHashMessageWithTooFewRecipients -> show"Root hash messages with wrong recipients tree: RecipientsTree(recipient group = ${mediatorRef.toRecipient})") -> List.empty,

          (batchWithRepeatedRootHashMessage             -> show"Several root hash messages for recipients: ${MemberRecipient(participant)}") ->
            List(Set[Member](participant) -> correctViewType),

          (batchWithDivergingRootHashMessages -> show"Several root hash messages for recipients: ${MemberRecipient(participant)}") ->
            List(Set[Member](participant) -> correctViewType),

          (batchWithSuperfluousRootHashMessage -> show"Superfluous root hash message for members: $otherParticipant") ->
            List(Set[Member](participant, otherParticipant) -> correctViewType),

          (batchWithDifferentPayloads -> show"Different payloads in root hash messages. Sizes: 0, 17.") ->
            List(Set[Member](participant, otherParticipant) -> correctViewType),

          (requestWithoutExpectedRootHashMessage -> show"No root hash messages expected, but received for recipients: ${MemberRecipient(participant)}") ->
            List(Set[Member](participant) -> correctViewType)
        )
        // format: on

        sequentialTraverse_(testCases.zipWithIndex) {
          case ((((req, rootHashMessages), msg), _resultRecipientsAndViewTypes), sc) =>
            val ts = CantonTimestamp.ofEpochSecond(sc.toLong)
            withClueF(s"at test case #$sc") {
              loggerFactory.assertLogs(
                // This will not send a result message because there are no root hash messages in the batch.
                sut.processor.processRequest(
                  RequestId(ts),
                  notSignificantCounter,
                  ts.plusSeconds(60),
                  ts.plusSeconds(120),
                  req,
                  rootHashMessages,
                  batchAlsoContainsTopologyXTransaction = false,
                ),
                _.shouldBeCantonError(
                  MediatorError.MalformedMessage,
                  _ shouldBe s"Received a mediator request with id ${RequestId(ts)} with invalid root hash messages. Rejecting... Reason: $msg",
                ),
              )
            }
        }.map { _ =>
          val expectedResultRecipientsAndViewTypes: List[(String, List[(Set[Member], ViewType)])] =
            testCases.map(test => (test._1._2, test._2)).filter(test => test._2.nonEmpty).toList
          val resultBatches = sut.interceptedBatches
          resultBatches.size shouldBe expectedResultRecipientsAndViewTypes.size
          forAll(resultBatches.zip(expectedResultRecipientsAndViewTypes)) {
            case (resultBatch, expectedRecipientsAndViewTypes) =>
              val results = resultBatch.envelopes.map { envelope =>
                envelope.recipients -> Some(
                  envelope.protocolMessage
                    .asInstanceOf[SignedProtocolMessage[MediatorResult]]
                    .message
                    .viewType
                )
              }
              val ungroupedResults = results.flatMap { case (Recipients(trees), vt) =>
                trees.map(_ -> vt).toList
              }.toSet

              val expectedResults = expectedRecipientsAndViewTypes._2.toSet
              val expected = expectedResults.flatMap { case (recipients, viewType) =>
                recipients.map { member: Member =>
                  RecipientsTree.leaf(NonEmpty(Set, member)) -> Some(viewType)
                }
              }
              withClue(s"Test case: ${expectedRecipientsAndViewTypes._1}") {
                ungroupedResults shouldBe expected
              }
          }
        }
      }

      "reject when declared mediator is wrong" in {
        val sut = new Fixture()

        val otherMediatorId = MediatorId(UniqueIdentifier.tryCreate("mediator", "other"))
        val factoryOtherMediatorId =
          new ExampleTransactionFactory()(
            domainId = domainId,
            mediatorRef = MediatorRef(otherMediatorId),
          )
        val fullInformeeTreeOther =
          factoryOtherMediatorId.MultipleRootsAndViewNestings.fullInformeeTree
        val mediatorRequest = InformeeMessage(fullInformeeTreeOther)(testedProtocolVersion)
        val rootHashMessage = RootHashMessage(
          mediatorRequest.rootHash.value,
          domainId,
          testedProtocolVersion,
          mediatorRequest.viewType,
          SerializedRootHashMessagePayload.empty,
        )

        val sc = 10L
        val ts = CantonTimestamp.ofEpochSecond(sc)
        for {
          _ <- loggerFactory.assertLogs(
            sut.processor.processRequest(
              RequestId(ts),
              notSignificantCounter,
              ts.plusSeconds(60),
              ts.plusSeconds(120),
              mediatorRequest,
              List(
                OpenEnvelope(
                  rootHashMessage,
                  Recipients.cc(mediatorRef.toRecipient, MemberRecipient(participant)),
                )(
                  testedProtocolVersion
                )
              ),
              batchAlsoContainsTopologyXTransaction = false,
            ),
            _.shouldBeCantonError(
              MediatorError.MalformedMessage,
              message => {
                message should (include("Rejecting mediator request") and include(
                  s"${RequestId(ts)}"
                ) and include("incorrect mediator id") and include(s"$otherMediatorId"))
              },
            ),
          )
        } yield succeed
      }

      "correct series of mediator events" in {
        val sut = new Fixture()
        val informeeMessage = InformeeMessage(fullInformeeTree)(testedProtocolVersion)
        val rootHashMessage = RootHashMessage(
          fullInformeeTree.transactionId.toRootHash,
          domainId,
          testedProtocolVersion,
          ViewType.TransactionViewType,
          SerializedRootHashMessagePayload.empty,
        )
        val mockTopologySnapshot = mock[TopologySnapshot]
        when(mockTopologySnapshot.canConfirm(any[ParticipantId], any[LfPartyId], any[TrustLevel]))
          .thenReturn(Future.successful(true))
        when(mockTopologySnapshot.consortiumThresholds(any[Set[LfPartyId]])).thenAnswer {
          parties: Set[LfPartyId] => Future.successful(parties.map(x => x -> PositiveInt.one).toMap)
        }

        for {
          _ <- sut.processor.processRequest(
            requestId,
            notSignificantCounter,
            requestIdTs.plusSeconds(60),
            decisionTime,
            informeeMessage,
            List(
              OpenEnvelope(
                rootHashMessage,
                Recipients.cc(mediatorRef.toRecipient, MemberRecipient(participant)),
              )(
                testedProtocolVersion
              )
            ),
            batchAlsoContainsTopologyXTransaction = false,
          )
          // should record the request
          requestState <- sut.mediatorState.fetch(requestId).value.map(_.value)
          responseAggregation <-
            ResponseAggregation.fromRequest(
              requestId,
              informeeMessage,
              testedProtocolVersion,
              mockTopologySnapshot,
            )

          _ = requestState shouldBe responseAggregation
          // receiving the confirmation response
          ts1 = CantonTimestamp.Epoch.plusMillis(1L)
          approvals: Seq[SignedProtocolMessage[MediatorResponse]] <- sequentialTraverse(
            List(
              view0Position -> view,
              view1Position -> factory.MultipleRootsAndViewNestings.view1,
              view11Position -> factory.MultipleRootsAndViewNestings.view11,
              view110Position -> factory.MultipleRootsAndViewNestings.view110,
            )
          ) { case (viewPosition, view) =>
            signedResponse(
              Set(submitter),
              view,
              viewPosition,
              LocalApprove(testedProtocolVersion),
              requestId,
            )
          }
          _ <- sequentialTraverse_(approvals)(
            sut.processor.processResponse(
              ts1,
              notSignificantCounter,
              ts1.plusSeconds(60),
              ts1.plusSeconds(120),
              _,
              Recipients.cc(mediatorRef.toRecipient),
            )
          )
          // records the request
          updatedState <- sut.mediatorState.fetch(requestId).value
          _ = {
            inside(updatedState) {
              case Some(
                    ResponseAggregation(
                      actualRequestId,
                      actualRequest,
                      actualVersion,
                      Right(_states),
                    )
                  ) =>
                actualRequestId shouldBe requestId
                actualRequest shouldBe informeeMessage
                actualVersion shouldBe ts1
            }
            val ResponseAggregation(
              `requestId`,
              `informeeMessage`,
              `ts1`,
              Right(states),
            ) =
              updatedState.value
            assert(
              states === Map(
                view0Position ->
                  ResponseAggregation.ViewState(
                    Set.empty,
                    Map(
                      submitter -> ConsortiumVotingState(approvals =
                        Set(ExampleTransactionFactory.submitterParticipant)
                      )
                    ),
                    0,
                    Nil,
                  ),
                view1Position ->
                  ResponseAggregation.ViewState(
                    Set(ConfirmingParty(signatory, PositiveInt.one, TrustLevel.Ordinary)),
                    Map(
                      submitter -> ConsortiumVotingState(approvals =
                        Set(ExampleTransactionFactory.submitterParticipant)
                      ),
                      signatory -> ConsortiumVotingState(),
                    ),
                    1,
                    Nil,
                  ),
                view10Position ->
                  ResponseAggregation.ViewState(
                    Set(ConfirmingParty(signatory, PositiveInt.one, TrustLevel.Ordinary)),
                    Map(signatory -> ConsortiumVotingState()),
                    1,
                    Nil,
                  ),
                view11Position ->
                  ResponseAggregation.ViewState(
                    Set(ConfirmingParty(signatory, PositiveInt.one, TrustLevel.Ordinary)),
                    Map(
                      submitter -> ConsortiumVotingState(approvals =
                        Set(ExampleTransactionFactory.submitterParticipant)
                      ),
                      signatory -> ConsortiumVotingState(),
                    ),
                    1,
                    Nil,
                  ),
                view110Position ->
                  ResponseAggregation.ViewState(
                    Set.empty,
                    Map(
                      submitter -> ConsortiumVotingState(approvals =
                        Set(ExampleTransactionFactory.submitterParticipant)
                      )
                    ),
                    0,
                    Nil,
                  ),
              )
            )
          }
          // receiving the final confirmation response
          ts2 = CantonTimestamp.Epoch.plusMillis(2L)
          approvals <- sequentialTraverse(
            List(
              view1Position -> factory.MultipleRootsAndViewNestings.view1,
              view10Position -> factory.MultipleRootsAndViewNestings.view10,
              view11Position -> factory.MultipleRootsAndViewNestings.view11,
            )
          ) { case (viewPosition, view) =>
            signedResponse(
              Set(signatory),
              view,
              viewPosition,
              LocalApprove(testedProtocolVersion),
              requestId,
            )
          }
          _ <- sequentialTraverse_(approvals)(
            sut.processor.processResponse(
              ts2,
              notSignificantCounter,
              ts2.plusSeconds(60),
              ts2.plusSeconds(120),
              _,
              Recipients.cc(mediatorRef.toRecipient),
            )
          )
          // records the request
          finalState <- sut.mediatorState.fetch(requestId).value
        } yield {
          inside(finalState) {
            case Some(FinalizedResponse(`requestId`, `informeeMessage`, `ts2`, verdict)) =>
              assert(verdict === Approve(testedProtocolVersion))
          }
        }
      }

      "receiving Malformed responses" in {
        // receiving an informee message
        val sut = new Fixture(domainSyncCryptoApi2)

        // Create a custom informee message with many quorums such that the first Malformed rejection doesn't finalize the request
        val informeeMessage = new InformeeMessage(fullInformeeTree)(testedProtocolVersion) {
          override val informeesAndThresholdByViewPosition
              : Map[ViewPosition, (Set[Informee], NonNegativeInt)] = {
            val submitterI = Informee.create(submitter, NonNegativeInt.one, TrustLevel.Ordinary)
            val signatoryI = Informee.create(signatory, NonNegativeInt.one, TrustLevel.Ordinary)
            val observerI = Informee.create(observer, NonNegativeInt.one, TrustLevel.Ordinary)
            Map(
              view0Position -> (Set(
                submitterI,
                signatoryI,
              ) -> NonNegativeInt.one),
              view1Position -> (Set(
                submitterI,
                signatoryI,
                observerI,
              ) -> NonNegativeInt.one),
              view11Position -> (Set(
                observerI,
                signatoryI,
              ) -> NonNegativeInt.one),
              view10Position -> (Set(
                submitterI,
                signatoryI,
                observerI,
              ) -> NonNegativeInt.one),
            )
          }

          override def rootHash: Option[RootHash] = None // don't require root hash messages
        }
        val requestIdTs = CantonTimestamp.Epoch
        val requestId = RequestId(requestIdTs)

        val malformedMsg = "this is a test malformed response"

        def isMalformedWarn(participant: ParticipantId)(logEntry: LogEntry): Assertion = {
          logEntry.shouldBeCantonError(
            LocalReject.MalformedRejects.Payloads,
            _ shouldBe s"Rejected transaction due to malformed payload within views $malformedMsg",
            _ should contain("reportedBy" -> s"$participant"),
          )
        }

        def malformedResponse(
            participant: ParticipantId,
            viewHashO: Option[ViewHash] = None,
        ): Future[SignedProtocolMessage[MediatorResponse]] = {
          val response = MediatorResponse.tryCreate(
            requestId,
            participant,
            viewHashO,
            None,
            LocalReject.MalformedRejects.Payloads.Reject(malformedMsg)(localVerdictProtocolVersion),
            Some(fullInformeeTree.transactionId.toRootHash),
            Set.empty,
            factory.domainId,
            testedProtocolVersion,
          )
          val participantCrypto = identityFactory2.forOwner(participant)
          SignedProtocolMessage.trySignAndCreate(
            response,
            participantCrypto.tryForDomain(domainId).currentSnapshotApproximation,
            testedProtocolVersion,
          )
        }

        for {
          _ <- sut.processor.processRequest(
            requestId,
            notSignificantCounter,
            requestIdTs.plusSeconds(60),
            requestIdTs.plusSeconds(120),
            informeeMessage,
            List.empty,
            batchAlsoContainsTopologyXTransaction = false,
          )

          // receiving a confirmation response
          ts1 = CantonTimestamp.Epoch.plusMillis(1L)
          malformed <- sequentialTraverse(
            List(
              malformedResponse(participant1),
              malformedResponse(
                participant3,
                Some(factory.MultipleRootsAndViewNestings.view1.viewHash),
              ),
              malformedResponse(
                participant3,
                Some(factory.MultipleRootsAndViewNestings.view11.viewHash),
              ),
              malformedResponse(participant2), // This should finalize the request
              malformedResponse(
                participant3,
                Some(factory.MultipleRootsAndViewNestings.view10.viewHash),
              ),
            )
          )(Predef.identity)

          // records the request
          _ <- loggerFactory.assertLogs(
            sequentialTraverse_(malformed)(
              sut.processor.processResponse(
                ts1,
                notSignificantCounter,
                ts1.plusSeconds(60),
                ts1.plusSeconds(120),
                _,
                Recipients.cc(mediatorRef.toRecipient),
              )
            ),
            isMalformedWarn(participant1),
            isMalformedWarn(participant3),
            isMalformedWarn(participant3),
            isMalformedWarn(participant2),
            isMalformedWarn(participant3),
          )

          finalState <- sut.mediatorState.fetch(requestId).value
          _ = inside(finalState) {
            case Some(
                  FinalizedResponse(
                    _requestId,
                    _request,
                    _version,
                    Verdict.ParticipantReject(reasons),
                  )
                ) =>
              // TODO(#5337) These are only the rejections for the first view because this view happens to be finalized first.
              reasons.length shouldEqual 2
              reasons.foreach { case (party, reject) =>
                reject shouldBe LocalReject.MalformedRejects.Payloads.Reject(malformedMsg)(
                  localVerdictProtocolVersion
                )
                party should (contain(submitter) or contain(signatory))
              }
          }
        } yield succeed
      }

      "receiving late response" in {
        val sut = new Fixture()
        val requestTs = CantonTimestamp.Epoch.plusMillis(1)
        val requestId = RequestId(requestTs)
        // response is just too late
        val participantResponseDeadline = requestIdTs.plus(participantResponseTimeout.unwrap)
        val responseTs = participantResponseDeadline.addMicros(1)

        val informeeMessage = InformeeMessage(fullInformeeTree)(testedProtocolVersion)
        val rootHashMessage = RootHashMessage(
          fullInformeeTree.transactionId.toRootHash,
          domainId,
          testedProtocolVersion,
          ViewType.TransactionViewType,
          SerializedRootHashMessagePayload.empty,
        )

        for {
          _ <- sut.processor.processRequest(
            requestId,
            notSignificantCounter,
            requestIdTs.plus(participantResponseTimeout.unwrap),
            requestIdTs.plusSeconds(120),
            informeeMessage,
            List(
              OpenEnvelope(
                rootHashMessage,
                Recipients.cc(mediatorRef.toRecipient, MemberRecipient(participant)),
              )(
                testedProtocolVersion
              )
            ),
            batchAlsoContainsTopologyXTransaction = false,
          )
          response <- signedResponse(
            Set(submitter),
            view,
            ViewPosition.root,
            LocalApprove(testedProtocolVersion),
            requestId,
          )
          _ <- loggerFactory.assertLogs(
            sut.processor
              .processResponse(
                responseTs,
                notSignificantCounter + 1,
                participantResponseDeadline,
                requestIdTs.plusSeconds(120),
                response,
                Recipients.cc(mediatorRef.toRecipient),
              ),
            _.warningMessage shouldBe s"Response $responseTs is too late as request RequestId($requestTs) has already exceeded the participant response deadline [$participantResponseDeadline]",
          )
        } yield succeed
      }

      "reject requests whose batch contained a topology transaction" in {
        val sut = new Fixture()

        val mediatorRequest = InformeeMessage(fullInformeeTree)(testedProtocolVersion)
        val rootHashMessage = RootHashMessage(
          mediatorRequest.rootHash.value,
          domainId,
          testedProtocolVersion,
          mediatorRequest.viewType,
          SerializedRootHashMessagePayload.empty,
        )

        val sc = 10L
        val ts = CantonTimestamp.ofEpochSecond(sc)
        for {
          _ <- loggerFactory.assertLogs(
            sut.processor.processRequest(
              RequestId(ts),
              notSignificantCounter,
              ts.plusSeconds(60),
              ts.plusSeconds(120),
              mediatorRequest,
              List(
                OpenEnvelope(
                  rootHashMessage,
                  Recipients.cc(mediatorRef.toRecipient, MemberRecipient(participant)),
                )(
                  testedProtocolVersion
                )
              ),
              batchAlsoContainsTopologyXTransaction = true,
            ),
            _.shouldBeCantonError(
              MediatorError.MalformedMessage,
              message => {
                message should (include(
                  s"Received a mediator request with id ${RequestId(ts)} also containing a topology transaction."
                ))
              },
            ),
          )
        } yield succeed
      }

      "timeout request that is not pending should not fail" in {
        // could happen if a timeout is scheduled but the request is previously finalized
        val sut = new Fixture()
        val requestTs = CantonTimestamp.Epoch
        val requestId = RequestId(requestTs)
        val timeoutTs = requestTs.plusSeconds(20)

        // this request is not added to the pending state
        for {
          snapshot <- domainSyncCryptoApi2.snapshot(requestTs)
          _ <- sut.processor.handleTimeout(requestId, timeoutTs, decisionTime)
        } yield succeed
      }

      "reject request if some informee is not hosted by an active participant" in {
        val domainSyncCryptoApi =
          identityFactoryNoParticipants.forOwnerAndDomain(mediatorId, domainId)
        val sut = new Fixture(domainSyncCryptoApi)

        val request = InformeeMessage(fullInformeeTree)(testedProtocolVersion)

        for {
          _ <- loggerFactory.assertLogs(
            sut.processor.processRequest(
              requestId,
              notSignificantCounter,
              requestIdTs.plusSeconds(20),
              decisionTime,
              request,
              rootHashMessages,
              batchAlsoContainsTopologyXTransaction = false,
            ),
            _.shouldBeCantonError(
              MediatorError.InvalidMessage,
              _ shouldBe s"Received a mediator request with id $requestId with some informees not being hosted by an active participant: ${fullInformeeTree.allInformees}. Rejecting request...",
            ),
          )
        } yield succeed
      }
    }
  } else {
    "ConfirmationResponseProcessor" should {
      s"not run for PV=$testedProtocolVersion" ignore succeed
    }
  }
}

class ConfirmationResponseProcessorTestV5
    extends ConfirmationResponseProcessorTestV5Base(ProtocolVersion.v5) {

  override lazy val mediatorId: MediatorId = MediatorId(
    UniqueIdentifier.tryCreate("mediator", "one")
  )
  override lazy val mediatorRef: MediatorRef = MediatorRef(mediatorId)

  lazy val topology: TestingTopology = TestingTopology(
    Set(domainId),
    Map(
      submitter -> Map(participant -> ParticipantPermission.Confirmation),
      signatory ->
        Map(participant -> ParticipantPermission.Confirmation),
      observer ->
        Map(participant -> ParticipantPermission.Observation),
    ),
    Set(mediatorId),
  )
  override lazy val identityFactory: TestingIdentityFactoryBase = TestingIdentityFactory(
    topology,
    loggerFactory,
    dynamicDomainParameters =
      initialDomainParameters.tryUpdate(participantResponseTimeout = participantResponseTimeout),
  )

  override lazy val identityFactory2: TestingIdentityFactoryBase = {
    val topology2 = TestingTopology(
      Set(domainId),
      Map(
        submitter -> Map(participant1 -> ParticipantPermission.Confirmation),
        signatory -> Map(participant2 -> ParticipantPermission.Confirmation),
        observer -> Map(participant3 -> ParticipantPermission.Confirmation),
      ),
      Set(mediatorId),
    )
    TestingIdentityFactory(topology2, loggerFactory, initialDomainParameters)
  }

  lazy val identityFactory3: TestingIdentityFactoryBase = {
    val otherMediatorId = MediatorId(UniqueIdentifier.tryCreate("mediator", "other"))
    val topology3 = topology.copy(mediators = Set(otherMediatorId))
    TestingIdentityFactory(
      topology3,
      loggerFactory,
      dynamicDomainParameters = initialDomainParameters,
    )
  }

  override lazy val identityFactoryNoParticipants: TestingIdentityFactoryBase =
    TestingIdentityFactory(
      TestingTopology(Set(domainId), Map.empty, Set(mediatorId)),
      loggerFactory,
      dynamicDomainParameters = initialDomainParameters,
    )

  if (testedProtocolVersion >= ProtocolVersion.v5) {
    "inactive mediator ignores requests" in {
      val domainSyncCryptoApi3 = identityFactory3.forOwnerAndDomain(mediatorId, domainId)
      val sut = new Fixture(domainSyncCryptoApi3)

      val mediatorRequest = InformeeMessage(fullInformeeTree)(testedProtocolVersion)
      val rootHashMessage = RootHashMessage(
        mediatorRequest.rootHash.value,
        domainId,
        testedProtocolVersion,
        mediatorRequest.viewType,
        SerializedRootHashMessagePayload.empty,
      )

      val sc = SequencerCounter(100)
      val ts = CantonTimestamp.ofEpochSecond(sc.v)
      val requestId = RequestId(ts)
      for {
        _ <- sut.processor.processRequest(
          RequestId(ts),
          notSignificantCounter,
          ts.plusSeconds(60),
          ts.plusSeconds(120),
          mediatorRequest,
          List(
            OpenEnvelope(
              rootHashMessage,
              Recipients.cc(mediatorRef.toRecipient, MemberRecipient(participant)),
            )(
              testedProtocolVersion
            )
          ),
          batchAlsoContainsTopologyXTransaction = false,
        )
        _ = sut.verdictSender.sentResults shouldBe empty

        // If it nevertheless gets a response, it will complain about the request not being known
        response <- signedResponse(
          Set(submitter),
          view,
          view0Position,
          LocalApprove(testedProtocolVersion),
          requestId,
        )
        _ <- loggerFactory.assertLogs(
          sut.processor.processResponse(
            ts.immediateSuccessor,
            sc + 1L,
            ts.plusSeconds(60),
            ts.plusSeconds(120),
            response,
            Recipients.cc(mediatorRef.toRecipient),
          ), {
            _.shouldBeCantonError(
              MediatorError.InvalidMessage,
              _ shouldBe show"Received a mediator response at ${ts.immediateSuccessor} by $participant with an unknown request id $requestId. Discarding response...",
            )
          },
        )
      } yield {
        succeed
      }
    }
  }
}

class ConfirmationResponseProcessorTestV5X
    extends ConfirmationResponseProcessorTestV5Base(ProtocolVersion.CNTestNet) {
  override lazy val mediatorRef: MediatorRef = MediatorRef(mediatorGroup)
  override lazy val mediatorId: MediatorId = activeMediator2

  override lazy val factory: ExampleTransactionFactory =
    new ExampleTransactionFactory()(domainId = domainId, mediatorRef = mediatorRef)

  override lazy val identityFactory: TestingIdentityFactoryBase = {
    val topology = TestingTopologyX(
      Set(domainId),
      Map(
        submitter -> Map(participant -> ParticipantPermission.Confirmation),
        signatory ->
          Map(participant -> ParticipantPermission.Confirmation),
        observer ->
          Map(participant -> ParticipantPermission.Observation),
      ),
      Set(mediatorGroup),
    )
    TestingIdentityFactoryX(
      topology,
      loggerFactory,
      dynamicDomainParameters =
        initialDomainParameters.tryUpdate(participantResponseTimeout = participantResponseTimeout),
    )
  }

  override lazy val identityFactory2: TestingIdentityFactoryBase = {
    val topology2 = TestingTopologyX(
      Set(domainId),
      Map(
        submitter -> Map(participant1 -> ParticipantPermission.Confirmation),
        signatory -> Map(participant2 -> ParticipantPermission.Confirmation),
        observer -> Map(participant3 -> ParticipantPermission.Confirmation),
      ),
      Set(mediatorGroup),
    )
    TestingIdentityFactoryX(topology2, loggerFactory, initialDomainParameters)
  }

  override lazy val identityFactoryNoParticipants: TestingIdentityFactoryBase = {
    TestingIdentityFactoryX(
      TestingTopologyX(Set(domainId), Map.empty, Set(mediatorGroup)),
      loggerFactory,
      dynamicDomainParameters = initialDomainParameters,
    )
  }
}
