// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.protocol.submission

import cats.data.EitherT
import com.daml.lf.data.Ref.PackageId
import com.digitalasset.canton.*
import com.digitalasset.canton.data.GenTransactionTree
import com.digitalasset.canton.participant.DefaultParticipantStateValues
import com.digitalasset.canton.participant.protocol.submission.TransactionTreeFactory.*
import com.digitalasset.canton.protocol.ExampleTransactionFactory.{
  defaultTestingIdentityFactory,
  defaultTestingTopology,
}
import com.digitalasset.canton.protocol.WellFormedTransaction.WithoutSuffixes
import com.digitalasset.canton.protocol.*
import com.digitalasset.canton.topology.client.TopologySnapshot
import com.digitalasset.canton.version.ProtocolVersion
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.Future

@SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
final class TransactionTreeFactoryImplTest extends AsyncWordSpec with BaseTest {

  val factory: ExampleTransactionFactory = new ExampleTransactionFactory()()

  def successfulLookup(
      example: ExampleTransaction
  ): SerializableContractOfId = id => {
    EitherT.fromEither(
      example.inputContracts
        .get(id)
        .toRight(ContractLookupError(id, "Unable to lookup input contract from test data"))
    )
  }

  def failedLookup(
      testErrorMessage: String
  ): SerializableContractOfId = id => EitherT.leftT(ContractLookupError(id, testErrorMessage))

  def createTransactionTreeFactory(version: ProtocolVersion): TransactionTreeFactoryImpl =
    TransactionTreeFactoryImpl(
      ExampleTransactionFactory.submitterParticipant,
      factory.domainId,
      version,
      factory.cryptoOps,
      uniqueContractKeys = true,
      loggerFactory,
    )

  def createTransactionTree(
      treeFactory: TransactionTreeFactoryImpl,
      transaction: WellFormedTransaction[WithoutSuffixes],
      contractInstanceOfId: SerializableContractOfId,
      keyResolver: LfKeyResolver,
      actAs: List[LfPartyId] = List(ExampleTransactionFactory.submitter),
      snapshot: TopologySnapshot = factory.topologySnapshot,
  ): EitherT[Future, TransactionTreeConversionError, GenTransactionTree] = {
    val submitterInfo = DefaultParticipantStateValues.submitterInfo(actAs)
    treeFactory.createTransactionTree(
      transaction,
      submitterInfo,
      factory.confirmationPolicy,
      Some(WorkflowId.assertFromString("testWorkflowId")),
      factory.mediatorRef,
      factory.transactionSeed,
      factory.transactionUuid,
      snapshot,
      contractInstanceOfId,
      keyResolver,
      factory.ledgerTime.plusSeconds(100),
      validatePackageVettings = true,
    )
  }

  "TransactionTreeFactoryImpl@testedVersion" should {
    // Shadow default factory with the protocol version explicitly set
    val factory: ExampleTransactionFactory = new ExampleTransactionFactory(
      versionOverride = Some(testedProtocolVersion)
    )()

    "A transaction tree factory" when {

      "everything is ok" must {
        forEvery(factory.standardHappyCases) { example =>
          lazy val treeFactory = createTransactionTreeFactory(testedProtocolVersion)

          s"create the correct views for: $example" in {
            createTransactionTree(
              treeFactory,
              example.wellFormedUnsuffixedTransaction,
              successfulLookup(example),
              example.keyResolver,
            ).value.flatMap(_ should equal(Right(example.transactionTree)))
          }
        }
      }

      "a contract lookup fails" must {
        lazy val errorMessage = "Test error message"
        lazy val treeFactory = createTransactionTreeFactory(testedProtocolVersion)

        lazy val example = factory.SingleExercise(
          factory.deriveNodeSeed(0)
        ) // pick an example that needs divulgence of absolute ids

        "reject the input" in {
          createTransactionTree(
            treeFactory,
            example.wellFormedUnsuffixedTransaction,
            failedLookup(errorMessage),
            example.keyResolver,
          ).value.flatMap(
            _ shouldEqual Left(
              ContractLookupError(example.contractId, errorMessage)
            )
          )
        }
      }

      "empty actAs set is empty" must {
        lazy val treeFactory = createTransactionTreeFactory(testedProtocolVersion)

        "reject the input" in {
          val example = factory.standardHappyCases.headOption.value
          createTransactionTree(
            treeFactory,
            example.wellFormedUnsuffixedTransaction,
            successfulLookup(example),
            example.keyResolver,
            actAs = List.empty,
          ).value
            .flatMap(
              _ should equal(Left(SubmitterMetadataError("The actAs set must not be empty.")))
            )
        }
      }

      "checking package vettings" must {
        lazy val treeFactory = createTransactionTreeFactory(testedProtocolVersion)
        lazy val banana = PackageId.assertFromString("banana")
        "fail if the main package is not vetted" in {
          val example = factory.standardHappyCases(2)
          createTransactionTree(
            treeFactory,
            example.wellFormedUnsuffixedTransaction,
            successfulLookup(example),
            example.keyResolver,
            snapshot = defaultTestingTopology.withPackages(Seq.empty).build().topologySnapshot(),
          ).value.flatMap(_ should matchPattern { case Left(UnknownPackageError(_)) => })
        }
        "fail if some dependency is not vetted" in {

          val example = factory.standardHappyCases(2)
          for {
            err <- createTransactionTree(
              treeFactory,
              example.wellFormedUnsuffixedTransaction,
              successfulLookup(example),
              example.keyResolver,
              snapshot = defaultTestingIdentityFactory.topologySnapshot(
                packageDependencies = x =>
                  EitherT.rightT(
                    if (x == ExampleTransactionFactory.packageId)
                      Set(banana)
                    else Set.empty[PackageId]
                  )
              ),
            ).value
          } yield inside(err) { case Left(UnknownPackageError(unknownTo)) =>
            forEvery(unknownTo) {
              _.packageId shouldBe banana
            }
            unknownTo should not be empty
          }
        }

        "fail gracefully if the present participant is misconfigured and somehow doesn't have a package that it should have" in {
          val example = factory.standardHappyCases(2)
          for {
            err <- createTransactionTree(
              treeFactory,
              example.wellFormedUnsuffixedTransaction,
              successfulLookup(example),
              example.keyResolver,
              snapshot = defaultTestingIdentityFactory.topologySnapshot(
                packageDependencies = x =>
                  if (x == ExampleTransactionFactory.packageId)
                    EitherT.leftT(banana)
                  else EitherT.rightT(Set.empty[PackageId])
              ),
            ).value
          } yield inside(err) { case Left(UnknownPackageError(unknownTo)) =>
            unknownTo should not be empty
          }
        }
      }
    }
  }
}
