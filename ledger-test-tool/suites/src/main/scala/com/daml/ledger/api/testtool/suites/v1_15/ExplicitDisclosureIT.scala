// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.api.testtool.suites.v1_15

import com.daml.error.definitions.LedgerApiErrors
import com.daml.ledger.api.refinements.ApiTypes.Party
import com.daml.ledger.api.testtool.infrastructure.Allocation._
import com.daml.ledger.api.testtool.infrastructure.Assertions._
import com.daml.ledger.api.testtool.infrastructure.LedgerTestSuite
import com.daml.ledger.api.testtool.infrastructure.Synchronize.synchronize
import com.daml.ledger.api.testtool.infrastructure.TransactionHelpers.createdEvents
import com.daml.ledger.api.testtool.infrastructure.participant.ParticipantTestContext
import com.daml.ledger.api.v1.command_service.SubmitAndWaitRequest
import com.daml.ledger.api.v1.commands.DisclosedContract.Arguments
import com.daml.ledger.api.v1.commands.{
  Command,
  CreateCommand,
  DisclosedContract,
  ExerciseByKeyCommand,
}
import com.daml.ledger.api.v1.contract_metadata.ContractMetadata
import com.daml.ledger.api.v1.event.CreatedEvent
import com.daml.ledger.api.v1.transaction_filter.{
  Filters,
  InclusiveFilters,
  TemplateFilter,
  TransactionFilter,
}
import com.daml.ledger.api.v1.value.{Identifier, Record, RecordField, Value}
import com.daml.ledger.client.binding
import com.daml.ledger.client.binding.Primitive
import com.daml.ledger.test.model.Test._
import com.daml.lf.transaction.TransactionCoder
import com.google.protobuf.ByteString
import scalaz.syntax.tag._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

final class ExplicitDisclosureIT extends LedgerTestSuite {
  import ExplicitDisclosureIT._

  test(
    "EDCorrectEventPayloadDisclosure",
    "Submission is successful if the correct disclosure as create_event_payload is provided",
    allocate(SingleParty, SingleParty),
    enabled = _.explicitDisclosure,
  )(implicit ec => {
    case Participants(
          Participant(ownerParticipant, owner),
          Participant(delegateParticipant, delegate),
        ) =>
      for {
        testContext <- initializeTest(
          ownerParticipant = ownerParticipant,
          delegateParticipant = delegateParticipant,
          owner = owner,
          delegate = delegate,
          transactionFilter = filterByPartyAndTemplate(owner),
        )

        // Ensure participants are synchronized
        _ <- synchronize(ownerParticipant, delegateParticipant)

        // Exercise a choice on the Delegation that fetches the Delegated contract
        // Fails because the submitter doesn't see the contract being fetched
        exerciseFetchError <- testContext
          .exerciseFetchDelegated()
          .mustFail("the submitter does not see the contract")

        // Exercise the same choice, this time using correct explicit disclosure
        _ <- testContext.exerciseFetchDelegated(testContext.disclosedContract)
      } yield {
        assertEquals(!testContext.disclosedContract.createdEventBlob.isEmpty, true)

        assertGrpcError(
          exerciseFetchError,
          LedgerApiErrors.ConsistencyErrors.ContractNotFound,
          None,
          checkDefiniteAnswerMetadata = true,
        )
      }
  })

