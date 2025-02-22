// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.apiserver.services

import com.daml.daml_lf_dev.DamlLf.{Archive, HashFunction}
import com.daml.error.ContextualizedErrorLogger
import com.daml.ledger.api.v1.package_service.PackageServiceGrpc.PackageService
import com.daml.ledger.api.v1.package_service.{HashFunction as APIHashFunction, *}
import com.daml.lf.data.Ref
import com.daml.tracing.Telemetry
import com.digitalasset.canton.ledger.api.ValidationLogger
import com.digitalasset.canton.ledger.api.domain.LedgerId
import com.digitalasset.canton.ledger.api.grpc.{GrpcApiService, GrpcPackageService}
import com.digitalasset.canton.ledger.api.validation.ValidationErrors
import com.digitalasset.canton.ledger.error.groups.RequestValidationErrors
import com.digitalasset.canton.ledger.participant.state.index.v2.IndexPackagesService
import com.digitalasset.canton.logging.LoggingContextUtil.createLoggingContext
import com.digitalasset.canton.logging.LoggingContextWithTrace.{
  implicitExtractTraceContext,
  withEnrichedLoggingContext,
}
import com.digitalasset.canton.logging.TracedLoggerOps.TracedLoggerOps
import com.digitalasset.canton.logging.{
  ErrorLoggingContext,
  LoggingContextWithTrace,
  NamedLoggerFactory,
  NamedLogging,
}
import io.grpc.{BindableService, ServerServiceDefinition}

import scala.concurrent.{ExecutionContext, Future}

private[apiserver] final class ApiPackageService private (
    backend: IndexPackagesService,
    telemetry: Telemetry,
    val loggerFactory: NamedLoggerFactory,
)(implicit executionContext: ExecutionContext)
    extends PackageService
    with GrpcApiService
    with NamedLogging {

  private implicit val loggingContext = createLoggingContext(loggerFactory)(identity)

  override def bindService(): ServerServiceDefinition =
    PackageServiceGrpc.bindService(this, executionContext)

  override def close(): Unit = ()

  override def listPackages(request: ListPackagesRequest): Future[ListPackagesResponse] = {
    implicit val loggingContextWithTrace = LoggingContextWithTrace(loggerFactory, telemetry)
    logger.info(s"Received request to list packages: $request.")
    backend
      .listLfPackages()
      .map(p => ListPackagesResponse(p.keys.toSeq))
      .andThen(logger.logErrorsOnCall[ListPackagesResponse])
  }

  override def getPackage(request: GetPackageRequest): Future[GetPackageResponse] = {

    withEnrichedLoggingContext(telemetry)(
      logging.packageId(request.packageId)
    ) { implicit loggingContext =>
      logger.info(
        s"Received request for a package: $request, ${loggingContext.serializeFiltered("packageId")}."
      )
      withValidatedPackageId(request.packageId, request) { packageId =>
        backend
          .getLfArchive(packageId)
          .flatMap {
            case None =>
              Future.failed[GetPackageResponse](
                RequestValidationErrors.NotFound.Package
                  .Reject(packageId = packageId)(
                    createContextualizedErrorLogger
                  )
                  .asGrpcError
              )
            case Some(archive) => Future.successful(toGetPackageResponse(archive))
          }
          .andThen(logger.logErrorsOnCall[GetPackageResponse])
      }
    }
  }

  override def getPackageStatus(
      request: GetPackageStatusRequest
  ): Future[GetPackageStatusResponse] =
    withEnrichedLoggingContext(telemetry)(
      logging.packageId(request.packageId)
    ) { implicit loggingContext =>
      logger.info(
        s"Received request for a package status: $request, ${loggingContext.serializeFiltered("packageId")}."
      )
      withValidatedPackageId(request.packageId, request) { packageId =>
        backend
          .listLfPackages()
          .map { packages =>
            val result = if (packages.contains(packageId)) {
              PackageStatus.REGISTERED
            } else {
              PackageStatus.UNKNOWN
            }
            GetPackageStatusResponse(result)
          }
          .andThen(logger.logErrorsOnCall[GetPackageStatusResponse])
      }
    }

  private def withValidatedPackageId[T, R](packageId: String, request: R)(
      block: Ref.PackageId => Future[T]
  )(implicit loggingContext: LoggingContextWithTrace): Future[T] =
    Ref.PackageId
      .fromString(packageId)
      .fold(
        errorMessage =>
          Future.failed[T](
            ValidationLogger.logFailureWithTrace(
              logger,
              request,
              ValidationErrors
                .invalidArgument(s"Invalid package id: $errorMessage")(
                  createContextualizedErrorLogger
                ),
            )
          ),
        packageId => block(packageId),
      )

  private def toGetPackageResponse(archive: Archive): GetPackageResponse = {
    val hashFunction = archive.getHashFunction match {
      case HashFunction.SHA256 => APIHashFunction.SHA256
      case _ => APIHashFunction.Unrecognized(-1)
    }
    GetPackageResponse(
      hashFunction = hashFunction,
      archivePayload = archive.getPayload,
      hash = archive.getHash,
    )
  }

  private def createContextualizedErrorLogger(implicit
      loggingContext: LoggingContextWithTrace
  ): ContextualizedErrorLogger =
    ErrorLoggingContext(logger, loggingContext)
}

private[platform] object ApiPackageService {
  def create(
      ledgerId: LedgerId,
      backend: IndexPackagesService,
      telemetry: Telemetry,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      executionContext: ExecutionContext
  ): PackageService with GrpcApiService = {
    val service = new ApiPackageService(
      backend = backend,
      telemetry = telemetry,
      loggerFactory = loggerFactory,
    )
    new GrpcPackageService(
      service = service,
      ledgerId = ledgerId,
      telemetry = telemetry,
      loggerFactory = loggerFactory,
    ) with BindableService {
      override def bindService(): ServerServiceDefinition =
        PackageServiceGrpc.bindService(this, executionContext)
    }
  }
}
