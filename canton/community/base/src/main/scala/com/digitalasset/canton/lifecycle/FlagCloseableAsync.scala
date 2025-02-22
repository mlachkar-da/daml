// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.lifecycle

import com.digitalasset.canton.DiscardOps
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.util.FutureUtil

import scala.concurrent.duration.Duration
import scala.concurrent.{Future, TimeoutException}

/** AutoCloseableAsync eases the proper closing of futures.
  */
trait FlagCloseableAsync extends FlagCloseable {

  /** closeAsync asynchronously releases resources held by a future
    * @return an ordered sequence of async and sync closeables with async closeables made up of future and timeout
    */
  protected def closeAsync(): Seq[AsyncOrSyncCloseable]

  final override def onClosed(): Unit = Lifecycle.close(closeAsync(): _*)(logger)
}

trait AsyncOrSyncCloseable extends AutoCloseable

class AsyncCloseable private (
    name: String,
    closeFuture: () => Future[_],
    timeout: Duration,
    onTimeout: TimeoutException => Unit,
)(implicit
    loggingContext: ErrorLoggingContext
) extends AsyncOrSyncCloseable {
  override def close(): Unit = FutureUtil
    .noisyAwaitResult(closeFuture(), s"closing $name", timeout, onTimeout = onTimeout)
    .discard

  override def toString: String = s"AsyncCloseable(name=${name})"
}
object AsyncCloseable {
  def apply(
      name: String,
      closeFuture: => Future[_],
      timeout: Duration,
      onTimeout: TimeoutException => Unit = _ => (),
  )(implicit
      loggingContext: ErrorLoggingContext
  ): AsyncCloseable =
    new AsyncCloseable(name, () => closeFuture, timeout, onTimeout)
}

class SyncCloseable private (name: String, sync: () => Unit) extends AsyncOrSyncCloseable {
  override def close(): Unit = sync()
  override def toString: String = s"SyncCloseable(name=${name})"
}
object SyncCloseable {
  def apply(name: String, sync: => Unit): SyncCloseable =
    new SyncCloseable(name, () => sync)
}