  test(
    "EDSuperfluousDisclosure",
    "Submission is successful when unnecessary disclosed contract is provided",
    allocate(SingleParty, SingleParty),
    enabled = _.explicitDisclosure,
  )(testCase = implicit ec => {
    case Participants(
          Participant(ownerParticipant, owner),
          Participant(delegateParticipant, delegate),
        ) =>
      for {
        testContext <- initializeTest(
          ownerParticipant = ownerParticipant,
          delegateParticipant = delegateParticipant,
          owner = owner,
          delegate = delegate,
          transactionFilter = filterByPartyAndTemplate(owner),
        )

        dummyCid <- ownerParticipant.create(owner, Dummy(owner))
        dummyTxs <- ownerParticipant.flatTransactions(
          ownerParticipant.getTransactionsRequest(filterByPartyAndTemplate(owner, Dummy.id.unwrap))
        )
        dummyCreate = createdEvents(dummyTxs(0)).head
        dummyDisclosedContract = createEventToDisclosedContract(dummyCreate)

        // Ensure participants are synchronized
        _ <- synchronize(ownerParticipant, delegateParticipant)

        // Exercise works with provided disclosed contract
        _ <- testContext.exerciseFetchDelegated(testContext.disclosedContract)
        // Exercise works with the Dummy contract as a superfluous disclosed contract
        _ <- testContext.exerciseFetchDelegated(
          testContext.disclosedContract,
          dummyDisclosedContract,
        )

        // Archive the Dummy contract
        _ <- ownerParticipant.exercise(owner, dummyCid.exerciseArchive())

        // Ensure participants are synchronized
        _ <- synchronize(ownerParticipant, delegateParticipant)

        // Exercise works with the archived superfluous disclosed contract
        _ <- testContext.exerciseFetchDelegated(
          testContext.disclosedContract,
          dummyDisclosedContract,
        )
      } yield ()
  })

  test(
    "EDExerciseByKeyDisclosedContract",
    "A disclosed contract can be exercised by key with non-witness readers if authorized",
    partyAllocation = allocate(SingleParty, SingleParty),
    enabled = _.explicitDisclosure,
  ) { implicit ec =>
    {
      case Participants(
            Participant(ownerParticipant, owner),
            Participant(divulgeeParticipant, divulgee),
          ) =>
        for {
          // Create contract with `owner` as only stakeholder
          _ <- ownerParticipant.submitAndWait(
            ownerParticipant.submitAndWaitRequest(owner, WithKey(owner).create.command)
          )
          txs <- ownerParticipant.flatTransactions(
            ownerParticipant.getTransactionsRequest(
              filterByPartyAndTemplate(owner, WithKey.id.unwrap)
            )
          )
          withKeyCreationTx = assertSingleton("Transaction expected non-empty", txs)
          withKeyCreate = createdEvents(withKeyCreationTx).head
          withKeyDisclosedContract = createEventToDisclosedContract(withKeyCreate)

          // Ensure participants are synchronized
          _ <- synchronize(ownerParticipant, divulgeeParticipant)

          exerciseByKeyError <- divulgeeParticipant
            .submitAndWait(
              exerciseWithKey_byKey_request(divulgeeParticipant, owner, divulgee, None)
            )
            .mustFail("divulgee does not see the contract")
          // Assert that a random party can exercise the contract by key (if authorized)
          // when passing the disclosed contract to the submission
          _ <- divulgeeParticipant.submitAndWait(
            exerciseWithKey_byKey_request(
              divulgeeParticipant,
              owner,
              divulgee,
              Some(withKeyDisclosedContract),
            )
          )
        } yield assertGrpcError(
          exerciseByKeyError,
          LedgerApiErrors.CommandExecution.Interpreter.LookupErrors.ContractKeyNotFound,
          None,
          checkDefiniteAnswerMetadata = true,
        )
    }
  }

  test(
    "EDArchivedDisclosedContracts",
    "The ledger rejects archived disclosed contracts",
    allocate(SingleParty, SingleParty),
    enabled = _.explicitDisclosure,
  )(implicit ec => {
    case Participants(
          Participant(ownerParticipant, owner),
          Participant(delegateParticipant, delegate),
        ) =>
      for {
        testContext <- initializeTest(
          ownerParticipant = ownerParticipant,
          delegateParticipant = delegateParticipant,
          owner = owner,
          delegate = delegate,
          transactionFilter = filterByPartyAndTemplate(owner),
        )

        // Archive the disclosed contract
        _ <- ownerParticipant.exercise(owner, testContext.delegatedCid.exerciseArchive())

        // Ensure participants are synchronized
        _ <- synchronize(ownerParticipant, delegateParticipant)

        // Exercise the choice using the now inactive disclosed contract
        _ <- testContext
          .exerciseFetchDelegated(testContext.disclosedContract)
          .mustFail("the contract is already archived")
      } yield {
        // TODO ED: Assert specific error codes once Canton error codes can be accessible from these suites
      }
  })

