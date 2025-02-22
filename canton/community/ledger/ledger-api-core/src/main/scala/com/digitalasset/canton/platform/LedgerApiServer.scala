// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform

import com.daml.ledger.resources.ResourceOwner
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.metrics.Metrics
import com.digitalasset.canton.platform.config.IndexServiceConfig
import com.digitalasset.canton.platform.index.InMemoryStateUpdater
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext

object LedgerApiServer {
  def createInMemoryStateAndUpdater(
      indexServiceConfig: IndexServiceConfig,
      maxCommandsInFlight: Int,
      metrics: Metrics,
      executionContext: ExecutionContext,
      tracer: Tracer,
      loggerFactory: NamedLoggerFactory,
      multiDomainEnabled: Boolean,
  )(implicit
      traceContext: TraceContext
  ): ResourceOwner[(InMemoryState, InMemoryStateUpdater.UpdaterFlow)] = {
    for {
      inMemoryState <- InMemoryState.owner(
        apiStreamShutdownTimeout = indexServiceConfig.apiStreamShutdownTimeout,
        bufferedStreamsPageSize = indexServiceConfig.bufferedStreamsPageSize,
        maxContractStateCacheSize = indexServiceConfig.maxContractStateCacheSize,
        maxContractKeyStateCacheSize = indexServiceConfig.maxContractKeyStateCacheSize,
        maxTransactionsInMemoryFanOutBufferSize =
          indexServiceConfig.maxTransactionsInMemoryFanOutBufferSize,
        executionContext = executionContext,
        maxCommandsInFlight = maxCommandsInFlight,
        metrics = metrics,
        tracer = tracer,
        loggerFactory = loggerFactory,
      )

      inMemoryStateUpdater <- InMemoryStateUpdater.owner(
        inMemoryState = inMemoryState,
        prepareUpdatesParallelism = indexServiceConfig.inMemoryStateUpdaterParallelism,
        preparePackageMetadataTimeOutWarning =
          indexServiceConfig.preparePackageMetadataTimeOutWarning.underlying,
        metrics = metrics,
        loggerFactory = loggerFactory,
        multiDomainEnabled = multiDomainEnabled,
      )
    } yield inMemoryState -> inMemoryStateUpdater
  }
}
