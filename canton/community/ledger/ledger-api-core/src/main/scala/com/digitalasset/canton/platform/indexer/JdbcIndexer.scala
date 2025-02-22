// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.indexer

import akka.stream.*
import com.daml.ledger.resources.ResourceOwner
import com.daml.lf.data.Ref
import com.digitalasset.canton.ledger.participant.state.v2 as state
import com.digitalasset.canton.logging.{LoggingContextWithTrace, NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.metrics.Metrics
import com.digitalasset.canton.platform.InMemoryState
import com.digitalasset.canton.platform.index.InMemoryStateUpdater
import com.digitalasset.canton.platform.indexer.ha.HaConfig
import com.digitalasset.canton.platform.indexer.parallel.{
  InitializeParallelIngestion,
  ParallelIndexerFactory,
  ParallelIndexerSubscription,
}
import com.digitalasset.canton.platform.store.DbSupport.{
  DataSourceProperties,
  ParticipantDataSourceConfig,
}
import com.digitalasset.canton.platform.store.DbType
import com.digitalasset.canton.platform.store.backend.{
  ParameterStorageBackend,
  StorageBackendFactory,
  StringInterningStorageBackend,
}
import com.digitalasset.canton.platform.store.cache.ImmutableLedgerEndCache
import com.digitalasset.canton.platform.store.dao.DbDispatcher
import com.digitalasset.canton.platform.store.dao.events.{CompressionStrategy, LfValueTranslation}
import com.digitalasset.canton.platform.store.interning.UpdatingStringInterningView
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

object JdbcIndexer {
  final class Factory(
      participantId: Ref.ParticipantId,
      participantDataSourceConfig: ParticipantDataSourceConfig,
      config: IndexerConfig,
      readService: state.ReadService,
      metrics: Metrics,
      inMemoryState: InMemoryState,
      apiUpdaterFlow: InMemoryStateUpdater.UpdaterFlow,
      executionContext: ExecutionContext,
      tracer: Tracer,
      loggerFactory: NamedLoggerFactory,
      multiDomainEnabled: Boolean,
      dataSourceProperties: DataSourceProperties,
      highAvailability: HaConfig,
  )(implicit materializer: Materializer) {

    def initialized(
        logger: TracedLogger
    )(implicit traceContext: TraceContext): ResourceOwner[Indexer] = {
      val factory = StorageBackendFactory.of(
        DbType.jdbcType(participantDataSourceConfig.jdbcUrl),
        loggerFactory,
      )
      val dataSourceStorageBackend = factory.createDataSourceStorageBackend
      val ingestionStorageBackend = factory.createIngestionStorageBackend
      val meteringStoreBackend = factory.createMeteringStorageWriteBackend
      val parameterStorageBackend = factory.createParameterStorageBackend
      val meteringParameterStorageBackend = factory.createMeteringParameterStorageBackend
      val DBLockStorageBackend = factory.createDBLockStorageBackend
      val stringInterningStorageBackend = factory.createStringInterningStorageBackend
      val dbConfig = dataSourceProperties
      val indexer = ParallelIndexerFactory(
        inputMappingParallelism = config.inputMappingParallelism.unwrap,
        batchingParallelism = config.batchingParallelism.unwrap,
        dbConfig = dbConfig.createDbConfig(participantDataSourceConfig),
        haConfig = highAvailability,
        metrics = metrics,
        dbLockStorageBackend = DBLockStorageBackend,
        dataSourceStorageBackend = dataSourceStorageBackend,
        initializeParallelIngestion = InitializeParallelIngestion(
          providedParticipantId = participantId,
          parameterStorageBackend = parameterStorageBackend,
          ingestionStorageBackend = ingestionStorageBackend,
          stringInterningStorageBackend = stringInterningStorageBackend,
          metrics = metrics,
          loggerFactory = loggerFactory,
        ),
        parallelIndexerSubscription = ParallelIndexerSubscription(
          parameterStorageBackend = parameterStorageBackend,
          ingestionStorageBackend = ingestionStorageBackend,
          participantId = participantId,
          translation = new LfValueTranslation(
            metrics = metrics,
            engineO = None,
            loadPackage = (_, _) => Future.successful(None),
            loggerFactory = loggerFactory,
          ),
          compressionStrategy =
            if (config.enableCompression) CompressionStrategy.allGZIP(metrics)
            else CompressionStrategy.none(metrics),
          maxInputBufferSize = config.maxInputBufferSize.unwrap,
          inputMappingParallelism = config.inputMappingParallelism.unwrap,
          batchingParallelism = config.batchingParallelism.unwrap,
          ingestionParallelism = config.ingestionParallelism.unwrap,
          submissionBatchSize = config.submissionBatchSize,
          maxTailerBatchSize = config.maxTailerBatchSize,
          maxOutputBatchedBufferSize = config.maxOutputBatchedBufferSize,
          metrics = metrics,
          inMemoryStateUpdaterFlow = apiUpdaterFlow,
          stringInterningView = inMemoryState.stringInterningView,
          tracer = tracer,
          loggerFactory = loggerFactory,
          multiDomainEnabled = multiDomainEnabled,
        ),
        meteringAggregator = new MeteringAggregator.Owner(
          meteringStore = meteringStoreBackend,
          meteringParameterStore = meteringParameterStorageBackend,
          parameterStore = parameterStorageBackend,
          metrics = metrics,
          loggerFactory = loggerFactory,
        ).apply,
        mat = materializer,
        readService = readService,
        initializeInMemoryState = dbDispatcher =>
          ledgerEnd =>
            inMemoryState.initializeTo(ledgerEnd)(
              updateStringInterningView = (updatingStringInterningView, ledgerEnd) =>
                updateStringInterningView(
                  stringInterningStorageBackend,
                  metrics,
                  dbDispatcher,
                  updatingStringInterningView,
                  ledgerEnd,
                ),
              updatePackageMetadataView = UpdatePackageMetadataView(
                factory.createPackageStorageBackend(
                  ImmutableLedgerEndCache(ledgerEnd.lastOffset -> ledgerEnd.lastEventSeqId)
                ),
                metrics,
                dbDispatcher,
                _,
                executionContext,
                config.packageMetadataView,
                loggerFactory,
              ),
            ),
        loggerFactory = loggerFactory,
      )

      indexer
    }
  }

  private def updateStringInterningView(
      stringInterningStorageBackend: StringInterningStorageBackend,
      metrics: Metrics,
      dbDispatcher: DbDispatcher,
      updatingStringInterningView: UpdatingStringInterningView,
      ledgerEnd: ParameterStorageBackend.LedgerEnd,
  ): Future[Unit] =
    updatingStringInterningView.update(ledgerEnd.lastStringInterningId)(
      (fromExclusive, toInclusive) => {
        implicit val loggingContext: LoggingContextWithTrace =
          LoggingContextWithTrace.empty
        dbDispatcher.executeSql(metrics.daml.index.db.loadStringInterningEntries) {
          stringInterningStorageBackend.loadStringInterningEntries(
            fromExclusive,
            toInclusive,
          )
        }
      }
    )
}
