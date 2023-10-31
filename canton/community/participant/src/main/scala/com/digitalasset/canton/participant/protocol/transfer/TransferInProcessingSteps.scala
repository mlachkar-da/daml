// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.protocol.transfer

import cats.data.EitherT
import cats.syntax.bifunctor.*
import cats.syntax.either.*
import cats.syntax.functor.*
import cats.syntax.parallel.*
import com.daml.lf.data.Bytes
import com.daml.nonempty.{NonEmpty, NonEmptyUtil}
import com.digitalasset.canton.crypto.{DecryptionError as _, EncryptionError as _, *}
import com.digitalasset.canton.data.ViewType.TransferInViewType
import com.digitalasset.canton.data.*
import com.digitalasset.canton.ledger.participant.state.v2.CompletionInfo
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.RequestOffset
import com.digitalasset.canton.participant.protocol.ProcessingSteps.PendingRequestData
import com.digitalasset.canton.participant.protocol.conflictdetection.{
  ActivenessCheck,
  ActivenessResult,
  ActivenessSet,
  CommitSet,
}
import com.digitalasset.canton.participant.protocol.submission.{
  EncryptedViewMessageFactory,
  SeedGenerator,
}
import com.digitalasset.canton.participant.protocol.transfer.TransferInProcessingSteps.*
import com.digitalasset.canton.participant.protocol.transfer.TransferInValidation.*
import com.digitalasset.canton.participant.protocol.transfer.TransferProcessingSteps.*
import com.digitalasset.canton.participant.protocol.{
  CanSubmitTransfer,
  ProcessingSteps,
  ProtocolProcessor,
}
import com.digitalasset.canton.participant.store.ActiveContractStore.Archived
import com.digitalasset.canton.participant.store.*
import com.digitalasset.canton.participant.sync.{LedgerSyncEvent, TimestampedEvent}
import com.digitalasset.canton.participant.util.DAMLe
import com.digitalasset.canton.protocol.*
import com.digitalasset.canton.protocol.messages.*
import com.digitalasset.canton.sequencing.protocol.*
import com.digitalasset.canton.serialization.DefaultDeserializationError
import com.digitalasset.canton.store.SessionKeyStore
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.EitherTUtil.condUnitET
import com.digitalasset.canton.util.ErrorUtil
import com.digitalasset.canton.util.FutureInstances.*
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.version.Transfer.{SourceProtocolVersion, TargetProtocolVersion}
import com.digitalasset.canton.{
  LfPartyId,
  RequestCounter,
  SequencerCounter,
  TransferCounter,
  TransferCounterO,
  checked,
}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

