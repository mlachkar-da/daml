// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.common.domain.grpc

import cats.data.EitherT
import cats.syntax.bifunctor.*
import cats.syntax.either.*
import cats.syntax.traverse.*
import com.digitalasset.canton.common.domain.SequencerConnectClient.{
  DomainClientBootstrapInfo,
  Error,
}
import com.digitalasset.canton.common.domain.{SequencerConnectClient, ServiceAgreement}
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.domain.api.v0
import com.digitalasset.canton.domain.api.v0.GetServiceAgreementRequest
import com.digitalasset.canton.domain.api.v0.SequencerConnect.GetDomainParameters.Response.Parameters
import com.digitalasset.canton.domain.api.v0.SequencerConnect.VerifyActive
import com.digitalasset.canton.lifecycle.{FlagCloseable, Lifecycle}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.grpc.{CantonGrpcUtil, ClientChannelBuilder}
import com.digitalasset.canton.protocol.StaticDomainParameters
import com.digitalasset.canton.sequencing.GrpcSequencerConnection
import com.digitalasset.canton.sequencing.protocol.{HandshakeRequest, HandshakeResponse}
import com.digitalasset.canton.serialization.ProtoConverter.ParsingResult
import com.digitalasset.canton.topology.{DomainId, ParticipantId, SequencerId}
import com.digitalasset.canton.tracing.{TraceContext, TracingConfig}
import com.digitalasset.canton.util.retry.RetryUtil.AllExnRetryable
import com.digitalasset.canton.util.retry.Success
import com.digitalasset.canton.util.{Thereafter, retry}
import com.digitalasset.canton.version.HandshakeErrors.DeprecatedProtocolVersion
import com.digitalasset.canton.{DomainAlias, ProtoDeserializationError}
import io.grpc.ClientInterceptors

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContextExecutor, Future}

