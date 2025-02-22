// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.ledger.api.auth

import akka.actor.{Cancellable, Scheduler}
import com.daml.clock.AdjustableClock
import com.daml.error.ErrorsAssertions
import com.daml.jwt.JwtTimestampLeeway
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.ledger.api.auth.AuthorizationError.Expired
import com.digitalasset.canton.ledger.error.groups.AuthorizationChecksErrors
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.platform.localstore.api.UserManagementStore
import io.grpc.StatusRuntimeException
import io.grpc.stub.ServerCallStreamObserver
import org.mockito.{ArgumentCaptor, ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{Clock, Duration, Instant, ZoneId}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

class OngoingAuthorizationObserverSpec
    extends AsyncFlatSpec
    with BaseTest
    with Matchers
    with Eventually
    with IntegrationPatience
    with MockitoSugar
    with ArgumentMatchersSugar
    with ErrorsAssertions {

  private implicit val errorLogger = ErrorLoggingContext(
    loggerFactory.getTracedLogger(getClass),
    loggerFactory.properties,
    traceContext,
  )

  it should "signal onError aborting the stream when user rights state hasn't been refreshed in a timely manner" in {
    val clock = AdjustableClock(
      baseClock = Clock.fixed(Instant.now(), ZoneId.systemDefault()),
      offset = Duration.ZERO,
    )
    val delegate = mock[ServerCallStreamObserver[Int]]
    val mockScheduler = mock[Scheduler]
    // Set scheduler to do nothing
    val cancellableMock = mock[Cancellable]
    when(
      mockScheduler.scheduleWithFixedDelay(any[FiniteDuration], any[FiniteDuration])(any[Runnable])(
        any[ExecutionContext]
      )
    ).thenReturn(cancellableMock)
    val userRightsCheckIntervalInSeconds = 10
    val tested = OngoingAuthorizationObserver(
      observer = delegate,
      originalClaims = ClaimSet.Claims.Empty.copy(
        resolvedFromUser = true,
        applicationId = Some("some_user_id"),
      ),
      nowF = () => clock.instant,
      userManagementStore = mock[UserManagementStore],
      // This is also the user rights state refresh timeout
      userRightsCheckIntervalInSeconds = userRightsCheckIntervalInSeconds,
      akkaScheduler = mockScheduler,
      loggerFactory = loggerFactory,
    )(executionContext, traceContext)

    // After 20 seconds pass we expect onError to be called due to lack of user rights state refresh task inactivity
    tested.onNext(1)
    clock.fastForward(Duration.ofSeconds(2.toLong * userRightsCheckIntervalInSeconds - 1))
    tested.onNext(2)
    clock.fastForward(Duration.ofSeconds(2))
    // Next onNext detects the user rights state refresh task inactivity
    tested.onNext(3)

    val captor = ArgumentCaptor.forClass(classOf[StatusRuntimeException])
    val order = inOrder(delegate)
    order.verify(delegate, times(1)).onNext(1)
    order.verify(delegate, times(1)).onNext(2)
    order.verify(delegate, times(1)).onError(captor.capture())
    order.verifyNoMoreInteractions()
    // Scheduled task is cancelled
    verify(cancellableMock, times(1)).cancel()
    assertError(
      actual = captor.getValue,
      expected = AuthorizationChecksErrors.StaleUserManagementBasedStreamClaims
        .Reject()
        .asGrpcError,
    )

    // onError has already been called by tested implementation so subsequent onNext, onError and onComplete
    // must not be forwarded to the delegate observer
    tested.onNext(4)
    tested.onError(new RuntimeException)
    tested.onCompleted()
    verifyNoMoreInteractions(delegate)

    succeed
  }

  it should "not abort the stream when the token has expired but adding leeway overlaps verification time" in {
    val clock = AdjustableClock(
      baseClock = Clock.fixed(Instant.now(), ZoneId.systemDefault()),
      offset = Duration.ZERO,
    )
    val delegate = mock[ServerCallStreamObserver[Int]]
    val mockScheduler = mock[Scheduler]
    // Set scheduler to do nothing
    val cancellableMock = mock[Cancellable]
    when(
      mockScheduler.scheduleWithFixedDelay(any[FiniteDuration], any[FiniteDuration])(any[Runnable])(
        any[ExecutionContext]
      )
    ).thenReturn(cancellableMock)
    val tested = OngoingAuthorizationObserver(
      observer = delegate,
      originalClaims = ClaimSet.Claims.Empty.copy(
        resolvedFromUser = true,
        applicationId = Some("some_user_id"),
        expiration = Some(clock.instant.plusSeconds(1)),
      ),
      nowF = () => clock.instant,
      userManagementStore = mock[UserManagementStore],
      userRightsCheckIntervalInSeconds = 10,
      akkaScheduler = mockScheduler,
      // defined default leeway of 1 second for authorization
      jwtTimestampLeeway = Some(JwtTimestampLeeway(default = Some(1))),
      loggerFactory = loggerFactory,
    )(executionContext, traceContext)

    tested.onNext(1)
    clock.fastForward(Duration.ofSeconds(1))
    tested.onNext(2)

    val order = inOrder(delegate)
    order.verify(delegate, times(1)).onNext(1)
    order.verify(delegate, times(1)).onNext(2)
    order.verifyNoMoreInteractions()

    succeed
  }

  it should "abort the stream when the token has expired" in {
    val clock = AdjustableClock(
      baseClock = Clock.fixed(Instant.now(), ZoneId.systemDefault()),
      offset = Duration.ZERO,
    )
    val delegate = mock[ServerCallStreamObserver[Int]]
    val mockScheduler = mock[Scheduler]
    // Set scheduler to do nothing
    val cancellableMock = mock[Cancellable]
    when(
      mockScheduler.scheduleWithFixedDelay(any[FiniteDuration], any[FiniteDuration])(any[Runnable])(
        any[ExecutionContext]
      )
    ).thenReturn(cancellableMock)
    val expiration = clock.instant.plusSeconds(1)
    val tested = OngoingAuthorizationObserver(
      observer = delegate,
      originalClaims = ClaimSet.Claims.Empty.copy(
        resolvedFromUser = true,
        applicationId = Some("some_user_id"),
        // the expiration claim will be invalid in the next second
        expiration = Some(expiration),
      ),
      nowF = () => clock.instant,
      userManagementStore = mock[UserManagementStore],
      userRightsCheckIntervalInSeconds = 10,
      akkaScheduler = mockScheduler,
      jwtTimestampLeeway = Some(JwtTimestampLeeway(default = Some(1))),
      loggerFactory = loggerFactory,
    )(executionContext, traceContext)

    // After 2 seconds have passed we expect onError to be called due to invalid expiration claim
    tested.onNext(1)
    clock.fastForward(Duration.ofSeconds(2))
    // Next onNext detects the invalid expiration claim
    tested.onNext(2)

    val captor = ArgumentCaptor.forClass(classOf[StatusRuntimeException])
    val order = inOrder(delegate)
    order.verify(delegate, times(1)).onNext(1)
    order.verify(delegate, times(1)).onError(captor.capture())
    order.verifyNoMoreInteractions()

    loggerFactory.assertLogs(
      within = {
        // Scheduled task is cancelled
        verify(cancellableMock, times(1)).cancel()
        assertError(
          actual = captor.getValue,
          expected = AuthorizationChecksErrors.PermissionDenied
            .Reject(Expired(expiration, clock.instant).reason)
            .asGrpcError,
        )
      },
      assertions =
        _.warningMessage should include("PERMISSION_DENIED(7,0): Claims were valid until "),
    )

    // onError has already been called by tested implementation so subsequent onNext, onError and onComplete
    // must not be forwarded to the delegate observer
    tested.onNext(3)
    tested.onError(new RuntimeException)
    tested.onCompleted()
    verifyNoMoreInteractions(delegate)

    succeed
  }

}