private[transfer] class TransferInProcessingSteps(
    val domainId: TargetDomainId,
    val participantId: ParticipantId,
    val engine: DAMLe,
    transferCoordination: TransferCoordination,
    seedGenerator: SeedGenerator,
    targetProtocolVersion: TargetProtocolVersion,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit val ec: ExecutionContext)
    extends TransferProcessingSteps[
      SubmissionParam,
      SubmissionResult,
      TransferInViewType,
      TransferInResult,
      PendingTransferIn,
    ]
    with NamedLogging {

  import TransferInProcessingSteps.*

  override def requestKind: String = "TransferIn"

  override def submissionDescription(param: SubmissionParam): String =
    s"Submitter ${param.submitterMetadata.submitter}, transferId ${param.transferId}"

  override type SubmissionResultArgs = PendingTransferSubmission

  override type PendingDataAndResponseArgs = TransferInProcessingSteps.PendingDataAndResponseArgs

  override type RequestType = ProcessingSteps.RequestType.TransferIn
  override val requestType = ProcessingSteps.RequestType.TransferIn

  override def pendingSubmissions(state: SyncDomainEphemeralState): PendingSubmissions = {
    state.pendingTransferInSubmissions
  }

  private val transferInValidation = new TransferInValidation(
    domainId,
    participantId,
    engine,
    transferCoordination,
    targetProtocolVersion,
    loggerFactory,
  )

  override def submissionIdOfPendingRequest(pendingData: PendingTransferIn): RootHash =
    pendingData.rootHash

  override def prepareSubmission(
      param: SubmissionParam,
      mediator: MediatorRef,
      ephemeralState: SyncDomainEphemeralStateLookup,
      recentSnapshot: DomainSnapshotSyncCryptoApi,
  )(implicit
      traceContext: TraceContext
  ): EitherT[FutureUnlessShutdown, TransferProcessorError, Submission] = {

    val SubmissionParam(
      submitterMetadata,
      transferId,
      sourceProtocolVersion,
    ) = param
    val topologySnapshot = recentSnapshot.ipsSnapshot
    val pureCrypto = recentSnapshot.pureCrypto
    val submitter = submitterMetadata.submitter

    def activeParticipantsOfParty(
        party: LfPartyId
    ): EitherT[Future, TransferProcessorError, Set[ParticipantId]] =
      EitherT(topologySnapshot.activeParticipantsOf(party).map(_.keySet).map { participants =>
        Either.cond(
          participants.nonEmpty,
          participants,
          NoParticipantForReceivingParty(transferId, party),
        )
      })

    val result = for {
      transferData <- ephemeralState.transferLookup
        .lookup(transferId)
        .leftMap(err => NoTransferData(transferId, err))
      transferOutResult <- EitherT.fromEither[Future](
        transferData.transferOutResult.toRight(TransferOutIncomplete(transferId, participantId))
      )

      targetDomain = transferData.targetDomain
      _ = if (targetDomain != domainId)
        throw new IllegalStateException(
          s"Transfer-in $transferId: Transfer data for ${transferData.targetDomain} found on wrong domain $domainId"
        )

      stakeholders = transferData.transferOutRequest.stakeholders
      _ <- condUnitET[Future](
        stakeholders.contains(submitter),
        SubmittingPartyMustBeStakeholderIn(transferId, submitter, stakeholders),
      )

      _ <- CanSubmitTransfer.transferIn(transferId, topologySnapshot, submitter, participantId)

      transferInUuid = seedGenerator.generateUuid()
      seed = seedGenerator.generateSaltSeed()

      fullTree <- EitherT.fromEither[Future](
        makeFullTransferInTree(
          pureCrypto,
          seed,
          submitterMetadata,
          stakeholders,
          transferData.contract,
          transferData.transferCounter,
          transferData.creatingTransactionId,
          targetDomain,
          mediator,
          transferOutResult,
          transferInUuid,
          sourceProtocolVersion,
          targetProtocolVersion,
        )
      )

      rootHash = fullTree.rootHash
      mediatorMessage = fullTree.mediatorMessage
      recipientsSet <- {
        stakeholders.toSeq
          .parTraverse(activeParticipantsOfParty)
          .map(_.foldLeft(Set.empty[Member])(_ ++ _))
      }
      recipients <- EitherT.fromEither[Future](
        Recipients
          .ofSet(recipientsSet)
          .toRight(NoStakeholders.logAndCreate(transferData.contract.contractId, logger))
      )
      viewMessage <- EncryptedViewMessageFactory
        .create(TransferInViewType)(
          fullTree,
          recentSnapshot,
          ephemeralState.sessionKeyStoreLookup,
          targetProtocolVersion.v,
        )
        .leftMap[TransferProcessorError](EncryptionError(transferData.contract.contractId, _))
    } yield {
      val rootHashMessage =
        RootHashMessage(
          rootHash,
          domainId.unwrap,
          targetProtocolVersion.v,
          ViewType.TransferInViewType,
          EmptyRootHashMessagePayload,
        )
      // Each member gets a message sent to itself and to the mediator
      val rootHashRecipients =
        Recipients.recipientGroups(
          checked(
            NonEmptyUtil.fromUnsafe(
              recipientsSet.toSeq.map(participant =>
                NonEmpty(Set, mediator.toRecipient, MemberRecipient(participant))
              )
            )
          )
        )
      val messages = Seq[(ProtocolMessage, Recipients)](
        mediatorMessage -> Recipients.cc(mediator.toRecipient),
        viewMessage -> recipients,
        rootHashMessage -> rootHashRecipients,
      )
      TransferSubmission(Batch.of(targetProtocolVersion.v, messages: _*), rootHash)
    }

    result.mapK(FutureUnlessShutdown.outcomeK).widen[Submission]
  }

  override def updatePendingSubmissions(
      pendingSubmissionMap: PendingSubmissions,
      submissionParam: SubmissionParam,
      submissionId: PendingSubmissionId,
  ): EitherT[Future, TransferProcessorError, SubmissionResultArgs] = {
    performPendingSubmissionMapUpdate(
      pendingSubmissionMap,
      Some(submissionParam.transferId),
      submissionParam.submitterLf,
      submissionId,
    )
  }

  override def createSubmissionResult(
      deliver: Deliver[Envelope[_]],
      pendingSubmission: SubmissionResultArgs,
  ): SubmissionResult =
    SubmissionResult(pendingSubmission.transferCompletion.future)

  override protected def decryptTree(
      snapshot: DomainSnapshotSyncCryptoApi,
      sessionKeyStore: SessionKeyStore,
  )(
      envelope: OpenEnvelope[EncryptedViewMessage[TransferInViewType]]
  )(implicit
      tc: TraceContext
  ): EitherT[Future, EncryptedViewMessageError, WithRecipients[
    FullTransferInTree
  ]] =
    EncryptedViewMessage
      .decryptFor(
        snapshot,
        sessionKeyStore,
        envelope.protocolMessage,
        participantId,
        targetProtocolVersion.v,
      ) { bytes =>
        FullTransferInTree
          .fromByteString(snapshot.pureCrypto)(bytes)
          .leftMap(e => DefaultDeserializationError(e.toString))
      }
      .map(WithRecipients(_, envelope.recipients))

  override def computeActivenessSetAndPendingContracts(
      ts: CantonTimestamp,
      rc: RequestCounter,
      sc: SequencerCounter,
      fullViewsWithSignatures: NonEmpty[
        Seq[(WithRecipients[FullTransferInTree], Option[Signature])]
      ],
      malformedPayloads: Seq[ProtocolProcessor.MalformedPayload],
      snapshot: DomainSnapshotSyncCryptoApi,
      mediator: MediatorRef,
  )(implicit
      traceContext: TraceContext
  ): EitherT[Future, TransferProcessorError, CheckActivenessAndWritePendingContracts] = {
    val correctRootHashes = fullViewsWithSignatures.map { case (rootHashes, _) =>
      rootHashes.unwrap
    }
    // TODO(i12926): Send a rejection if malformedPayloads is non-empty
    for {
      txInRequest <- EitherT.cond[Future](
        correctRootHashes.toList.sizeCompare(1) == 0,
        correctRootHashes.head1,
        ReceivedMultipleRequests(correctRootHashes.map(_.viewHash)): TransferProcessorError,
      )
      contractId = txInRequest.contract.contractId

      _ <- condUnitET[Future](
        txInRequest.targetDomain == domainId,
        UnexpectedDomain(
          txInRequest.transferOutResultEvent.transferId,
          targetDomain = txInRequest.domainId,
          receivedOn = domainId.unwrap,
        ),
      ).leftWiden[TransferProcessorError]

      transferringParticipant = txInRequest.transferOutResultEvent.unwrap.informees
        .contains(participantId.adminParty.toLf)

      contractIdS = Set(contractId)
      contractCheck = ActivenessCheck.tryCreate(
        checkFresh = Set.empty,
        checkFree = contractIdS,
        checkActive = Set.empty,
        lock = contractIdS,
        needPriorState = Set.empty,
      )
      activenessSet = ActivenessSet(
        contracts = contractCheck,
        transferIds =
          if (transferringParticipant) Set(txInRequest.transferOutResultEvent.transferId)
          else Set.empty,
        // We check keys on only domains with unique contract key semantics and there cannot be transfers on such domains
        keys = ActivenessCheck.empty,
      )
    } yield CheckActivenessAndWritePendingContracts(
      activenessSet,
      PendingDataAndResponseArgs(
        txInRequest,
        ts,
        rc,
        sc,
        snapshot,
        transferringParticipant,
      ),
    )
  }

  override def constructPendingDataAndResponse(
      pendingDataAndResponseArgs: PendingDataAndResponseArgs,
      transferLookup: TransferLookup,
      contractLookup: ContractLookup,
      activenessResultFuture: FutureUnlessShutdown[ActivenessResult],
      pendingCursor: Future[Unit],
      mediator: MediatorRef,
      freshOwnTimelyTx: Boolean,
  )(implicit
      traceContext: TraceContext
  ): EitherT[
    FutureUnlessShutdown,
    TransferProcessorError,
    StorePendingDataAndSendResponseAndCreateTimeout,
  ] = {

    val PendingDataAndResponseArgs(
      txInRequest,
      ts,
      rc,
      sc,
      targetCrypto,
      transferringParticipant,
    ) = pendingDataAndResponseArgs

    val transferId = txInRequest.transferOutResultEvent.transferId

    for {
      _ <- transferInValidation.checkStakeholders(txInRequest).mapK(FutureUnlessShutdown.outcomeK)

      hostedStks <- EitherT.right[TransferProcessorError](
        FutureUnlessShutdown.outcomeF(
          hostedStakeholders(
            txInRequest.contract.metadata.stakeholders.toList,
            targetCrypto.ipsSnapshot,
          )
        )
      )

      transferDataO <- EitherT
        .right[TransferProcessorError](
          transferLookup.lookup(transferId).toOption.value
        )
        .mapK(FutureUnlessShutdown.outcomeK)
      validationResultO <- transferInValidation
        .validateTransferInRequest(
          ts,
          txInRequest,
          transferDataO,
          targetCrypto,
          transferringParticipant,
        )
        .mapK(FutureUnlessShutdown.outcomeK)

      activenessResult <- EitherT.right[TransferProcessorError](activenessResultFuture)
      requestId = RequestId(ts)

      // construct pending data and response
      entry = PendingTransferIn(
        requestId,
        rc,
        sc,
        txInRequest.tree.rootHash,
        txInRequest.contract,
        txInRequest.transferCounter,
        txInRequest.submitterMetadata,
        txInRequest.creatingTransactionId,
        transferringParticipant,
        transferId,
        hostedStks.toSet,
        mediator,
      )
      responses <- validationResultO match {
        case None =>
          EitherT.rightT[FutureUnlessShutdown, TransferProcessorError](Seq.empty)
        case Some(validationResult) =>
          val contractResult = activenessResult.contracts
          lazy val localVerdictProtocolVersion =
            LocalVerdict.protocolVersionRepresentativeFor(targetProtocolVersion.v)

          val localVerdict =
            if (activenessResult.isSuccessful)
              LocalApprove(targetProtocolVersion.v)
            else if (contractResult.notFree.nonEmpty) {
              contractResult.notFree.toSeq match {
                case Seq((coid, Archived)) =>
                  LocalReject.TransferInRejects.ContractAlreadyArchived.Reject(show"coid=$coid")(
                    localVerdictProtocolVersion
                  )
                case Seq((coid, _state)) =>
                  LocalReject.TransferInRejects.ContractAlreadyActive.Reject(show"coid=$coid")(
                    localVerdictProtocolVersion
                  )
                case coids =>
                  throw new RuntimeException(
                    s"Activeness result for a transfer-in fails for multiple contract IDs $coids"
                  )
              }
            } else if (contractResult.alreadyLocked.nonEmpty)
              LocalReject.TransferInRejects.ContractIsLocked.Reject("")(localVerdictProtocolVersion)
            else if (activenessResult.inactiveTransfers.nonEmpty)
              LocalReject.TransferInRejects.AlreadyCompleted.Reject("")(localVerdictProtocolVersion)
            else
              throw new RuntimeException(
                withRequestId(requestId, s"Unexpected activeness result $activenessResult")
              )

          EitherT
            .fromEither[FutureUnlessShutdown](
              MediatorResponse
                .create(
                  requestId,
                  participantId,
                  Some(txInRequest.viewHash),
                  Some(ViewPosition.root),
                  localVerdict,
                  txInRequest.toBeSigned,
                  validationResult.confirmingParties,
                  domainId.id,
                  targetProtocolVersion.v,
                )
            )
            .leftMap(e => FailedToCreateResponse(transferId, e): TransferProcessorError)
            .map(transferResponse => Seq(transferResponse -> Recipients.cc(mediator.toRecipient)))
      }
    } yield {
      StorePendingDataAndSendResponseAndCreateTimeout(
        entry,
        responses,
        RejectionArgs(
          entry,
          LocalReject.TimeRejects.LocalTimeout.Reject(targetProtocolVersion.v),
        ),
      )
    }
  }

  private[this] def withRequestId(requestId: RequestId, message: String) =
    s"Transfer-in $requestId: $message"

  override def getCommitSetAndContractsToBeStoredAndEvent(
      messageE: Either[
        EventWithErrors[Deliver[DefaultOpenEnvelope]],
        SignedContent[Deliver[DefaultOpenEnvelope]],
      ],
      resultE: Either[MalformedMediatorRequestResult, TransferInResult],
      pendingRequestData: PendingTransferIn,
      pendingSubmissionMap: PendingSubmissions,
      hashOps: HashOps,
  )(implicit
      traceContext: TraceContext
  ): EitherT[Future, TransferProcessorError, CommitAndStoreContractsAndPublishEvent] = {
    val PendingTransferIn(
      requestId,
      requestCounter,
      requestSequencerCounter,
      rootHash,
      contract,
      transferCounter,
      submitterMetadata,
      creatingTransactionId,
      transferringParticipant,
      transferId,
      hostedStakeholders,
      _,
    ) = pendingRequestData

    import scala.util.Either.MergeableEither
    MergeableEither[MediatorResult](resultE).merge.verdict match {
      case _: Verdict.Approve =>
        val commitSet = CommitSet(
          archivals = Map.empty,
          creations = Map.empty,
          transferOuts = Map.empty,
          transferIns = Map(
            contract.contractId -> WithContractHash
              .fromContract(
                contract,
                CommitSet.TransferInCommit(
                  transferId,
                  contract.metadata,
                  transferCounter,
                ),
              )
          ),
          keyUpdates = Map.empty,
        )
        val commitSetO = Some(Future.successful(commitSet))
        val contractsToBeStored = Seq(WithTransactionId(contract, creatingTransactionId))

        for {
          event <- createTransferredIn(
            contract,
            creatingTransactionId,
            requestId.unwrap,
            submitterMetadata,
            transferId,
            rootHash,
            isTransferringParticipant = transferringParticipant,
            transferCounter,
            hostedStakeholders.toList,
          )
          timestampEvent = Some(
            TimestampedEvent(
              event,
              RequestOffset(requestId.unwrap, requestCounter),
              Some(requestSequencerCounter),
            )
          )
        } yield CommitAndStoreContractsAndPublishEvent(
          commitSetO,
          contractsToBeStored,
          timestampEvent,
        )

      case reasons: Verdict.ParticipantReject =>
        EitherT
          .fromEither[Future](
            createRejectionEvent(RejectionArgs(pendingRequestData, reasons.keyEvent))
          )
          .map(CommitAndStoreContractsAndPublishEvent(None, Seq.empty, _))

      case _: Verdict.MediatorReject =>
        EitherT.pure(CommitAndStoreContractsAndPublishEvent(None, Seq.empty, None))
    }
  }

  private[transfer] def createTransferredIn(
      contract: SerializableContract,
      creatingTransactionId: TransactionId,
      recordTime: CantonTimestamp,
      submitterMetadata: TransferSubmitterMetadata,
      transferId: TransferId,
      rootHash: RootHash,
      isTransferringParticipant: Boolean,
      transferCounter: TransferCounterO,
      hostedStakeholders: List[LfPartyId],
  ): EitherT[Future, TransferProcessorError, LedgerSyncEvent.TransferredIn] = {
    val targetDomain = domainId
    val contractInst = contract.contractInstance.unversioned
    val createNode: LfNodeCreate =
      LfNodeCreate(
        contract.contractId,
        contractInst.template,
        contractInst.arg,
        "", // TODO(i12451): get the right agreement text from `contractInst`
        contract.metadata.signatories,
        contract.metadata.stakeholders,
        keyOpt = contract.metadata.maybeKeyWithMaintainers,
        contract.contractInstance.version,
      )
    val driverContractMetadata = contract.contractSalt
      .map { salt =>
        DriverContractMetadata(salt).toLfBytes(targetProtocolVersion.v)
      }
      .getOrElse(Bytes.Empty)

    for {
      updateId <- EitherT.fromEither[Future](
        rootHash.asLedgerTransactionId.leftMap[TransferProcessorError](
          FieldConversionError(transferId, "Transaction id (root hash)", _)
        )
      )

      ledgerCreatingTransactionId <- EitherT.fromEither[Future](
        creatingTransactionId.asLedgerTransactionId.leftMap[TransferProcessorError](
          FieldConversionError(transferId, "Transaction id (creating transaction)", _)
        )
      )

      completionInfo =
        Option.when(participantId.toLf == submitterMetadata.submittingParticipant)(
          CompletionInfo(
            actAs = List(submitterMetadata.submitter),
            applicationId = submitterMetadata.applicationId,
            commandId = submitterMetadata.commandId,
            optDeduplicationPeriod = None,
            submissionId = submitterMetadata.submissionId,
            statistics = None,
          )
        )
    } yield LedgerSyncEvent.TransferredIn(
      updateId = updateId,
      optCompletionInfo = completionInfo,
      submitter = Option(submitterMetadata.submitter),
      recordTime = recordTime.toLf,
      ledgerCreateTime = contract.ledgerCreateTime.toLf,
      createNode = createNode,
      creatingTransactionId = ledgerCreatingTransactionId,
      contractMetadata = driverContractMetadata,
      transferId = transferId,
      targetDomain = targetDomain,
      createTransactionAccepted = !isTransferringParticipant,
      workflowId = submitterMetadata.workflowId,
      isTransferringParticipant = isTransferringParticipant,
      hostedStakeholders = hostedStakeholders,
      transferCounter = transferCounter.getOrElse(
        // Default value for protocol version earlier than dev
        // TODO(#12373) Adapt when releasing BFT
        TransferCounter.MinValue
      ),
    )
  }
}

