// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.apiserver.ratelimiting

import com.codahale.metrics.MetricRegistry
import com.daml.executors.executors.{NamedExecutor, QueueAwareExecutor}
import com.daml.grpc.adapter.utils.implementations.HelloServiceAkkaImplementation
import com.daml.grpc.sampleservice.implementations.HelloServiceReferenceImplementation
import com.daml.ledger.api.testing.utils.AkkaBeforeAndAfterAll
import com.daml.ledger.resources.{ResourceOwner, TestResourceContext}
import com.daml.platform.hello.{HelloRequest, HelloResponse, HelloServiceGrpc}
import com.daml.ports.Port
import com.daml.scalautil.Statement.discard
import com.daml.tracing.NoOpTelemetry
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.ledger.api.grpc.{GrpcClientResource, GrpcHealthService}
import com.digitalasset.canton.ledger.api.health.HealthChecks.ComponentName
import com.digitalasset.canton.ledger.api.health.{HealthChecks, ReportsHealth}
import com.digitalasset.canton.logging.SuppressingLogger
import com.digitalasset.canton.metrics.Metrics
import com.digitalasset.canton.platform.apiserver.ActiveStreamMetricsInterceptor
import com.digitalasset.canton.platform.apiserver.configuration.RateLimitingConfig
import com.digitalasset.canton.platform.apiserver.ratelimiting.LimitResult.LimitResultCheck
import com.google.protobuf.ByteString
import io.grpc.Status.Code
import io.grpc.*
import io.grpc.health.v1.health.{HealthCheckRequest, HealthCheckResponse, HealthGrpc}
import io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.ProtoReflectionService
import io.grpc.reflection.v1alpha.{
  ServerReflectionGrpc,
  ServerReflectionRequest,
  ServerReflectionResponse,
}
import io.grpc.stub.StreamObserver
import org.mockito.MockitoSugar
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.time.{Second, Span}

import java.io.IOException
import java.lang.management.*
import java.net.{InetAddress, InetSocketAddress}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Future, Promise}