  test(
    "EDDisclosedContractsArchiveRaceTest",
    "Only one archival succeeds in a race between a normal exercise and one with disclosed contracts",
    allocate(SingleParty, SingleParty),
    enabled = _.explicitDisclosure,
    repeated = 3,
  )(implicit ec => {
    case Participants(Participant(ledger1, party1), Participant(ledger2, party2)) =>
      val attempts = 10

      Future
        .traverse((1 to attempts).toList) {
          _ =>
            for {
              contractId <- ledger1.create(party1, Dummy(party1))

              transactions <- ledger1.flatTransactionsByTemplateId(Dummy.id, party1)
              create = createdEvents(transactions(0)).head
              disclosedContract = createEventToDisclosedContract(create)

              // Submit concurrently two consuming exercise choices (with and without disclosed contract)
              party1_exerciseF = ledger1.exercise(party1, contractId.exerciseArchive())
              // Ensure participants are synchronized
              _ <- synchronize(ledger1, ledger2)
              party2_exerciseWithDisclosureF =
                ledger2.submitAndWait(
                  ledger2
                    .submitAndWaitRequest(party2, contractId.exercisePublicChoice(party2).command)
                    .update(_.commands.disclosedContracts := scala.Seq(disclosedContract))
                )

              // Wait for both commands to finish
              party1_exercise_result <- party1_exerciseF.transform(Success(_))
              party2_exerciseWithDisclosure <- party2_exerciseWithDisclosureF.transform(Success(_))
            } yield {
              oneFailedWith(
                party1_exercise_result,
                party2_exerciseWithDisclosure,
              ) { _ =>
                // TODO ED: Assert specific error codes once Canton error codes can be accessible from these suites
                ()
              }
            }
        }
        .map(_ => ())
  })

  test(
    "EDMalformedDisclosedContractPayload",
    "The ledger rejects disclosed contracts with a malformed disclosed contract payload",
    allocate(SingleParty, SingleParty),
    enabled = _.explicitDisclosure,
  )(implicit ec => {
    case Participants(
          Participant(ownerParticipant, owner),
          Participant(delegateParticipant, delegate),
        ) =>
      for {
        testContext <- initializeTest(
          ownerParticipant = ownerParticipant,
          delegateParticipant = delegateParticipant,
          owner = owner,
          delegate = delegate,
          transactionFilter = filterByPartyAndTemplate(owner),
        )

        // Ensure participants are synchronized
        _ <- synchronize(ownerParticipant, delegateParticipant)

        // Exercise a choice using invalid explicit disclosure
        failure <- testContext
          .exerciseFetchDelegated(
            testContext.disclosedContract
              .update(_.createdEventBlob.set(ByteString.copyFromUtf8("foo")))
          )
          .mustFail("using a malformed disclosed contract create event payload")

      } yield {
        assertGrpcError(
          failure,
          LedgerApiErrors.RequestValidation.InvalidArgument,
          Some(
            "The submitted command has invalid arguments: Unable to decode disclosed contract event payload: DecodeError"
          ),
          checkDefiniteAnswerMetadata = true,
        )
      }
  })

