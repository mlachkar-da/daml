// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.ledger.client.services.testing.time

import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, RunnableGraph, Sink}
import akka.stream.{ClosedShape, KillSwitches, Materializer, UniqueKillSwitch}
import com.daml.api.util.TimestampConversion.*
import com.daml.api.util.{TimeProvider, TimestampConversion}
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.grpc.adapter.client.akka.ClientAdapter
import com.daml.ledger.api.v1.testing.time_service.TimeServiceGrpc.{TimeService, TimeServiceStub}
import com.daml.ledger.api.v1.testing.time_service.{GetTimeRequest, SetTimeRequest}
import com.digitalasset.canton.DiscardOps
import com.digitalasset.canton.concurrent.DirectExecutionContext
import com.digitalasset.canton.ledger.client.LedgerClient
import com.digitalasset.canton.logging.NamedLoggerFactory

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}

class StaticTime(
    timeService: TimeService,
    clock: AtomicReference[Instant],
    killSwitch: UniqueKillSwitch,
    ledgerId: String,
) extends TimeProvider
    with AutoCloseable {

  def getCurrentTime: Instant = clock.get

  def timeRequest(instant: Instant): SetTimeRequest =
    SetTimeRequest(
      ledgerId,
      Some(TimestampConversion.fromInstant(getCurrentTime)),
      Some(TimestampConversion.fromInstant(instant)),
    )

  def setTime(instant: Instant)(implicit ec: ExecutionContext): Future[Unit] = {
    timeService.setTime(timeRequest(instant)).map { _ =>
      val _ = StaticTime.advanceClock(clock, instant)
    }
  }

  override def close(): Unit = killSwitch.shutdown()
}

object StaticTime {

  def advanceClock(clock: AtomicReference[Instant], instant: Instant): Instant = {
    clock.updateAndGet {
      case current if instant isAfter current => instant
      case current => current
    }
  }

  def updatedVia(
      timeService: TimeServiceStub,
      ledgerId: String,
      loggerFactory: NamedLoggerFactory,
      token: Option[String] = None,
  )(implicit m: Materializer, esf: ExecutionSequencerFactory): Future[StaticTime] = {
    val clockRef = new AtomicReference[Instant](Instant.EPOCH)
    val killSwitchExternal = KillSwitches.single[Instant]
    val sinkExternal = Sink.head[Instant]

    val logger = loggerFactory.getLogger(getClass)
    val directEc = DirectExecutionContext(logger)

    RunnableGraph
      .fromGraph {
        GraphDSL.createGraph(killSwitchExternal, sinkExternal) {
          case (killSwitch, futureOfFirstElem) =>
            // We serve this in a future which completes when the first element has passed through.
            // Thus we make sure that the object we serve already received time data from the ledger.
            futureOfFirstElem.map(_ => new StaticTime(timeService, clockRef, killSwitch, ledgerId))(
              directEc
            )
        } { implicit b => (killSwitch, sinkHead) =>
          import GraphDSL.Implicits.*
          val instantSource = b.add(
            ClientAdapter
              .serverStreaming(
                GetTimeRequest(ledgerId),
                LedgerClient.stub(timeService, token).getTime,
              )
              .map(r => toInstant(r.getCurrentTime))
          )

          val updateClock = b.add(Flow[Instant].map { i =>
            advanceClock(clockRef, i).discard
            i
          })

          val broadcastTimes = b.add(Broadcast[Instant](2))

          val ignore = b.add(Sink.ignore)

          // format: OFF
          instantSource ~> killSwitch ~> updateClock ~> broadcastTimes.in
                                                        broadcastTimes.out(0) ~> sinkHead
                                                        broadcastTimes.out(1) ~> ignore
          // format: ON

          ClosedShape
        }
      }
      .run()
  }

}