final class RateLimitingInterceptorSpec
    extends AsyncFlatSpec
    with AkkaBeforeAndAfterAll
    with Eventually
    with TestResourceContext
    with MockitoSugar
    with BaseTest {

  import RateLimitingInterceptorSpec.*

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(1, Second)))

  private val config = RateLimitingConfig(100, 10, 75, 100 * RateLimitingConfig.Megabyte, 100)

  behavior of "RateLimitingInterceptor"

  it should "support additional checks" in {
    val executorWithQueueSize = new QueueAwareExecutor with NamedExecutor {
      private val queueSizeValues =
        Iterator(0L, config.maxApiServicesQueueSize.toLong + 1, 0L)
      override def queueSize: Long = queueSizeValues.next()
      override def name: String = "test"
    }
    val threadPoolHumanReadableName = "For testing"
    withChannel(
      metrics,
      new HelloServiceAkkaImplementation,
      config,
      additionalChecks = List(
        ThreadpoolCheck(
          threadPoolHumanReadableName,
          executorWithQueueSize,
          config.maxApiServicesQueueSize,
          loggerFactory,
        )
      ),
    ).use { channel: Channel =>
      val helloService = HelloServiceGrpc.stub(channel)
      for {
        _ <- helloService.single(HelloRequest(1))
        exception <- helloService.single(HelloRequest(2)).failed
        _ <- helloService.single(HelloRequest(3))
      } yield {
        exception.toString should include(threadPoolHumanReadableName)
      }
    }
  }

  /** Allowing metadata requests allows grpcurl to be used to debug problems */

  it should "allow metadata requests even when over limit" in {
    metrics.registry
      .meter(MetricRegistry.name(metrics.daml.lapi.threadpool.apiServices, "submitted"))
      .mark(config.maxApiServicesQueueSize.toLong + 1) // Over limit

    val protoService = ProtoReflectionService.newInstance()

    withChannel(metrics, protoService, config).use { channel: Channel =>
      val methodDescriptor: MethodDescriptor[ServerReflectionRequest, ServerReflectionResponse] =
        ServerReflectionGrpc.getServerReflectionInfoMethod
      val call = channel.newCall(methodDescriptor, CallOptions.DEFAULT)
      val promise = Promise[Status]()
      val listener = new ClientCall.Listener[ServerReflectionResponse]() {
        override def onReady(): Unit = {
          call.request(1)
        }
        override def onClose(status: Status, trailers: Metadata): Unit = {
          promise.success(status)
        }
      }
      call.start(listener, new Metadata())
      val request = ServerReflectionRequest
        .newBuilder()
        .setListServices("services")
        .setHost("localhost")
        .build()
      call.sendMessage(ServerReflectionRequest.newBuilder(request).build())
      call.halfClose()
      promise.future.map(status => status shouldBe Status.OK)
    }
  }

  it should "allow health checks event when over limit" in {
    metrics.registry
      .meter(MetricRegistry.name(metrics.daml.lapi.threadpool.apiServices, "submitted"))
      .mark(config.maxApiServicesQueueSize.toLong + 1) // Over limit

    val healthService =
      new GrpcHealthService(healthChecks, telemetry = NoOpTelemetry, loggerFactory = loggerFactory)(
        executionSequencerFactory,
        materializer,
        executionContext,
      )

    withChannel(metrics, healthService, config).use { channel: Channel =>
      val healthStub = HealthGrpc.stub(channel)
      val promise = Promise[Unit]()
      for {
        _ <- healthStub.check(HealthCheckRequest())
        _ = healthStub.watch(
          HealthCheckRequest(),
          new StreamObserver[HealthCheckResponse] {
            override def onNext(value: HealthCheckResponse): Unit = {
              promise.success(())
            }
            override def onError(t: Throwable): Unit = {}
            override def onCompleted(): Unit = {}
          },
        )
        _ <- promise.future
      } yield {
        succeed
      }
    }
  }

  it should "limit calls when there is a danger of running out of heap space" in {
    val poolName = "Tenured_Gen"
    val maxMemory = 100000L

    // Based on a combination of JvmMetricSet and MemoryUsageGaugeSet
    val expectedMetric = s"jvm_memory_usage_pools_$poolName"

    val memoryBean = mock[MemoryMXBean]

    val memoryPoolBean = mock[MemoryPoolMXBean]
    when(memoryPoolBean.getType).thenReturn(MemoryType.HEAP)
    when(memoryPoolBean.getName).thenReturn(poolName)
    when(memoryPoolBean.getCollectionUsage).thenReturn(new MemoryUsage(0, 0, 0, maxMemory))
    when(memoryPoolBean.isCollectionUsageThresholdSupported).thenReturn(true)
    when(memoryPoolBean.isCollectionUsageThresholdExceeded).thenReturn(false, true, false)
    when(memoryPoolBean.getCollectionUsageThreshold).thenReturn(
      config.calculateCollectionUsageThreshold(maxMemory)
    )

    val nonCollectableBean = mock[MemoryPoolMXBean]
    when(nonCollectableBean.getType).thenReturn(MemoryType.HEAP)
    when(nonCollectableBean.isCollectionUsageThresholdSupported).thenReturn(false)

    val nonHeapBean = mock[MemoryPoolMXBean]
    when(nonHeapBean.getType).thenReturn(MemoryType.NON_HEAP)
    when(nonHeapBean.isCollectionUsageThresholdSupported).thenReturn(true)

    val pool = List(nonCollectableBean, nonHeapBean, memoryPoolBean)

    withChannel(metrics, new HelloServiceAkkaImplementation, config, pool, memoryBean).use {
      channel: Channel =>
        val helloService = HelloServiceGrpc.stub(channel)
        for {
          _ <- helloService.single(HelloRequest(1))
          exception <- helloService.single(HelloRequest(2)).failed
          _ <- helloService.single(HelloRequest(3))
        } yield {
          verify(memoryPoolBean).setCollectionUsageThreshold(
            config.calculateCollectionUsageThreshold(maxMemory)
          )
          verify(memoryBean).gc()
          exception.getMessage should include(expectedMetric)
        }
    }
  }

  it should "limit the number of streams" in {

    val limitStreamConfig = RateLimitingConfig.Default.copy(maxStreams = 2)

    val waitService = new WaitService()
    withChannel(metrics, waitService, limitStreamConfig).use { channel: Channel =>
      {
        for {
          fStatus1 <- streamHello(channel) // Ok
          fStatus2 <- streamHello(channel) // Ok
          activeStreams = metrics.daml.lapi.streams.active.getValue
          fStatus3 <- streamHello(channel) // Limited
          status3 <- fStatus3 // Closed as part of limiting
          _ = waitService.completeStream()
          status1 <- fStatus1
          fStatus4 <- streamHello(channel) // Ok
          _ = waitService.completeStream()
          status2 <- fStatus2
          _ = waitService.completeStream()
          status4 <- fStatus4
        } yield {
          activeStreams shouldBe limitStreamConfig.maxStreams
          status1.getCode shouldBe Code.OK
          status2.getCode shouldBe Code.OK
          status3.getCode shouldBe Code.ABORTED
          status3.getDescription should include(metrics.daml.lapi.streams.activeName)
          status4.getCode shouldBe Code.OK
          eventually { metrics.daml.lapi.streams.active.getValue shouldBe 0 }
        }
      }
    }
  }

  it should "exclude non-stream traffic from stream counts" in {

    val limitStreamConfig = RateLimitingConfig.Default.copy(maxStreams = 2)

    val waitService = new WaitService()
    withChannel(metrics, waitService, limitStreamConfig).use { channel: Channel =>
      {
        for {

          fStatus1 <- streamHello(channel)
          fHelloStatus1 = singleHello(channel)
          fStatus2 <- streamHello(channel)
          fHelloStatus2 = singleHello(channel)

          activeStreams = metrics.daml.lapi.streams.active.getValue

          _ = waitService.completeStream()
          _ = waitService.completeSingle()
          _ = waitService.completeStream()
          _ = waitService.completeSingle()

          status1 <- fStatus1
          helloStatus1 <- fHelloStatus1
          status2 <- fStatus2
          helloStatus2 <- fHelloStatus2

        } yield {
          activeStreams shouldBe 2
          status1.getCode shouldBe Code.OK
          helloStatus1.getCode shouldBe Code.OK
          status2.getCode shouldBe Code.OK
          helloStatus2.getCode shouldBe Code.OK
          eventually { metrics.daml.lapi.streams.active.getValue shouldBe 0 }
        }
      }
    }
  }

  it should "stream rate limiting should not limit non-stream traffic" in {

    val limitStreamConfig = RateLimitingConfig.Default.copy(maxStreams = 2)

    val waitService = new WaitService()
    withChannel(metrics, waitService, limitStreamConfig).use { channel: Channel =>
      {
        for {

          fStatus1 <- streamHello(channel)
          fStatus2 <- streamHello(channel)
          fHelloStatus1 = singleHello(channel)

          _ = waitService.completeSingle()
          _ = waitService.completeStream()
          _ = waitService.completeStream()

          _ <- fStatus1
          _ <- fStatus2
          helloStatus <- fHelloStatus1

        } yield {
          helloStatus.getCode shouldBe Code.OK
        }
      }
    }
  }

  it should "maintain stream count for streams cancellations" in {

    val limitStreamConfig = RateLimitingConfig.Default.copy(maxStreams = 2)

    val waitService = new WaitService()
    withChannel(metrics, waitService, limitStreamConfig).use { channel: Channel =>
      for {
        fStatus1 <- streamHello(channel, cancel = true)
        status1 <- fStatus1
      } yield {
        status1.getCode shouldBe Code.CANCELLED

        eventually { metrics.daml.lapi.streams.active.getValue shouldBe 0 }
      }
    }
  }

  it should "should reset limit if a change of max memory is detected" in {

    val initMemory = 100000L
    val increasedMemory = 200000L

    val memoryBean = mock[MemoryMXBean]

    val memoryPoolBean = mock[MemoryPoolMXBean]
    when(memoryPoolBean.getType).thenReturn(MemoryType.HEAP)
    when(memoryPoolBean.getName).thenReturn("Tenured_Gen")
    when(memoryPoolBean.getCollectionUsage).thenReturn(
      new MemoryUsage(0, 0, 0, initMemory),
      new MemoryUsage(0, 0, 0, increasedMemory),
    )
    when(memoryPoolBean.isCollectionUsageThresholdSupported).thenReturn(true)
    when(memoryPoolBean.isCollectionUsageThresholdExceeded).thenReturn(true)

    val pool = List(memoryPoolBean)

    withChannel(metrics, new HelloServiceAkkaImplementation, config, pool, memoryBean).use {
      channel: Channel =>
        val helloService = HelloServiceGrpc.stub(channel)
        for {
          _ <- helloService.single(HelloRequest(1))
        } yield {
          verify(memoryPoolBean).setCollectionUsageThreshold(
            config.calculateCollectionUsageThreshold(initMemory)
          )
          verify(memoryPoolBean).setCollectionUsageThreshold(
            config.calculateCollectionUsageThreshold(increasedMemory)
          )
          succeed
        }
    }
  }

  it should "calculate the collection threshold zone size" in {
    // The actual threshold used would be max(maxHeapSpacePercentage * maxHeapSize / 100, maxHeapSize - maxOverThresholdZoneSize)
    val underTest =
      RateLimitingConfig.Default.copy(maxUsedHeapSpacePercentage = 90, minFreeHeapSpaceBytes = 1000)
    underTest.calculateCollectionUsageThreshold(3000) shouldBe 2700 // 90%
    underTest.calculateCollectionUsageThreshold(101000) shouldBe 100000 // 101000 - 1000
  }

}