  test(
    "EDInconsistentDisclosedContract",
    "The ledger rejects inconsistent contract payloads",
    allocate(SingleParty, SingleParty),
    enabled = _.explicitDisclosure,
  )(implicit ec => {
    case Participants(
          Participant(ownerParticipant, owner),
          Participant(delegateParticipant, delegate),
        ) =>
      for {
        ownerContext <- initializeTest(
          ownerParticipant = ownerParticipant,
          delegateParticipant = delegateParticipant,
          owner = owner,
          delegate = delegate,
          transactionFilter = filterByPartyAndTemplate(owner),
        )

        // Create a new context only for the sake of getting a new disclosed contract
        // with the same template id
        delegateContext <- initializeTest(
          ownerParticipant = delegateParticipant,
          delegateParticipant = delegateParticipant,
          owner = delegate,
          delegate = delegate,
          transactionFilter = filterByPartyAndTemplate(delegate),
        )

        // Ensure participants are synchronized
        _ <- synchronize(ownerParticipant, delegateParticipant)

        otherSalt = TransactionCoder
          .decodeFatContractInstance(delegateContext.disclosedContract.createdEventBlob)
          .map(_.cantonData)
          .getOrElse(fail("contract decode failed"))

        tamperedPayload = TransactionCoder
          .encodeFatContractInstance(
            TransactionCoder
              .decodeFatContractInstance(ownerContext.disclosedContract.createdEventBlob)
              .map(_.setSalt(otherSalt))
              .getOrElse(fail("contract decode failed"))
          )
          .getOrElse(fail("contract encode failed"))

        _ <- ownerContext
          // Use of inconsistent disclosed contract
          // i.e. the delegate cannot fetch the owner's contract with attaching a different disclosed contract
          .exerciseFetchDelegated(
            ownerContext.disclosedContract.copy(createdEventBlob = tamperedPayload)
          )
          .mustFail("using an inconsistent disclosed contract create event payload")
      } yield {
        // TODO ED: Assert specific error codes once Canton error codes can be accessible from these suites
        //          Should be DISCLOSED_CONTRACT_AUTHENTICATION_FAILED
      }
  })

  test(
    "EDDuplicates",
    "Submission is rejected on duplicate contract ids or key hashes",
    allocate(SingleParty, SingleParty),
    enabled = _.explicitDisclosure,
  )(implicit ec => {
    case Participants(
          Participant(ownerParticipant, owner),
          Participant(delegateParticipant, delegate),
        ) =>
      for {
        testContext <- initializeTest(
          ownerParticipant = ownerParticipant,
          delegateParticipant = delegateParticipant,
          owner = owner,
          delegate = delegate,
          transactionFilter = filterByPartyAndTemplate(owner),
        )

        // Ensure participants are synchronized
        _ <- synchronize(ownerParticipant, delegateParticipant)

        // Exercise a choice with a disclosed contract
        _ <- testContext.exerciseFetchDelegated(testContext.disclosedContract)

        // Submission with disclosed contracts with the same contract id should be rejected
        errorDuplicateContractId <- testContext
          .dummyCreate(testContext.disclosedContract, testContext.disclosedContract)
          .mustFail("duplicate contract id")
      } yield {
        assertGrpcError(
          errorDuplicateContractId,
          LedgerApiErrors.CommandExecution.Preprocessing.PreprocessingFailed,
          Some(
            s"Duplicate disclosed contract ID ${testContext.disclosedContract.contractId}"
          ),
          checkDefiniteAnswerMetadata = true,
        )
      }
  })

  // TODO ED: Remove this test once feature is deemed stable and not configurable in Canton
  test(
    "EDFeatureDisabled",
    "Submission fails when disclosed contracts provided on feature disabled",
    allocate(SingleParty, SingleParty),
    enabled = feature => !feature.explicitDisclosure,
  )(implicit ec => {
    case Participants(
          Participant(ownerParticipant, owner),
          Participant(delegateParticipant, delegate),
        ) =>
      for {
        testContext <- initializeTest(
          ownerParticipant = ownerParticipant,
          delegateParticipant = delegateParticipant,
          owner = owner,
          delegate = delegate,
          transactionFilter = filterByPartyAndTemplate(owner),
        )

        // Ensure participants are synchronized
        _ <- synchronize(ownerParticipant, delegateParticipant)

        exerciseFetchError <- testContext
          .exerciseFetchDelegated(testContext.disclosedContract)
          .mustFail("explicit disclosure feature is disabled")
      } yield {
        assertGrpcError(
          exerciseFetchError,
          LedgerApiErrors.RequestValidation.InvalidField,
          Some(
            "Invalid field disclosed_contracts: feature in development: disclosed_contracts should not be set"
          ),
          checkDefiniteAnswerMetadata = true,
        )
      }
  })

