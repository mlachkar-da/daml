// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.store.dao

import com.daml.lf.data.Ref
import com.daml.lf.transaction.{BlindingInfo, NodeId}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{LoneElement, OptionValues}

private[dao] trait JdbcLedgerDaoTransactionsWriterSpec extends LoneElement with OptionValues {
  this: AsyncFlatSpec with Matchers with JdbcLedgerDaoSuite =>

  private val ok = io.grpc.Status.Code.OK.value()

  behavior of "JdbcLedgerDao (TransactionsWriter)"

  it should "serialize a valid positive lookupByKey" in {
    val keyValue = "positive-lookup-by-key"

    for {
      from <- ledgerDao.lookupLedgerEnd()
      (_, create) <- store(txCreateContractWithKey(alice, keyValue))
      createdContractId = nonTransient(create).loneElement
      (_, lookup) <- store(txLookupByKey(alice, keyValue, Some(createdContractId)))
      to <- ledgerDao.lookupLedgerEnd()
      completions <- getCompletions(from.lastOffset, to.lastOffset, defaultAppId, Set(alice))
    } yield {
      completions should contain.allOf(
        create.commandId.value -> ok,
        lookup.commandId.value -> ok,
      )
    }
  }

  it should "serialize a valid fetch" in {
    val keyValue = "valid-fetch"

    for {
      from <- ledgerDao.lookupLedgerEnd()
      (_, create) <- store(txCreateContractWithKey(alice, keyValue))
      createdContractId = nonTransient(create).loneElement
      (_, fetch) <- store(txFetch(alice, createdContractId))
      to <- ledgerDao.lookupLedgerEnd()
      completions <- getCompletions(from.lastOffset, to.lastOffset, defaultAppId, Set(alice))
    } yield {
      completions should contain.allOf(
        create.commandId.value -> ok,
        fetch.commandId.value -> ok,
      )
    }
  }

  it should "prefer pre-computed blinding info" in {
    val mismatchingBlindingInfo =
      BlindingInfo(Map(NodeId(0) -> Set(Ref.Party.assertFromString("zoe"))), Map())
    for {
      (_, tx) <- store(
        offsetAndTx = singleCreate,
        blindingInfo = Some(mismatchingBlindingInfo),
        divulgedContracts = Map.empty,
      )
      result <- ledgerDao.contractsReader.lookupActiveContractAndLoadArgument(
        Set(alice),
        nonTransient(tx).loneElement,
      )
    } yield {
      result shouldBe None
    }
  }
}
