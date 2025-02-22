// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf.engine.trigger

import akka.http.scaladsl.model.Uri
import com.daml.ledger.api.v1.value.Identifier
import com.daml.timer.RetryStrategy
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class TriggerServiceTestTls
    extends AbstractTriggerServiceTest
    with NoAuthFixture
    with TriggerDaoInMemFixture
    with Matchers {

  override protected lazy val tlsEnable: Boolean = true

  it should "successfully run Cat breeding trigger" in {
    withTriggerService(List(dar)) { uri: Uri =>
      for {
        client <- defaultLedgerClient()
        party <- allocateParty(client)
        resp <- startTrigger(uri, s"$testPkgId:Cats:breedingTrigger", party, Some(applicationId))
        catsTrigger <- parseTriggerId(resp)
        _ <- assertTriggerIds(uri, party, Vector(catsTrigger))
        _ <- assertTriggerStatus(catsTrigger, _.last shouldBe "running")
        // Ensure at least one Cat contract is created
        _ <- RetryStrategy.constant(10, 1.seconds) { (_, _) =>
          getActiveContracts(client, party, Identifier(testPkgId, "Cats", "Cat"))
            .map(_.length should be >= 1)
        }
      } yield succeed
    }
  }
}