  test(
    "EDRejectOnPayloadAndBlobSet",
    "Submission is rejected when both the deprecated blob and create_event_payload are set",
    allocate(SingleParty, SingleParty),
    enabled = _.explicitDisclosure,
  )(implicit ec => {
    case Participants(
          Participant(ownerParticipant, owner),
          Participant(delegateParticipant, delegate),
        ) =>
      for {
        testContext <- initializeTest(
          ownerParticipant = ownerParticipant,
          delegateParticipant = delegateParticipant,
          owner = owner,
          delegate = delegate,
          transactionFilter = filterByPartyAndTemplate(owner),
        )

        // Ensure participants are synchronized
        _ <- synchronize(ownerParticipant, delegateParticipant)

        failure <- testContext
          .exerciseFetchDelegated(
            testContext.disclosedContract.copy(metadata = Some(ContractMetadata()))
          )
          .mustFail("Submitter forwarded a contract with invalid driver metadata")
      } yield {
        assertGrpcError(
          failure,
          LedgerApiErrors.RequestValidation.InvalidArgument,
          Some(
            "The submitted command has invalid arguments: DisclosedContract.arguments or DisclosedContract.metadata cannot be set together with DisclosedContract.create_event_payload"
          ),
          checkDefiniteAnswerMetadata = true,
        )
      }
  })

  test(
    "EDRejectOnPayloadNotSet",
    "Submission is rejected when the disclosed contract payload is not set",
    allocate(SingleParty, SingleParty),
    enabled = _.explicitDisclosure,
  )(implicit ec => {
    case Participants(
          Participant(ownerParticipant, owner),
          Participant(delegateParticipant, delegate),
        ) =>
      for {
        testContext <- initializeTest(
          ownerParticipant = ownerParticipant,
          delegateParticipant = delegateParticipant,
          owner = owner,
          delegate = delegate,
          transactionFilter = filterByPartyAndTemplate(owner),
        )

        // Ensure participants are synchronized
        _ <- synchronize(ownerParticipant, delegateParticipant)

        failure <- testContext
          .exerciseFetchDelegated(
            testContext.disclosedContract.copy(
              metadata = None,
              arguments = Arguments.Empty,
              createdEventBlob = ByteString.EMPTY,
            )
          )
          .mustFail("Submitter forwarded a contract with unpopulated create_event_payload")
      } yield {
        assertGrpcError(
          failure,
          LedgerApiErrors.RequestValidation.MissingField,
          Some(
            // TODO ED: Change assertion to missing create_event_payload once the deprecated fields are removed
            "The submitted command is missing a mandatory field: DisclosedContract.arguments"
          ),
          checkDefiniteAnswerMetadata = true,
        )
      }
  })

  private def oneFailedWith(result1: Try[_], result2: Try[_])(
      assertError: Throwable => Unit
  ): Unit =
    (result1.isFailure, result2.isFailure) match {
      case (true, false) => assertError(result1.failed.get)
      case (false, true) => assertError(result2.failed.get)
      case (true, true) => fail("Exactly one request should have failed, but both failed")
      case (false, false) => fail("Exactly one request should have failed, but both succeeded")
    }
}