object RateLimitingInterceptorSpec extends MockitoSugar {

  private val loggerFactory = SuppressingLogger(getClass)
  private val logger = loggerFactory.getLogger(getClass)
  private val healthChecks = new HealthChecks(Map.empty[ComponentName, ReportsHealth])

  private def metrics = Metrics.ForTesting

  // For tests that do not involve memory
  private def underLimitMemoryPoolMXBean(): MemoryPoolMXBean = {
    val memoryPoolBean = mock[MemoryPoolMXBean]
    when(memoryPoolBean.getType).thenReturn(MemoryType.HEAP)
    when(memoryPoolBean.getName).thenReturn("UnderLimitPool")
    when(memoryPoolBean.getCollectionUsage).thenReturn(new MemoryUsage(0, 0, 0, 0))
    when(memoryPoolBean.isCollectionUsageThresholdSupported).thenReturn(true)
    when(memoryPoolBean.isCollectionUsageThresholdExceeded).thenReturn(false)
  }

  def withChannel(
      metrics: Metrics,
      service: BindableService,
      config: RateLimitingConfig,
      pool: List[MemoryPoolMXBean] = List(underLimitMemoryPoolMXBean()),
      memoryBean: MemoryMXBean = ManagementFactory.getMemoryMXBean,
      additionalChecks: List[LimitResultCheck] = List.empty,
  ): ResourceOwner[Channel] = {
    val rateLimitingInterceptor = RateLimitingInterceptor(
      loggerFactory,
      metrics,
      config,
      pool,
      memoryBean,
      additionalChecks,
    )
    val activeStreamMetricsInterceptor = new ActiveStreamMetricsInterceptor(metrics)
    for {
      server <- ResourceOwner.forServer(
        NettyServerBuilder
          .forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress, 0))
          .directExecutor()
          .intercept(activeStreamMetricsInterceptor)
          .intercept(rateLimitingInterceptor)
          .addService(service),
        FiniteDuration(10, "seconds"),
      )
      channel <- GrpcClientResource.owner(Port(server.getPort))
    } yield channel
  }

  /** By default [[HelloServiceReferenceImplementation]] will return all elements and complete the stream on
    * the server side on every request.  For stream based rate limiting we want to explicitly hold open
    * the stream such that we know for sure how many streams are open.
    */
  class WaitService extends HelloServiceReferenceImplementation {

    private val observers = new LinkedBlockingQueue[StreamObserver[HelloResponse]]()
    private val requests = new LinkedBlockingQueue[Promise[HelloResponse]]()

    def completeStream(): Unit = {
      val responseObserver = observers.remove()
      responseObserver.onNext(HelloResponse(0, ByteString.copyFromUtf8("last")))
      responseObserver.onCompleted()
    }

    def completeSingle(): Unit = {
      discard(
        requests
          .poll(10, TimeUnit.SECONDS)
          .success(HelloResponse(0, ByteString.copyFromUtf8("only")))
      )
    }

    override def serverStreaming(
        request: HelloRequest,
        responseObserver: StreamObserver[HelloResponse],
    ): Unit = {
      responseObserver.onNext(HelloResponse(0, ByteString.copyFromUtf8("first")))
      observers.put(responseObserver)
    }

    override def single(request: HelloRequest): Future[HelloResponse] = {
      val promise = Promise[HelloResponse]()
      requests.put(promise)
      promise.future
    }

  }

  def singleHello(channel: Channel): Future[Status] = {

    val status = Promise[Status]()
    val clientCall = channel.newCall(HelloServiceGrpc.METHOD_SINGLE, CallOptions.DEFAULT)

    clientCall.start(
      new ClientCall.Listener[HelloResponse] {
        override def onClose(grpcStatus: Status, trailers: Metadata): Unit = {
          logger.debug(s"Single closed with $grpcStatus")
          status.success(grpcStatus)
        }
        override def onMessage(message: HelloResponse): Unit = {
          logger.debug(s"Got single message: $message")
        }
      },
      new Metadata(),
    )

    clientCall.sendMessage(HelloRequest(1))
    clientCall.halfClose()
    clientCall.request(1)

    status.future
  }

  def streamHello(channel: Channel, cancel: Boolean = false): Future[Future[Status]] = {

    val init = Promise[Future[Status]]()
    val status = Promise[Status]()
    val clientCall =
      channel.newCall(HelloServiceGrpc.METHOD_SERVER_STREAMING, CallOptions.DEFAULT)

    clientCall.start(
      new ClientCall.Listener[HelloResponse] {
        override def onClose(grpcStatus: Status, trailers: Metadata): Unit = {
          if (!init.isCompleted) init.success(status.future)
          status.success(grpcStatus)
        }
        override def onMessage(message: HelloResponse): Unit = {
          if (!init.isCompleted) init.success(status.future)
        }
      },
      new Metadata(),
    )

    // When the handshake is single message -> streamHello then onReady is not applicable
    clientCall.sendMessage(HelloRequest(2))
    clientCall.halfClose()
    clientCall.request(2) // Request both messages

    if (cancel) clientCall.cancel("Test cancel", new IOException("network down"))

    init.future
  }

}
