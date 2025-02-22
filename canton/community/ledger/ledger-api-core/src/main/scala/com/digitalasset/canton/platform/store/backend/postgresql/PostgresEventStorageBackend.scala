// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.store.backend.postgresql

import anorm.SqlParser.int
import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.platform.store.backend.common.ComposableQuery.SqlStringInterpolation
import com.digitalasset.canton.platform.store.backend.common.{
  EventStorageBackendTemplate,
  ParameterStorageBackendImpl,
}
import com.digitalasset.canton.platform.store.cache.LedgerEndCache
import com.digitalasset.canton.platform.store.interning.StringInterning

import java.sql.Connection

class PostgresEventStorageBackend(
    ledgerEndCache: LedgerEndCache,
    stringInterning: StringInterning,
    loggerFactory: NamedLoggerFactory,
) extends EventStorageBackendTemplate(
      queryStrategy = PostgresQueryStrategy,
      ledgerEndCache = ledgerEndCache,
      stringInterning = stringInterning,
      participantAllDivulgedContractsPrunedUpToInclusive =
        ParameterStorageBackendImpl.participantAllDivulgedContractsPrunedUpToInclusive,
      loggerFactory = loggerFactory,
    ) {

  /** If `pruneAllDivulgedContracts` is set, validate that the pruning offset is after
    * the last event offset that was ingested before the migration to append-only schema (if such event offset exists).
    * (see `LedgerDao.prune`)
    */
  override def isPruningOffsetValidAgainstMigration(
      pruneUpToInclusive: Offset,
      pruneAllDivulgedContracts: Boolean,
      connection: Connection,
  ): Boolean =
    if (pruneAllDivulgedContracts) {
      import com.digitalasset.canton.platform.store.backend.Conversions.OffsetToStatement
      def selectFrom(table: String) = cSQL"""
         (select max(event_offset) as max_event_offset
         from #$table, participant_migration_history_v100
         where event_sequential_id <= ledger_end_sequential_id_before)
      """
      SQL"""
       with max_offset_before_migration as (
         select max(max_event_offset) as max_event_offset from (
           ${selectFrom("participant_events_create")}
           UNION ALL
           ${selectFrom("participant_events_consuming_exercise")}
           UNION ALL
           ${selectFrom("participant_events_non_consuming_exercise")}
           UNION ALL
           ${selectFrom("participant_events_divulgence")}
         ) as participant_events
       )
       select 1 as result
       from max_offset_before_migration
       where max_event_offset >= $pruneUpToInclusive
       """
        .as(int("result").singleOpt)(connection)
        .isEmpty
    } else {
      true
    }
}
