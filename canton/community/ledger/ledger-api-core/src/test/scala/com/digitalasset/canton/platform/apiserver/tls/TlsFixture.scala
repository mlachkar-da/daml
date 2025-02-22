// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.apiserver.tls

import com.daml.grpc.sampleservice.implementations.HelloServiceReferenceImplementation
import com.daml.ledger.resources.{Resource, ResourceContext, ResourceOwner}
import com.daml.platform.hello.{HelloRequest, HelloResponse, HelloServiceGrpc}
import com.digitalasset.canton.config.RequireTypes.Port
import com.digitalasset.canton.ledger.api.tls.TlsConfiguration
import com.digitalasset.canton.ledger.client.GrpcChannel
import com.digitalasset.canton.ledger.client.configuration.LedgerClientChannelConfiguration
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.metrics.Metrics
import com.digitalasset.canton.platform.apiserver.{ApiService, ApiServices, LedgerApiService}
import io.grpc.{BindableService, ManagedChannel}
import io.netty.handler.ssl.ClientAuth

import java.io.File
import java.util.concurrent.Executors
import scala.collection.immutable
import scala.concurrent.Future

final case class TlsFixture(
    loggerFactory: NamedLoggerFactory,
    tlsEnabled: Boolean,
    serverCrt: File,
    serverKey: File,
    caCrt: File,
    clientCrt: Option[File],
    clientKey: Option[File],
    clientAuth: ClientAuth = ClientAuth.REQUIRE,
    certRevocationChecking: Boolean = false,
)(implicit rc: ResourceContext) {

  def makeARequest(): Future[HelloResponse] =
    resources().use { channel =>
      val testRequest = HelloRequest(1)
      HelloServiceGrpc
        .stub(channel)
        .single(testRequest)
    }

  private val DefaultMaxInboundMessageSize: Int = 4 * 1024 * 1024 // taken from the Sandbox config

  private final class MockApiServices(apiServices: ApiServices) extends ResourceOwner[ApiServices] {
    override def acquire()(implicit context: ResourceContext): Resource[ApiServices] = {
      Resource(Future.successful(apiServices))(_ => Future.successful(()))(context)
    }
  }

  private final class EmptyApiServices extends ApiServices {
    override val services: Iterable[BindableService] = List(
      new HelloServiceReferenceImplementation
    )
    override def withServices(otherServices: immutable.Seq[BindableService]): ApiServices = this
  }

  private val serverTlsConfiguration = TlsConfiguration(
    enabled = tlsEnabled,
    certChainFile = Some(serverCrt),
    privateKeyFile = Some(serverKey),
    trustCollectionFile = Some(caCrt),
    clientAuth = clientAuth,
    enableCertRevocationChecking = certRevocationChecking,
  )

  private def apiServerOwner(): ResourceOwner[ApiService] = {
    val apiServices = new EmptyApiServices
    val owner = new MockApiServices(apiServices)

    ResourceOwner
      .forExecutorService(() => Executors.newCachedThreadPool())
      .flatMap(servicesExecutor =>
        new LedgerApiService(
          apiServicesOwner = owner,
          desiredPort = Port.Dynamic,
          maxInboundMessageSize = DefaultMaxInboundMessageSize,
          address = None,
          tlsConfiguration = Some(serverTlsConfiguration),
          servicesExecutor = servicesExecutor,
          metrics = Metrics.ForTesting,
          loggerFactory = loggerFactory,
        )
      )
  }

  private val clientTlsConfiguration =
    TlsConfiguration(
      enabled = tlsEnabled,
      certChainFile = clientCrt,
      privateKeyFile = clientKey,
      trustCollectionFile = Some(caCrt),
    )

  private val ledgerClientChannelConfiguration = LedgerClientChannelConfiguration(
    sslContext = clientTlsConfiguration.client()
  )

  private def resources(): ResourceOwner[ManagedChannel] =
    for {
      apiServer <- apiServerOwner()
      channel <- new GrpcChannel.Owner(apiServer.port, ledgerClientChannelConfiguration)
    } yield channel

}
