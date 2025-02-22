// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.pruning

import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.protocol.{DomainParametersLookup, StaticDomainParameters}
import com.digitalasset.canton.time.PositiveSeconds
import com.digitalasset.canton.topology.client.DomainTopologyClient
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.EitherUtil.*

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.util.chaining.*

class SortedReconciliationIntervalsProvider(
    reconciliationIntervalsProvider: DomainParametersLookup[PositiveSeconds],
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  private val approximateLatestReconciliationInterval = new AtomicReference[
    Option[SortedReconciliationIntervals.ReconciliationInterval]
  ](None)

  def getApproximateLatestReconciliationInterval
      : Option[SortedReconciliationIntervals.ReconciliationInterval] =
    approximateLatestReconciliationInterval.get()

  def approximateReconciliationIntervals(implicit
      traceContext: TraceContext
  ): Future[SortedReconciliationIntervals] = reconciliationIntervals(
    reconciliationIntervalsProvider.approximateTimestamp
  )

  def reconciliationIntervals(
      validAt: CantonTimestamp
  )(implicit
      traceContext: TraceContext
  ): Future[SortedReconciliationIntervals] =
    reconciliationIntervalsProvider
      .getAll(validAt)
      .map { reconciliationIntervals =>
        SortedReconciliationIntervals
          .create(
            reconciliationIntervals,
            validUntil = validAt,
          )
          .tapLeft(logger.error(_))
          .getOrElse(SortedReconciliationIntervals.empty)
          .tap { sortedReconciliationIntervals =>
            val latest = sortedReconciliationIntervals.intervals.headOption

            approximateLatestReconciliationInterval.set(latest)
          }
      }
}

object SortedReconciliationIntervalsProvider {
  def apply(
      staticDomainParameters: StaticDomainParameters,
      topologyClient: DomainTopologyClient,
      futureSupervisor: FutureSupervisor,
      loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext): SortedReconciliationIntervalsProvider =
    new SortedReconciliationIntervalsProvider(
      DomainParametersLookup.forReconciliationInterval(
        staticDomainParameters,
        topologyClient,
        futureSupervisor,
        loggerFactory,
      ),
      loggerFactory,
    )
}
