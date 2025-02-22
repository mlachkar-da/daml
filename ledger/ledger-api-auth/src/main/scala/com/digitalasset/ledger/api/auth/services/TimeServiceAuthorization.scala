// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.api.auth.services

import com.daml.ledger.api.auth.Authorizer
import com.daml.ledger.api.v1.testing.time_service.TimeServiceGrpc.TimeService
import com.daml.ledger.api.v1.testing.time_service._
import com.google.protobuf.empty.Empty
import io.grpc.ServerServiceDefinition
import io.grpc.stub.StreamObserver

import scala.concurrent.{ExecutionContext, Future}

private[daml] final class TimeServiceAuthorization(
    protected val service: TimeService with AutoCloseable,
    private val authorizer: Authorizer,
)(implicit executionContext: ExecutionContext)
    extends TimeService
    with ProxyCloseable
    with GrpcApiService {

  override def getTime(
      request: GetTimeRequest,
      responseObserver: StreamObserver[GetTimeResponse],
  ): Unit =
    authorizer.requirePublicClaimsOnStream(service.getTime)(request, responseObserver)

  override def setTime(request: SetTimeRequest): Future[Empty] =
    authorizer.requireAdminClaims(service.setTime)(request)

  override def bindService(): ServerServiceDefinition =
    TimeServiceGrpc.bindService(this, executionContext)

  override def close(): Unit = service.close()
}