object TransferInProcessingSteps {

  final case class SubmissionParam(
      submitterMetadata: TransferSubmitterMetadata,
      transferId: TransferId,
      sourceProtocolVersion: SourceProtocolVersion,
  ) {
    val submitterLf: LfPartyId = submitterMetadata.submitter
  }

  final case class SubmissionResult(transferInCompletionF: Future[com.google.rpc.status.Status])

  final case class PendingTransferIn(
      override val requestId: RequestId,
      override val requestCounter: RequestCounter,
      override val requestSequencerCounter: SequencerCounter,
      rootHash: RootHash,
      contract: SerializableContract,
      transferCounter: TransferCounterO,
      submitterMetadata: TransferSubmitterMetadata,
      creatingTransactionId: TransactionId,
      isTransferringParticipant: Boolean,
      transferId: TransferId,
      hostedStakeholders: Set[LfPartyId],
      mediator: MediatorRef,
  ) extends PendingTransfer
      with PendingRequestData

  private[transfer] def makeFullTransferInTree(
      pureCrypto: CryptoPureApi,
      seed: SaltSeed,
      submitterMetadata: TransferSubmitterMetadata,
      stakeholders: Set[LfPartyId],
      contract: SerializableContract,
      transferCounter: TransferCounterO,
      creatingTransactionId: TransactionId,
      targetDomain: TargetDomainId,
      targetMediator: MediatorRef,
      transferOutResult: DeliveredTransferOutResult,
      transferInUuid: UUID,
      sourceProtocolVersion: SourceProtocolVersion,
      targetProtocolVersion: TargetProtocolVersion,
  )(implicit
      loggingContext: ErrorLoggingContext
  ): Either[TransferProcessorError, FullTransferInTree] = {
    val commonDataSalt = Salt.tryDeriveSalt(seed, 0, pureCrypto)
    val viewSalt = Salt.tryDeriveSalt(seed, 1, pureCrypto)

    for {
      _ <- checkIncompatiblePV(sourceProtocolVersion, targetProtocolVersion, contract.contractId)

      // If transfer is initiated from a domain where the transfers counters are not yet defined, we set it to 0
      // And if we transfer to a domain that does not support transfer counters and the source domain also does not
      // support them, we omit it
      // Otherwise, due to the PV compatibility check above, both domains must support transfer counters and we
      // keep the transfer counter unchanged
      revisedTransferCounter = {
        if (
          sourceProtocolVersion.v < TransferCommonData.minimumPvForTransferCounter &&
          targetProtocolVersion.v >= TransferCommonData.minimumPvForTransferCounter
        )
          Some(TransferCounter.Genesis)
        else if (
          targetProtocolVersion.v < TransferCommonData.minimumPvForTransferCounter && sourceProtocolVersion.v < TransferCommonData.minimumPvForTransferCounter
        ) None
        else if (
          targetProtocolVersion.v >= TransferCommonData.minimumPvForTransferCounter && sourceProtocolVersion.v >= TransferCommonData.minimumPvForTransferCounter
        ) transferCounter
        else
          ErrorUtil.invalidState(
            s"The source domain PV ${sourceProtocolVersion.v} and target domains PV ${targetProtocolVersion.v} are incompatible"
          )
      }

      commonData <- TransferInCommonData
        .create(pureCrypto)(
          commonDataSalt,
          targetDomain,
          targetMediator,
          stakeholders,
          transferInUuid,
          targetProtocolVersion,
        )
        .leftMap(reason => InvalidTransferCommonData(reason))
      view <- TransferInView
        .create(pureCrypto)(
          viewSalt,
          submitterMetadata,
          contract,
          creatingTransactionId,
          transferOutResult,
          sourceProtocolVersion,
          targetProtocolVersion,
          revisedTransferCounter,
        )
        .leftMap(reason => InvalidTransferView(reason))
      tree = TransferInViewTree(commonData, view)(pureCrypto)
    } yield FullTransferInTree(tree)
  }

  final case class PendingDataAndResponseArgs(
      txInRequest: FullTransferInTree,
      ts: CantonTimestamp,
      rc: RequestCounter,
      sc: SequencerCounter,
      targetCrypto: DomainSnapshotSyncCryptoApi,
      transferringParticipant: Boolean,
  )
}