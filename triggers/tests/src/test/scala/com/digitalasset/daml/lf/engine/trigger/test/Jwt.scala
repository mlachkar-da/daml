// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf.engine.trigger.test

import akka.stream.scaladsl.Flow
import com.daml.integrationtest.CantonFixture
import com.daml.ledger.api.domain
import com.daml.ledger.api.refinements.ApiTypes.{Party => ApiParty}
import com.daml.ledger.api.v1.commands.CreateCommand
import com.daml.ledger.api.v1.{value => LedgerApi}
import com.daml.lf.data.Ref._
import com.daml.lf.engine.trigger.Runner.TriggerContext
import com.daml.lf.engine.trigger.TriggerMsg
import org.scalatest._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import scalaz.syntax.tag._

class Jwt extends AsyncWordSpec with AbstractTriggerTest with Matchers with TryValues {

  import AbstractTriggerTest._

  "Jwt" can {
    // We just need something simple to test the connection.
    val assetId = LedgerApi.Identifier(packageId, "ACS", "Asset")
    val assetMirrorId = LedgerApi.Identifier(packageId, "ACS", "AssetMirror")

    def asset(party: ApiParty): CreateCommand =
      CreateCommand(
        templateId = Some(assetId),
        createArguments = Some(
          LedgerApi.Record(
            fields =
              Seq(LedgerApi.RecordField("issuer", Some(LedgerApi.Value().withParty(party.unwrap))))
          )
        ),
      )
    "1 create" in {
      for {
        adminClient <- defaultLedgerClient(config.adminToken)
        userId = CantonFixture.freshUserId()
        party <- allocateParty(adminClient)
        user = domain.User(userId, None)
        rights = Seq(domain.UserRight.CanActAs(Party.assertFromString(party.unwrap)))
        _ <- adminClient.userManagementClient.createUser(user, rights)
        client <- defaultLedgerClient(config.getToken(userId))
        runner = getRunner(client, QualifiedName.assertFromString("ACS:test"), party)
        (acs, offset) <- runner.queryACS()
        // Start the future here
        finalStateF = runner
          .runWithACS(acs, offset, msgFlow = Flow[TriggerContext[TriggerMsg]].take(6))
          ._2
        // Execute commands
        _ <- create(client, party, asset(party))
        // Wait for the trigger to terminate
        _ <- finalStateF
        acs <- queryACS(client, party)
      } yield {
        acs(assetId) should have size 1
        acs(assetMirrorId) should have size 1
      }
    }
  }
}
