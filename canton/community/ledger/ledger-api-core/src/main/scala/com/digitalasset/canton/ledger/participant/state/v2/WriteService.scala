// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.ledger.participant.state.v2

import com.daml.lf.data.{ImmArray, Ref}
import com.daml.lf.transaction.{GlobalKey, SubmittedTransaction}
import com.daml.lf.value.Value
import com.digitalasset.canton.data.ProcessedDisclosedContract
import com.digitalasset.canton.ledger.api.health.ReportsHealth
import com.digitalasset.canton.tracing.TraceContext

import java.util.concurrent.CompletionStage

/** An interface to change a ledger via a participant.
  * '''Please note that this interface is unstable and may significantly change.'''
  *
  * The methods in this interface are all methods that are supported
  * *uniformly* across all ledger participant implementations. Methods for
  * uploading packages, on-boarding parties, and changing ledger-wide
  * configuration are specific to a ledger and therefore to a participant
  * implementation. Moreover, these methods usually require admin-level
  * privileges, whose granting is also specific to a ledger.
  *
  * If a ledger is run for testing only, there is the option for quite freely
  * allowing the on-boarding of parties and uploading of packages. There are
  * plans to make this functionality uniformly available: see the roadmap for
  * progress information https://github.com/digital-asset/daml/issues/121.
  *
  * The following methods are currently available for changing the state of a Daml ledger:
  * - submitting a transaction using [[WriteService!.submitTransaction]]
  * - allocating a new party using [[WritePartyService!.allocateParty]]
  * - uploading a new package using [[WritePackagesService!.uploadPackages]]
  * - pruning a participant ledger using [[WriteParticipantPruningService!.prune]]
  */
trait WriteService
    extends WritePackagesService
    with WritePartyService
    with WriteConfigService
    with WriteParticipantPruningService
    with ReportsHealth {

  /** Submit a transaction for acceptance to the ledger.
    *
    * This method must be thread-safe.
    *
    * The result of the transaction submission is communicated asynchronously
    * via a [[com.digitalasset.canton.ledger.participant.state.v2.ReadService]] implementation backed by the same participant
    * state as this [[com.digitalasset.canton.ledger.participant.state.v2.WriteService]]. Successful transaction acceptance is
    * communicated using a [[com.digitalasset.canton.ledger.participant.state.v2.Update.TransactionAccepted]] message. Failed
    * transaction acceptance is communicated when possible via a
    * [[com.digitalasset.canton.ledger.participant.state.v2.Update.CommandRejected]] message referencing the same `submitterInfo` as
    * provided in the submission. There can be failure modes where a
    * transaction submission is lost in transit, and no [[com.digitalasset.canton.ledger.participant.state.v2.Update.CommandRejected]] is
    * generated. See the comments on [[com.digitalasset.canton.ledger.participant.state.v2.ReadService.stateUpdates]] for further details.
    *
    * A note on ledger time and record time: transactions are
    * submitted together with a `ledgerTime` provided as part of the
    * `transactionMeta` information. The ledger time is used by the
    * Daml Engine to resolve calls to the `getTime :: Update Time`
    * function. Letting the submitter freely choose the ledger time
    * is though a problem for the other stakeholders in the contracts affected
    * by the submitted transaction. The submitter can in principle choose to
    * submit transactions that are effective far in the past or future
    * relative to the wall-clock time of the other participants. This gives
    * the submitter an unfair advantage and make the semantics of `getTime`
    * quite surprising. We've chosen the following solution to provide useful
    * guarantees for contracts relying on `getTime`.
    *
    * The ledger is charged with (1) associating record-time stamps to accepted
    * transactions and (2) to provide a guarantee on the maximal skew between the
    * ledger effective time and the record time stamp associated to an
    * accepted transaction. The ledger is also expected to provide guarantees
    * on the distribution of the maximal skew between record time stamps on
    * accepted transactions and the wall-clock time at delivery of accepted transactions to a ledger
    * participant. Thereby providing ledger participants with a guarantee on the
    * maximal skew between the ledger effective time of an accepted
    * transaction and the wall-clock time at delivery to these participants.
    *
    * Concretely, we typically expect the allowed skew between record time and
    * ledger time to be in the minute range. Thereby leaving ample
    * time for submitting and validating large transactions before they are
    * timestamped with their record time.
    *
    * The [[com.digitalasset.canton.ledger.participant.state.v2.WriteService]] is responsible for deduplicating commands
    * with the same [[com.digitalasset.canton.ledger.participant.state.v2.SubmitterInfo.changeId]] within the [[com.digitalasset.canton.ledger.participant.state.v2.SubmitterInfo.deduplicationPeriod]].
    *
    * @param submitterInfo               the information provided by the submitter for
    *                                    correlating this submission with its acceptance or rejection on the
    *                                    associated [[com.digitalasset.canton.ledger.participant.state.v2.ReadService]].
    * @param transactionMeta             the meta-data accessible to all consumers of the transaction.
    *                                    See [[com.digitalasset.canton.ledger.participant.state.v2.TransactionMeta]] for more information.
    * @param transaction                 the submitted transaction. This transaction can contain local
    *                                    contract-ids that need suffixing. The participant state may have to
    *                                    suffix those contract-ids in order to guaranteed their global
    *                                    uniqueness. See the Contract Id specification for more detail
    *                                    daml-lf/spec/contract-id.rst.
    * @param estimatedInterpretationCost Estimated cost of interpretation that may be used for
    *                                    handling submitted transactions differently.
    * @param globalKeyMapping            Input key mapping inferred by interpretation.
    *                                    The map should contain all contract keys that were used during interpretation.
    *                                    A value of None means no contract was found with this contract key.
    * @param processedDisclosedContracts      Explicitly disclosed contracts used during interpretation.
    */
  def submitTransaction(
      submitterInfo: SubmitterInfo,
      transactionMeta: TransactionMeta,
      transaction: SubmittedTransaction,
      estimatedInterpretationCost: Long,
      globalKeyMapping: Map[GlobalKey, Option[Value.ContractId]],
      processedDisclosedContracts: ImmArray[ProcessedDisclosedContract],
  )(implicit
      traceContext: TraceContext
  ): CompletionStage[SubmissionResult]

  /** Submit a reassignment command for acceptance to the ledger.
    *
    * To complete a reassignment, first a submission of an unassign command followed by an assign
    * command is required. The [[com.digitalasset.canton.ledger.participant.state.v2.ReassignmentCommand.Assign]] command must include the unassign ID which can be
    * observed in the accepted event marking the corresponding successful unassign command.
    *
    * @param submitter           The submitter of the reassignment.
    * @param applicationId       An identifier for the Daml application that
    *                            submitted the command. This is used for monitoring, command
    *                            deduplication, and to allow Daml applications subscribe to their own
    *                            submissions only.
    * @param commandId           A submitter-provided identifier to identify an intended ledger change
    *                            within all the submissions by the same parties and application.
    * @param submissionId        An identifier for the submission that allows an application to
    *                            correlate completions to its submissions.
    * @param workflowId          A submitter-provided identifier used for monitoring
    *                            and to traffic-shape the work handled by Daml applications
    *                            communicating over the ledger.
    * @param reassignmentCommand The command specifying this reassignment further.
    */
  def submitReassignment(
      submitter: Ref.Party,
      applicationId: Ref.ApplicationId,
      commandId: Ref.CommandId,
      submissionId: Option[Ref.SubmissionId],
      workflowId: Option[Ref.WorkflowId],
      reassignmentCommand: ReassignmentCommand,
  )(implicit
      traceContext: TraceContext
  ): CompletionStage[SubmissionResult]
}
