// Copyright (c) 2020 The DAML Authors. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.on.sql

import java.time.Instant

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.codahale.metrics.MetricRegistry
import com.daml.ledger.participant.state.kvutils.ParticipantStateIntegrationSpecBase
import com.daml.ledger.participant.state.kvutils.ParticipantStateIntegrationSpecBase.ParticipantState
import com.daml.ledger.participant.state.kvutils.api.KeyValueParticipantState
import com.daml.ledger.participant.state.v1.SeedService.Seeding
import com.daml.ledger.participant.state.v1.{LedgerId, ParticipantId, SeedService}
import com.digitalasset.logging.LoggingContext
import com.digitalasset.resources.ResourceOwner

abstract class SqlLedgerReaderWriterIntegrationSpecBase(implementationName: String)
    extends ParticipantStateIntegrationSpecBase(implementationName) {
  protected def jdbcUrl(id: String): String

  override protected final val startIndex: Long = StartIndex

  override protected final def participantStateFactory(
      ledgerId: Option[LedgerId],
      participantId: ParticipantId,
      testId: String,
      heartbeats: Source[Instant, NotUsed],
      metricRegistry: MetricRegistry,
  )(implicit logCtx: LoggingContext): ResourceOwner[ParticipantState] =
    new SqlLedgerReaderWriter.Owner(
      ledgerId,
      participantId,
      metricRegistry,
      jdbcUrl(testId),
      heartbeats = heartbeats,
      // Using a weak random source to avoid slowdown during tests.
      seedService = SeedService(Seeding.Weak),
    ).map(readerWriter => new KeyValueParticipantState(readerWriter, readerWriter, metricRegistry))
}