class GrpcSequencerConnectClient(
    sequencerConnection: GrpcSequencerConnection,
    val timeouts: ProcessingTimeout,
    traceContextPropagation: TracingConfig.Propagation,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContextExecutor)
    extends SequencerConnectClient
    with NamedLogging
    with FlagCloseable {

  private val clientChannelBuilder = ClientChannelBuilder(loggerFactory)
  private val builder =
    sequencerConnection.mkChannelBuilder(clientChannelBuilder, traceContextPropagation)

  override def getDomainClientBootstrapInfo(
      domainAlias: DomainAlias
  )(implicit traceContext: TraceContext): EitherT[Future, Error, DomainClientBootstrapInfo] =
    for {
      response <- CantonGrpcUtil
        .sendSingleGrpcRequest(
          serverName = domainAlias.unwrap,
          requestDescription = "get domain id and sequencer id",
          channel = builder.build(),
          stubFactory = v0.SequencerConnectServiceGrpc.stub,
          timeout = timeouts.network.unwrap,
          logger = logger,
          logPolicy = CantonGrpcUtil.silentLogPolicy,
          retryPolicy = CantonGrpcUtil.RetryPolicy.noRetry,
        )(_.getDomainId(v0.SequencerConnect.GetDomainId.Request()))
        .leftMap(err => Error.Transport(err.toString))

      domainId = DomainId
        .fromProtoPrimitive(response.domainId, "domainId")
        .leftMap[Error](err => Error.DeserializationFailure(err.toString))

      domainId <- EitherT.fromEither[Future](domainId)

      sequencerId =
        if (response.sequencerId.isEmpty) Right(SequencerId(domainId.unwrap))
        else
          SequencerId
            .fromProtoPrimitive(response.sequencerId, "sequencerId")
            .leftMap[Error](err => Error.DeserializationFailure(err.toString))

      sequencerId <- EitherT.fromEither[Future](sequencerId)
    } yield DomainClientBootstrapInfo(domainId, sequencerId)

  override def getDomainParameters(
      domainAlias: DomainAlias
  )(implicit traceContext: TraceContext): EitherT[Future, Error, StaticDomainParameters] = for {
    responseP <- CantonGrpcUtil
      .sendSingleGrpcRequest(
        serverName = domainAlias.unwrap,
        requestDescription = "get domain parameters",
        channel = builder.build(),
        stubFactory = v0.SequencerConnectServiceGrpc.stub,
        timeout = timeouts.network.unwrap,
        logger = logger,
        logPolicy = CantonGrpcUtil.silentLogPolicy,
        retryPolicy = CantonGrpcUtil.RetryPolicy.noRetry,
      )(_.getDomainParameters(v0.SequencerConnect.GetDomainParameters.Request()))
      .leftMap(err => Error.Transport(err.toString))

    domainParametersE = GrpcSequencerConnectClient
      .toStaticDomainParameters(responseP)
      .leftMap[Error](err => Error.DeserializationFailure(err.toString))

    domainParameters <- EitherT.fromEither[Future](domainParametersE)

  } yield domainParameters

  override def handshake(
      domainAlias: DomainAlias,
      request: HandshakeRequest,
      dontWarnOnDeprecatedPV: Boolean,
  )(implicit
      traceContext: TraceContext
  ): EitherT[Future, Error, HandshakeResponse] =
    for {
      responseP <- CantonGrpcUtil
        .sendSingleGrpcRequest(
          serverName = domainAlias.unwrap,
          requestDescription = "handshake",
          channel = builder.build(),
          stubFactory = v0.SequencerConnectServiceGrpc.stub,
          timeout = timeouts.network.unwrap,
          logger = logger,
          logPolicy = CantonGrpcUtil.silentLogPolicy,
          retryPolicy = CantonGrpcUtil.RetryPolicy.noRetry,
        )(_.handshake(request.toProtoV0))
        .leftMap(err => Error.Transport(err.toString))

      handshakeResponse <- EitherT
        .fromEither[Future](HandshakeResponse.fromProtoV0(responseP))
        .leftMap[Error](err => Error.DeserializationFailure(err.toString))
      _ = if (handshakeResponse.serverProtocolVersion.isDeprecated && !dontWarnOnDeprecatedPV)
        DeprecatedProtocolVersion.WarnSequencerClient(
          domainAlias,
          handshakeResponse.serverProtocolVersion,
        )
    } yield handshakeResponse

  override def getAgreement(
      domainId: DomainId
  )(implicit traceContext: TraceContext): EitherT[Future, Error, Option[ServiceAgreement]] = for {
    response <- CantonGrpcUtil
      .sendSingleGrpcRequest(
        domainId.toString,
        "getting service agreement from remote domain",
        channel = builder.build(),
        stubFactory = v0.SequencerConnectServiceGrpc.stub,
        timeout = timeouts.network.unwrap,
        logger = logger,
        logPolicy = CantonGrpcUtil.silentLogPolicy,
        retryPolicy = CantonGrpcUtil.RetryPolicy.noRetry,
      )(_.getServiceAgreement(GetServiceAgreementRequest()))
      .leftMap(e => Error.Transport(e.toString))
    optAgreement <- EitherT
      .fromEither[Future](
        response.agreement
          .traverse(ag =>
            ServiceAgreement.fromProtoV0(ag).leftMap(e => Error.DeserializationFailure(e.toString))
          )
      )
      .leftWiden[Error]
  } yield optAgreement

  def isActive(participantId: ParticipantId, waitForActive: Boolean)(implicit
      traceContext: TraceContext
  ): EitherT[Future, Error, Boolean] = {
    import Thereafter.syntax.*
    val interceptor = new SequencerConnectClientInterceptor(participantId, loggerFactory)

    val channel = builder.build()
    val closeableChannel = Lifecycle.toCloseableChannel(channel, logger, "sendSingleGrpcRequest")
    val interceptedChannel = ClientInterceptors.intercept(closeableChannel.channel, interceptor)
    val service = v0.SequencerConnectServiceGrpc.stub(interceptedChannel)

    // retry in case of failure. Also if waitForActive is true, retry if response is negative
    implicit val success: Success[Either[Error, Boolean]] =
      retry.Success({
        case Left(_) => false
        case Right(value) => value || !waitForActive
      })

    def verifyActive(): Future[Either[Error, Boolean]] =
      service.verifyActive(VerifyActive.Request()).map(handleVerifyActiveResponse)

    // The verify active check within the sequencer connect service uses the sequenced topology state.
    // The retry logic was previously used as the "auto approve identity registration strategy"
    // did not wait for the registration to complete before signalling success. While this has
    // been improved in the meantime, we've left this retry logic in the code in order to support
    // future, less synchronous implementations.
    val interval = 500.millis
    val maxRetries: Int = timeouts.verifyActive.retries(interval)
    EitherT(
      retry
        .Pause(logger, this, maxRetries, interval, "verify active")
        .apply(verifyActive(), AllExnRetryable)
    ).thereafter(_ => closeableChannel.close())
  }
}

object GrpcSequencerConnectClient {
  private def toStaticDomainParameters(
      response: v0.SequencerConnect.GetDomainParameters.Response
  ): ParsingResult[StaticDomainParameters] = response.parameters match {
    case Parameters.Empty =>
      Left(ProtoDeserializationError.FieldNotSet("GetDomainParameters.parameters"))
    case Parameters.ParametersV0(parametersV0) => StaticDomainParameters.fromProtoV0(parametersV0)
    case Parameters.ParametersV1(parametersV1) => StaticDomainParameters.fromProtoV1(parametersV1)
  }
}
