// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.store.backend

import com.daml.ledger.api.v1.contract_metadata.ContractMetadata
import com.daml.lf.crypto.Hash
import com.daml.lf.data.{Bytes, Ref}
import com.digitalasset.canton.platform.store.backend.common.{
  EventPayloadSourceForFlatTx,
  EventPayloadSourceForTreeTx,
}
import com.digitalasset.canton.platform.store.dao.events.Raw.{FlatEvent, TreeEvent}
import com.google.protobuf.ByteString
import com.google.protobuf.timestamp.Timestamp
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, OptionValues}

import scala.reflect.ClassTag

// TODO(i12294): Complete test suite for asserting flat/tree/ACS streams contents
private[backend] trait StorageBackendTestsTransactionStreamsEvents
    extends Matchers
    with OptionValues
    with StorageBackendSpec {
  this: AnyFlatSpec =>

  import StorageBackendTestValues.*

  behavior of "StorageBackend events"

  it should "return the correct create contract metadata" in {
    val signatory = "party"
    val driverMetadata = Bytes.assertFromString("00abcd")
    val someHash = Hash.assertFromString("00" * 31 + "ff")

    testCreateContractMetadata(
      signatory,
      dtoCreate(
        offset = offset(1),
        eventSequentialId = 1L,
        contractId = hashCid("#1"),
        driverMetadata = Some(driverMetadata.toByteArray),
        signatory = signatory,
        keyHash = Some(someHash.toHexString),
      ),
      ContractMetadata(
        createdAt = Some(Timestamp(someTime.toInstant)),
        contractKeyHash = someHash.bytes.toByteString,
        driverMetadata = driverMetadata.toByteString,
      ),
    )
  }

  it should "allow missing contract key hash and driver metadata" in {
    val signatory = "party"

    testCreateContractMetadata(
      signatory,
      dtoCreate(
        offset = offset(1),
        eventSequentialId = 1L,
        contractId = hashCid("#1"),
        driverMetadata = None,
        signatory = signatory,
        keyHash = None,
      ),
      ContractMetadata(
        createdAt = Some(Timestamp(someTime.toInstant)),
        contractKeyHash = ByteString.EMPTY,
        driverMetadata = ByteString.EMPTY,
      ),
    )
  }

  it should "return the correct created_at" in {
    testCreatedAt(
      "party",
      dtoCreate(
        offset = offset(1),
        eventSequentialId = 1L,
        contractId = hashCid("#1"),
        signatory = "party",
      ),
      Timestamp(someTime.toInstant),
    )
  }

  // Allow using deprecated Protobuf fields for backwards compatibility
  @annotation.nowarn(
    "cat=deprecation&origin=com\\.daml\\.ledger\\.api\\.v1\\.event\\.CreatedEvent.*"
  )
  private def testCreateContractMetadata(
      signatory: String,
      create: DbDto.EventCreate,
      expectedCreateContractMetadata: ContractMetadata,
  ): Assertion = {
    val (
      flatTransactionEvents,
      transactionTreeEvents,
      flatTransaction,
      transactionTree,
      acs,
    ) = ingestAndFetch(signatory, create)

    extractContractMetadataFrom[FlatEvent.Created, FlatEvent](
      in = flatTransactionEvents,
      contractMetadata = _.partial.metadata,
    ) shouldBe expectedCreateContractMetadata

    extractContractMetadataFrom[FlatEvent.Created, FlatEvent](
      in = flatTransaction,
      contractMetadata = _.partial.metadata,
    ) shouldBe expectedCreateContractMetadata

    extractContractMetadataFrom[TreeEvent.Created, TreeEvent](
      in = transactionTreeEvents,
      contractMetadata = _.partial.metadata,
    ) shouldBe expectedCreateContractMetadata

    extractContractMetadataFrom[TreeEvent.Created, TreeEvent](
      in = transactionTree,
      contractMetadata = _.partial.metadata,
    ) shouldBe expectedCreateContractMetadata

    extractContractMetadataFrom[FlatEvent.Created, FlatEvent](
      in = acs,
      contractMetadata = _.partial.metadata,
    ) shouldBe expectedCreateContractMetadata
  }

  private def ingestAndFetch(signatory: String, create: DbDto.EventCreate) = {
    val someParty = Ref.Party.assertFromString(signatory)

    executeSql(backend.parameter.initializeParameters(someIdentityParams, loggerFactory))
    executeSql(ingest(Vector(create), _))
    executeSql(updateLedgerEnd(offset(1), 1L))

    val flatTransactionEvents = executeSql(
      backend.event.transactionStreamingQueries.fetchEventPayloadsFlat(
        EventPayloadSourceForFlatTx.Create
      )(eventSequentialIds = Seq(1L), Set(someParty))
    )
    val transactionTreeEvents = executeSql(
      backend.event.transactionStreamingQueries.fetchEventPayloadsTree(
        EventPayloadSourceForTreeTx.Create
      )(eventSequentialIds = Seq(1L), Set(someParty))
    )
    val flatTransaction = executeSql(
      backend.event.transactionPointwiseQueries.fetchFlatTransactionEvents(1L, 1L, Set(someParty))
    )
    val transactionTree = executeSql(
      backend.event.transactionPointwiseQueries.fetchTreeTransactionEvents(1L, 1L, Set(someParty))
    )
    val acs = executeSql(backend.event.activeContractCreateEventBatch(Seq(1L), Set(someParty), 1L))
    (flatTransactionEvents, transactionTreeEvents, flatTransaction, transactionTree, acs)
  }

  private def testCreatedAt(
      signatory: String,
      create: DbDto.EventCreate,
      expectedCreatedAt: Timestamp,
  ): Assertion = {
    val (
      flatTransactionEvents,
      transactionTreeEvents,
      flatTransaction,
      transactionTree,
      acs,
    ) = ingestAndFetch(signatory, create)

    extractCreatedAtFrom[FlatEvent.Created, FlatEvent](
      in = flatTransactionEvents,
      createdAt = _.partial.createdAt,
    ) shouldBe expectedCreatedAt

    extractCreatedAtFrom[FlatEvent.Created, FlatEvent](
      in = flatTransaction,
      createdAt = _.partial.createdAt,
    ) shouldBe expectedCreatedAt

    extractCreatedAtFrom[TreeEvent.Created, TreeEvent](
      in = transactionTreeEvents,
      createdAt = _.partial.createdAt,
    ) shouldBe expectedCreatedAt

    extractCreatedAtFrom[TreeEvent.Created, TreeEvent](
      in = transactionTree,
      createdAt = _.partial.createdAt,
    ) shouldBe expectedCreatedAt

    extractCreatedAtFrom[FlatEvent.Created, FlatEvent](
      in = acs,
      createdAt = _.partial.createdAt,
    ) shouldBe expectedCreatedAt
  }

  private def extractContractMetadataFrom[O: ClassTag, E >: O](
      in: Seq[EventStorageBackend.Entry[E]],
      contractMetadata: O => Option[ContractMetadata],
  ): ContractMetadata = {
    in.size shouldBe 1
    in.head.event match {
      case o: O => contractMetadata(o).value
      case _ =>
        fail(
          s"Expected created event of type ${implicitly[reflect.ClassTag[O]].runtimeClass.getSimpleName}"
        )
    }
  }

  private def extractCreatedAtFrom[O: ClassTag, E >: O](
      in: Seq[EventStorageBackend.Entry[E]],
      createdAt: O => Option[Timestamp],
  ): Timestamp = {
    in.size shouldBe 1
    in.head.event match {
      case o: O => createdAt(o).value
      case _ =>
        fail(
          s"Expected created event of type ${implicitly[reflect.ClassTag[O]].runtimeClass.getSimpleName}"
        )
    }
  }
}