object ExplicitDisclosureIT {
  case class TestContext(
      ownerParticipant: ParticipantTestContext,
      delegateParticipant: ParticipantTestContext,
      owner: binding.Primitive.Party,
      delegate: binding.Primitive.Party,
      contractKey: String,
      delegationCid: binding.Primitive.ContractId[Delegation],
      delegatedCid: binding.Primitive.ContractId[Delegated],
      originalCreateEvent: CreatedEvent,
      disclosedContract: DisclosedContract,
  ) {

    /** Exercises the FetchDelegated choice as the delegate party, with the given explicit disclosure contracts.
      * This choice fetches the Delegation contract which is only visible to the owner.
      */
    def exerciseFetchDelegated(disclosedContracts: DisclosedContract*): Future[Unit] = {
      val request = delegateParticipant
        .submitAndWaitRequest(
          delegate,
          delegationCid.exerciseFetchDelegated(delegatedCid).command,
        )
        .update(_.commands.disclosedContracts := disclosedContracts)
      delegateParticipant.submitAndWait(request)
    }

    def dummyCreate(disclosedContracts: DisclosedContract*): Future[Unit] = {
      val request = delegateParticipant
        .submitAndWaitRequest(
          delegate,
          Command.of(
            Command.Command.Create(
              CreateCommand(
                Some(Dummy.id.unwrap),
                Some(Dummy(delegate).arguments),
              )
            )
          ),
        )
        .update(_.commands.disclosedContracts := disclosedContracts)
      delegateParticipant.submitAndWait(request)
    }

  }

  private def initializeTest(
      ownerParticipant: ParticipantTestContext,
      delegateParticipant: ParticipantTestContext,
      owner: binding.Primitive.Party,
      delegate: binding.Primitive.Party,
      transactionFilter: TransactionFilter,
  )(implicit ec: ExecutionContext): Future[TestContext] = {
    val contractKey = ownerParticipant.nextKeyId()

    for {
      // Create a Delegation contract
      // Contract is visible both to owner (as signatory) and delegate (as observer)
      delegationCid <- ownerParticipant.create(owner, Delegation(owner, delegate))

      // Create Delegated contract
      // This contract is only visible to the owner
      delegatedCid <- ownerParticipant.create(owner, Delegated(owner, contractKey))

      // Get the contract payload from the transaction stream of the owner
      delegatedTx <- ownerParticipant.flatTransactions(
        ownerParticipant.getTransactionsRequest(transactionFilter)
      )
      createDelegatedEvent = createdEvents(delegatedTx.head).head

      // Copy the actual Delegated contract to a disclosed contract (which can be shared out of band).
      disclosedContract = createEventToDisclosedContract(createDelegatedEvent)
    } yield TestContext(
      ownerParticipant = ownerParticipant,
      delegateParticipant = delegateParticipant,
      owner = owner,
      delegate = delegate,
      contractKey = contractKey,
      delegationCid = delegationCid,
      delegatedCid = delegatedCid,
      originalCreateEvent = createDelegatedEvent,
      disclosedContract = disclosedContract,
    )
  }

  private def filterByPartyAndTemplate(
      owner: binding.Primitive.Party,
      templateId: Identifier = Delegated.id.unwrap,
  ): TransactionFilter =
    new TransactionFilter(
      Map(
        owner.unwrap -> new Filters(
          Some(
            InclusiveFilters(templateFilters =
              Seq(TemplateFilter(Some(templateId), includeCreatedEventBlob = true))
            )
          )
        )
      )
    )

  private def createEventToDisclosedContract(ev: CreatedEvent): DisclosedContract =
    DisclosedContract(
      templateId = ev.templateId,
      contractId = ev.contractId,
      createdEventBlob = ev.createdEventBlob,
    )

  private def exerciseWithKey_byKey_request(
      ledger: ParticipantTestContext,
      owner: Primitive.Party,
      party: Primitive.Party,
      withKeyDisclosedContract: Option[DisclosedContract],
  ): SubmitAndWaitRequest =
    ledger
      .submitAndWaitRequest(
        party,
        Command.of(
          Command.Command.ExerciseByKey(
            ExerciseByKeyCommand(
              Some(WithKey.id.unwrap),
              Option(Value(Value.Sum.Party(Party.unwrap(owner)))),
              "WithKey_NoOp",
              Option(
                Value(
                  Value.Sum.Record(
                    Record(
                      None,
                      List(RecordField("", Some(Value(Value.Sum.Party(Party.unwrap(party)))))),
                    )
                  )
                )
              ),
            )
          )
        ),
      )
      .update(_.commands.disclosedContracts := withKeyDisclosedContract.iterator.toSeq)
}
