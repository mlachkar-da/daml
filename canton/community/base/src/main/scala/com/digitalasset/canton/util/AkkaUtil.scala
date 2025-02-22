// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.util

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, FlowOps, FlowOpsMat, Keep, RunnableGraph, Source}
import akka.stream.stage.{GraphStageLogic, GraphStageWithMaterializedValue, InHandler, OutHandler}
import akka.stream.{
  ActorAttributes,
  Attributes,
  FlowShape,
  Inlet,
  KillSwitch,
  KillSwitches,
  Materializer,
  Outlet,
  QueueCompletionResult,
  QueueOfferResult,
  Supervision,
  UniqueKillSwitch,
}
import akka.{Done, NotUsed}
import cats.{Applicative, Eval, Functor, Traverse}
import com.daml.grpc.adapter.{AkkaExecutionSequencerPool, ExecutionSequencerFactory}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.DiscardOps
import com.digitalasset.canton.concurrent.{DirectExecutionContext, Threading}
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.lifecycle.UnlessShutdown.{AbortedDueToShutdown, Outcome}
import com.digitalasset.canton.lifecycle.{FutureUnlessShutdown, UnlessShutdown}
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.logging.{HasLoggerName, NamedLoggingContext}
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.util.Thereafter.syntax.*
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.language.implicitConversions
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

object AkkaUtil extends HasLoggerName {

  /** Utility function to run the graph supervised and stop on an unhandled exception.
    *
    * By default, an Akka flow will discard exceptions. Use this method to avoid discarding exceptions.
    */
  def runSupervised[T](
      reporter: Throwable => Unit,
      graph: RunnableGraph[T],
      debugLogging: Boolean = false,
  )(implicit
      mat: Materializer
  ): T = {
    val tmp = graph
      .addAttributes(ActorAttributes.withSupervisionStrategy { ex =>
        reporter(ex)
        Supervision.Stop
      })
    (if (debugLogging)
       tmp.addAttributes(ActorAttributes.debugLogging(true))
     else tmp)
      .run()
  }

  /** Create an Actor system using the existing execution context `ec`
    */
  def createActorSystem(namePrefix: String)(implicit ec: ExecutionContext): ActorSystem =
    ActorSystem(
      namePrefix + "-actor-system",
      defaultExecutionContext = Some(ec),
      config = Some(ConfigFactory.load),
    )

  /** Create a new execution sequencer factory (mainly used to create a ledger client) with the existing actor system `actorSystem`
    */
  def createExecutionSequencerFactory(namePrefix: String, logger: Logger)(implicit
      actorSystem: ActorSystem
  ): ExecutionSequencerFactory =
    new AkkaExecutionSequencerPool(
      namePrefix + "-execution-sequencer",
      actorCount = Threading.detectNumberOfThreads(logger),
    )

  /** Remembers the last `memory` many elements that have already been emitted previously.
    * Passes those remembered elements downstream with each new element.
    * The current element is the [[com.daml.nonempty.NonEmptyCollInstances.NEPreservingOps.last1]]
    * of the sequence.
    *
    * [[remember]] differs from [[akka.stream.scaladsl.FlowOps.sliding]] in
    * that [[remember]] emits elements immediately when the given source emits,
    * whereas [[akka.stream.scaladsl.FlowOps.sliding]] only after the source has emitted enough elements to fill the window.
    */
  def remember[A, Mat](
      graph: FlowOps[A, Mat],
      memory: NonNegativeInt,
  ): graph.Repr[NonEmpty[Seq[A]]] = {
    // Prepend window many None to the given source
    // so that sliding starts emitting upon the first element received
    graph
      .map(Some(_))
      .prepend(Source(Seq.fill(memory.value)(None)))
      .sliding(memory.value + 1)
      .mapConcat { noneOrElems =>
        // dropWhile is enough because None can only appear in the prefix
        val elems = noneOrElems
          .dropWhile(_.isEmpty)
          .map(_.getOrElse(throw new NoSuchElementException("Some did not contain a value")))
        // Do not emit anything if `noneOrElems` is all Nones,
        // because then the source completed before emitting any elements
        NonEmpty.from(elems)
      }
  }

  /** A version of [[akka.stream.scaladsl.FlowOps.mapAsync]] that additionally allows to pass state of type `S` between
    * every subsequent element. Unlike [[akka.stream.scaladsl.FlowOps.statefulMapConcat]], the state is passed explicitly.
    * Must not be run with supervision strategies [[akka.stream.Supervision.Restart]] nor [[akka.stream.Supervision.Resume]]
    */
  def statefulMapAsync[Out, Mat, S, T](graph: FlowOps[Out, Mat], initial: S)(
      f: (S, Out) => Future[(S, T)]
  )(implicit loggingContext: NamedLoggingContext): graph.Repr[T] = {
    val directExecutionContext = DirectExecutionContext(loggingContext.tracedLogger)
    graph
      .scanAsync((initial, Option.empty[T])) { case ((state, _), next) =>
        f(state, next)
          .map { case (newState, out) => (newState, Some(out)) }(directExecutionContext)
      }
      .drop(1) // The first element is `(initial, empty)`, which we want to drop
      .map(
        _._2.getOrElse(
          ErrorUtil.internalError(new NoSuchElementException("scanAsync did not return an element"))
        )
      )
  }

  /** Version of [[akka.stream.scaladsl.FlowOps.mapAsync]] for a [[com.digitalasset.canton.lifecycle.FutureUnlessShutdown]].
    * If `f` returns [[com.digitalasset.canton.lifecycle.UnlessShutdown.AbortedDueToShutdown]] on one element of
    * `source`, then the returned source returns [[com.digitalasset.canton.lifecycle.UnlessShutdown.AbortedDueToShutdown]]
    * for all subsequent elements as well.
    *
    * If `parallelism` is one, ensures that `f` is called sequentially for each element of `source`
    * and that `f` is not invoked on later stream elements if `f` returns
    * [[com.digitalasset.canton.lifecycle.UnlessShutdown.AbortedDueToShutdown]] for an earlier element.
    * If `parellelism` is greater than one, `f` may be invoked on later stream elements
    * even though an earlier invocation results in `f` returning
    * [[com.digitalasset.canton.lifecycle.UnlessShutdown.AbortedDueToShutdown]].
    *
    * '''Emits when''' the Future returned by the provided function finishes for the next element in sequence
    *
    * '''Backpressures when''' the number of futures reaches the configured parallelism and the downstream
    * backpressures or the first future is not completed
    *
    * '''Completes when''' upstream completes and all futures have been completed and all elements have been emitted,
    * including those for which the future did not run due to earlier [[com.digitalasset.canton.lifecycle.UnlessShutdown.AbortedDueToShutdown]]s.
    *
    * '''Cancels when''' downstream cancels
    *
    * @param parallelism The parallelism level. Must be at least 1.
    * @throws java.lang.IllegalArgumentException if `parallelism` is not positive.
    */
  def mapAsyncUS[A, Mat, B](graph: FlowOps[A, Mat], parallelism: Int)(
      f: A => FutureUnlessShutdown[B]
  )(implicit loggingContext: NamedLoggingContext): graph.Repr[UnlessShutdown[B]] = {
    require(parallelism > 0, "Parallelism must be positive")
    // If parallelism is 1, then the caller expects that the futures run in sequential order,
    // so if one of them aborts due to shutdown we must not run the subsequent ones.
    // For parallelism > 1, we do not have to stop immediately, as there is always a possible execution
    // where the future may have been started before the first one aborted.
    // So we just need to throw away the results of the futures and convert them into aborts.
    if (parallelism == 1) {
      val directExecutionContext = DirectExecutionContext(loggingContext.tracedLogger)
      statefulMapAsync(graph, initial = false) { (aborted, next) =>
        if (aborted) Future.successful(true -> AbortedDueToShutdown)
        else f(next).unwrap.map(us => !us.isOutcome -> us)(directExecutionContext)
      }
    } else {
      val discardedInitial: UnlessShutdown[B] = AbortedDueToShutdown
      // Mutable reference to short-circuit one we've observed the first aborted due to shutdown.
      val abortedFlag = new AtomicBoolean(false)
      graph
        .mapAsync(parallelism)(elem =>
          if (abortedFlag.get()) Future.successful(AbortedDueToShutdown)
          else f(elem).unwrap
        )
        .scan((false, discardedInitial)) { case ((aborted, _), next) =>
          if (aborted) (true, AbortedDueToShutdown)
          else {
            val abort = !next.isOutcome
            if (abort) abortedFlag.set(true)
            (abort, next)
          }
        }
        .drop(1) // The first element is `(false, discardedInitial)`, which we want to drop
        .map(_._2)
    }
  }

  /** Version of [[mapAsyncUS]] that discards the [[com.digitalasset.canton.lifecycle.UnlessShutdown.AbortedDueToShutdown]]s.
    *
    * '''Completes when''' upstream completes and all futures have been completed and all elements have been emitted.
    */
  def mapAsyncAndDrainUS[A, Mat, B](graph: FlowOps[A, Mat], parallelism: Int)(
      f: A => FutureUnlessShutdown[B]
  )(implicit loggingContext: NamedLoggingContext): graph.Repr[B] = {
    mapAsyncUS(graph, parallelism)(f)
      // Important to use `collect` instead of `takeWhile` here
      // so that the return source completes only after all `source`'s elements have been consumed.
      // TODO(#13789) Should we cancel/pull a kill switch to signal upstream that no more elements are needed?
      .collect { case Outcome(x) => x }
  }

  /** Combines [[mapAsyncUS]] with [[statefulMapAsync]]. */
  def statefulMapAsyncUS[Out, Mat, S, T](graph: FlowOps[Out, Mat], initial: S)(
      f: (S, Out) => FutureUnlessShutdown[(S, T)]
  )(implicit loggingContext: NamedLoggingContext): graph.Repr[UnlessShutdown[T]] = {
    implicit val directExecutionContext: ExecutionContext =
      DirectExecutionContext(loggingContext.tracedLogger)
    statefulMapAsync(graph, initial = Option(initial)) {
      case (Some(s), next) =>
        f(s, next).unwrap.map {
          case AbortedDueToShutdown => None -> AbortedDueToShutdown
          case Outcome((nextS, t)) => Some(nextS) -> Outcome(t)
        }
      case (None, _next) =>
        Future.successful(None -> AbortedDueToShutdown)
    }
  }

  /** Combines two kill switches into one */
  class CombinedKillSwitch(private val killSwitch1: KillSwitch, private val killSwitch2: KillSwitch)
      extends KillSwitch {
    override def shutdown(): Unit = {
      killSwitch1.shutdown()
      killSwitch2.shutdown()
    }

    override def abort(ex: Throwable): Unit = {
      killSwitch1.abort(ex)
      killSwitch2.abort(ex)
    }
  }

  /** Defines the policy when [[restartSource]] should restart the source, and the state from which the source should be restarted from. */
  trait RetrySourcePolicy[S, -A] {

    /** Determines whether the source should be restarted, and if so (([[scala.Some$]])),
      * the backoff duration and the new state to restart from.
      * Called after the current source has terminated normally or with an error.
      *
      * @param lastState The state that was used to create the current source
      * @param lastEmittedElement The last element emitted by the current source and passed downstream.
      *                           Downstream obviously need not yet have fully processed the element though.
      *                           [[scala.None$]] if the current source did not emit anything,
      *                           even if previous sources have emitted elements.
      * @param lastFailure The error the current source failed with, if any.
      */
    def shouldRetry(
        lastState: S,
        lastEmittedElement: Option[A],
        lastFailure: Option[Throwable],
    ): Option[(FiniteDuration, S)]
  }

  object RetrySourcePolicy {
    private val NEVER: RetrySourcePolicy[Any, Any] = new RetrySourcePolicy[Any, Any] {
      override def shouldRetry(
          lastState: Any,
          lastEmittedElement: Option[Any],
          lastFailure: Option[Throwable],
      ): Option[Nothing] = None
    }
    @SuppressWarnings(Array("org.wartremover.wart.AsInstanceOf"))
    def never[S, A]: RetrySourcePolicy[S, A] = NEVER.asInstanceOf[RetrySourcePolicy[S, A]]
  }

  /** Creates a source from `mkSource` from the `initial` state.
    * Whenever this source terminates, `policy` determines whether another source shall be constructed (after a given delay) from a possibly new state.
    * The returned source concatenates the output of all the constructed sources in order.
    * At most one constructed source is active at any given point in time.
    *
    * Failures in the constructed sources are passed to the `policy`, but do not make it downstream.
    * The `policy` is responsible for properly logging these errors if necessary.
    *
    * @return The concatenation of all constructed sources.
    *         This source is NOT a blueprint and MUST therefore be materialized at most once.
    *         Its materialized value provides a kill switch to stop retrying.
    *         Only the [[akka.stream.KillSwitch.shutdown]] method should be used;
    *         The switch does not short-circuit the already constructed sources though.
    *         synchronization may not work correctly with [[akka.stream.KillSwitch.abort]].
    *         Downstream should not cancel; use the kill switch instead.
    *
    *         The materialized [[scala.concurrent.Future]] can be used to synchronize on the computations for restarts:
    *         if the source is stopped with the kill switch, the future completes after the computations have finished.
    */
  def restartSource[S: Pretty, A](
      name: String,
      initial: S,
      mkSource: S => Source[A, (KillSwitch, Future[Done])],
      policy: RetrySourcePolicy[S, A],
  )(implicit
      loggingContext: NamedLoggingContext,
      materializer: Materializer,
  ): Source[WithKillSwitch[A], (KillSwitch, Future[Done])] = {
    val directExecutionContext = DirectExecutionContext(loggingContext.tracedLogger)

    // Use immediate acknowledgements and buffer size 1 to minimize the risk that
    // several materializations of the returned source concurrently restart stuff.
    val (boundedSourceQueue, source) = Source.queue[S](bufferSize = 1).preMaterialize()
    val flushFuture = new FlushFuture(s"RestartSource $name", loggingContext.loggerFactory)

    def idempotentComplete(): Unit =
      try {
        boundedSourceQueue.complete()
      } catch {
        case _: IllegalStateException =>
      }

    trait KillSwitchForRestartSource extends KillSwitch {
      type Handle

      /** Register a function to be executed when the kill switch is pulled.
        *
        * @return A handle with which the function can be removed again using [[removeOnClose]].
        */
      def runOnClose(f: () => Unit): Handle
      def removeOnClose(handle: Handle): Unit
    }

    class KillSwitchForRestartSourceImpl extends KillSwitchForRestartSource {
      override type Handle = AnyRef

      private val isClosing = new AtomicBoolean(false)

      private val completeOnClosing: scala.collection.concurrent.Map[Any, () => Unit] =
        TrieMap.empty[Any, () => Unit]

      private def onClose(): Unit = {
        isClosing.set(true)
        completeOnClosing.foreach { case (_, f) => f() }
      }

      def runOnClose(f: () => Unit): Handle = {
        val handle = new Object()
        completeOnClosing.put(handle, f).discard[Option[() => Unit]]
        if (isClosing.get()) f()
        handle
      }

      def removeOnClose(handle: Handle): Unit =
        completeOnClosing.remove(handle).discard[Option[() => Unit]]

      override def shutdown(): Unit = {
        onClose()
        idempotentComplete()
      }

      override def abort(ex: Throwable): Unit = {
        onClose()
        try {
          boundedSourceQueue.fail(ex)
        } catch {
          case _: IllegalStateException =>
        }
      }
    }
    val killSwitchForSourceQueue: KillSwitchForRestartSource = new KillSwitchForRestartSourceImpl

    def restartFrom(nextState: S): Unit = {
      loggingContext.debug(show"(Re)Starting the source $name from state $nextState")
      boundedSourceQueue.offer(nextState) match {
        case QueueOfferResult.Enqueued =>
          loggingContext.debug(s"Restarted the source $name with state $nextState")
        case QueueOfferResult.Dropped =>
          // This should not happen
          ErrorUtil.internalError(
            new IllegalStateException(
              s"Could not restart the source $name because the state queue is full. Has the returned source been materialized multiple times?"
            )
          )
        case _: QueueCompletionResult =>
          loggingContext.debug(
            s"Not restarting $name because the restart source has already been completed"
          )
      }
    }

    // Kick it off with the initial state
    restartFrom(initial)

    source
      .flatMapConcat { state =>
        val lastObservedElem: AtomicReference[Option[A]] = new AtomicReference[Option[A]](None)
        val lastObservedError: AtomicReference[Option[Throwable]] =
          new AtomicReference[Option[Throwable]](None)

        def observeSuccess(elem: Try[A]): Try[A] = {
          elem.foreach(x => lastObservedElem.set(Some(x)))
          elem
        }
        val observeError: Throwable PartialFunction Try[A] = { case NonFatal(ex) =>
          lastObservedError.set(Some(ex))
          Failure(ex)
        }

        // flatMapConcat swallows the materialized value of the inner sources
        // So we make them accessible to the retry directly.
        def uponTermination(handleKillSwitch: killSwitchForSourceQueue.Handle, doneF: Future[Done])
            : NotUsed = {
          val afterTerminationF = doneF
            .thereafter { outcome =>
              ErrorUtil.requireArgument(
                outcome.isSuccess,
                s"RestartSource $name: recover did not catch the error $outcome",
              )
              // Deregister the inner streams kill switch upon termination to prevent memory leaks
              killSwitchForSourceQueue.removeOnClose(handleKillSwitch)
              policy.shouldRetry(state, lastObservedElem.get, lastObservedError.get) match {
                case Some((backoff, nextState)) =>
                  implicit val ec: ExecutionContext = directExecutionContext

                  val delayedPromise = Promise[UnlessShutdown[Unit]]()
                  val handleDelayedPromise = killSwitchForSourceQueue.runOnClose { () =>
                    delayedPromise.trySuccess(AbortedDueToShutdown).discard[Boolean]
                  }
                  val delayedF = DelayUtil.delay(backoff).thereafter { _ =>
                    killSwitchForSourceQueue.removeOnClose(handleDelayedPromise)
                    delayedPromise.trySuccess(Outcome(())).discard[Boolean]
                  }
                  FutureUtil.doNotAwait(
                    delayedF,
                    s"DelayUtil.delay for RestartSource $name failed",
                  )

                  val restartF = delayedPromise.future.map {
                    case AbortedDueToShutdown =>
                      loggingContext.debug(s"Not restarting $name due to shutdown")
                    case Outcome(()) => restartFrom(nextState)
                  }
                  FutureUtil.doNotAwait(
                    restartF,
                    s"Restart future for RestartSource $name failed",
                  )
                case None =>
                  loggingContext.debug(s"Not retrying $name any more. Completing the source.")
                  idempotentComplete()
              }
            }(materializer.executionContext)
            .thereafter(_.failed.foreach { ex =>
              loggingContext.error(
                s"The retry policy for RestartSource $name failed with an error. Stop retrying.",
                ex,
              )
              idempotentComplete()
            })(materializer.executionContext)
          flushFuture.addToFlushAndLogError(show"RestartSource ${name.unquoted} at state $state")(
            afterTerminationF
          )
          NotUsed
        }

        mkSource(state)
          // Register the kill switch of the new source with the kill switch of the restart source
          .mapMaterializedValue { case (killSwitch, doneF) =>
            val handle = killSwitchForSourceQueue.runOnClose(() => killSwitch.shutdown())
            // The completion future terminates with an exception when the source itself aborts with the same exception
            // Since it is the responsibility of the policy to triage such exceptions, we do not log it here.
            flushFuture.addToFlushWithoutLogging(
              show"RestartSource ${name.unquoted}: completion future of $state"
            )(doneF)
            handle
          }
          .map(Success.apply)
          // Grab any upstream errors of the current source
          // before they escape to the concatenated source and bypass the restart logic
          .recover(observeError)
          // Observe elements only after recovering from errors so that the error cannot jump over the map.
          .map(observeSuccess)
          // Do not use the `doneF` future from the source to initiate the retry
          // because it is unclear how long `doneF` will take to complete after the source has terminated.
          // Instead, decide on a retry eagerly as soon as we know that the last element of the source has been emitted
          .watchTermination()(uponTermination)
      }
      // Filter out the exceptions from the recover
      .mapConcat(_.toOption.map(WithKillSwitch(_)(killSwitchForSourceQueue)))
      .watchTermination() { case (NotUsed, doneF) =>
        val everythingTerminatedF =
          doneF.thereafterF { _ =>
            // Complete the queue of elements again, to make sure that
            // downstream cancellations do not race with a restart.
            idempotentComplete()
            flushFuture.flush()
          }(
            // The direct execution context ensures that this runs as soon as the future's promise is completed,
            // i.e., a downstream cancellation signal cannot propagate upstream while this is running.
            directExecutionContext
          )
        killSwitchForSourceQueue -> everythingTerminatedF
      }
  }

  /** Adds a [[akka.stream.KillSwitches.single]] into the stream after the given source
    * and injects the created kill switch into the stream
    */
  def withUniqueKillSwitch[A, Mat, Mat2](
      graph: FlowOpsMat[A, Mat]
  )(mat: (Mat, UniqueKillSwitch) => Mat2): graph.ReprMat[WithKillSwitch[A], Mat2] = {
    withMaterializedValueMat(new AtomicReference[UniqueKillSwitch])(graph)(Keep.both)
      .viaMat(KillSwitches.single) { case ((m, ref), killSwitch) =>
        ref.set(killSwitch)
        mat(m, killSwitch)
      }
      .map { case (a, ref) => WithKillSwitch(a)(ref.get()) }
  }

  def injectKillSwitch[A, Mat](
      graph: FlowOpsMat[A, Mat]
  )(killSwitch: Mat => KillSwitch): graph.ReprMat[WithKillSwitch[A], Mat] = {
    withMaterializedValueMat(new AtomicReference[KillSwitch])(graph)(Keep.both)
      .mapMaterializedValue { case (mat, ref) =>
        ref.set(killSwitch(mat))
        mat
      }
      .map { case (a, ref) => WithKillSwitch(a)(ref.get()) }
  }

  private[util] def withMaterializedValueMat[M, A, Mat, Mat2](create: => M)(
      graph: FlowOpsMat[A, Mat]
  )(combine: (Mat, M) => Mat2): graph.ReprMat[(A, M), Mat2] =
    graph.viaMat(new WithMaterializedValue[M, A](() => create))(combine)

  /** Creates a value upon materialization that is added to every element of the stream.
    *
    * WARNING: This flow breaks the synchronization abstraction of Akka streams,
    * as the created value is accessible from within the stream and from the outside through the materialized value.
    * Users of this flow must make sure that accessing the value is thread-safe!
    */
  private class WithMaterializedValue[M, A](create: () => M)
      extends GraphStageWithMaterializedValue[FlowShape[A, (A, M)], M] {
    private val in: Inlet[A] = Inlet[A]("withMaterializedValue.in")
    private val out: Outlet[(A, M)] = Outlet[(A, M)]("withMaterializedValue.out")
    override val shape: FlowShape[A, (A, M)] = FlowShape(in, out)

    override def initialAttributes: Attributes = Attributes.name("withMaterializedValue")

    override def createLogicAndMaterializedValue(
        inheritedAttributes: Attributes
    ): (GraphStageLogic, M) = {
      val m: M = create()
      val logic = new GraphStageLogic(shape) with InHandler with OutHandler {
        override def onPush(): Unit = push(out, grab(in) -> m)

        override def onPull(): Unit = pull(in)

        setHandlers(in, out, this)
      }
      (logic, m)
    }
  }

  /** Container class for adding a [[akka.stream.KillSwitch]] to a single value.
    * Two containers are equal if their contained values are equal.
    *
    * (Equality ignores the [[akka.stream.KillSwitch]]es because it is usually not very meaningful.
    * The [[akka.stream.KillSwitch]] is therefore in the second argument list.)
    */
  final case class WithKillSwitch[+A](private val value: A)(val killSwitch: KillSwitch) {
    def unwrap: A = value
    def map[B](f: A => B): WithKillSwitch[B] = copy(f(value))
    def traverse[F[_], B](f: A => F[B])(implicit F: Functor[F]): F[WithKillSwitch[B]] =
      F.map(f(value))(copy)
    def copy[B](value: B = this.value): WithKillSwitch[B] = WithKillSwitch(value)(killSwitch)
  }
  object WithKillSwitch {
    implicit val traverseWithKillSwitch: Traverse[WithKillSwitch] = new Traverse[WithKillSwitch] {
      override def traverse[F[_], A, B](fa: WithKillSwitch[A])(f: A => F[B])(implicit
          F: Applicative[F]
      ): F[WithKillSwitch[B]] = fa.traverse(f)

      override def foldLeft[A, B](fa: WithKillSwitch[A], b: B)(f: (B, A) => B): B = f(b, fa.unwrap)

      override def foldRight[A, B](fa: WithKillSwitch[A], lb: Eval[B])(
          f: (A, Eval[B]) => Eval[B]
      ): Eval[B] = f(fa.unwrap, lb)
    }
  }

  /** Passes through all elements of the source until and including the first element that satisfies the condition.
    * Thereafter pulls the kill switch of the first such element and drops all remaining elements of the source.
    *
    * '''Emits when''' upstream emits and all previously emitted elements do not meet the condition.
    *
    * '''Backpressures when''' downstream backpressures
    *
    * '''Completes when upstream''' completes
    *
    * '''Cancels when''' downstream cancels
    */
  def takeUntilThenDrain[A, Mat](
      graph: FlowOps[WithKillSwitch[A], Mat],
      condition: A => Boolean,
  ): graph.Repr[WithKillSwitch[A]] =
    graph.statefulMapConcat(() => {
      @SuppressWarnings(Array("org.wartremover.warts.Var"))
      var draining = false
      elem => {
        if (draining) Iterable.empty[WithKillSwitch[A]]
        else {
          if (condition(elem.unwrap)) {
            draining = true
            elem.killSwitch.shutdown()
          }
          Iterable.single(elem)
        }
      }
    })

  object syntax {

    /** Defines extension methods for [[akka.stream.scaladsl.FlowOpsMat]] that map to the methods defined in this class.
      *
      * The construction with type parameter `U` follows
      * <a href="https://typelevel.org/blog/2017/03/01/four-ways-to-escape-a-cake.html">Stephen's blog post about relatable variables</a>
      * to ensure that we can uniformly abstract over [[akka.stream.scaladsl.Source]]s and [[akka.stream.scaladsl.Flow]]s.
      * In particular, we cannot use an implicit class here. Unlike in the blog post, the implicit conversion [[akkaUtilSyntaxForFlowOps]]
      * does not extract [[akka.stream.scaladsl.FlowOpsMat]] into a separate type parameter because this would confuse
      * type inference.
      */
    private[util] class AkkaUtilSyntaxForFlowOps[A, Mat, U <: FlowOps[A, Mat]](private val graph: U)
        extends AnyVal {
      def remember(window: NonNegativeInt): U#Repr[NonEmpty[Seq[A]]] =
        AkkaUtil.remember(graph, window)

      def statefulMapAsync[S, T](initial: S)(
          f: (S, A) => Future[(S, T)]
      )(implicit loggingContext: NamedLoggingContext): U#Repr[T] =
        AkkaUtil.statefulMapAsync(graph, initial)(f)

      def statefulMapAsyncUS[S, T](initial: S)(
          f: (S, A) => FutureUnlessShutdown[(S, T)]
      )(implicit loggingContext: NamedLoggingContext): U#Repr[UnlessShutdown[T]] =
        AkkaUtil.statefulMapAsyncUS(graph, initial)(f)

      def mapAsyncUS[B](parallelism: Int)(f: A => FutureUnlessShutdown[B])(implicit
          loggingContext: NamedLoggingContext
      ): U#Repr[UnlessShutdown[B]] =
        AkkaUtil.mapAsyncUS(graph, parallelism)(f)

      def mapAsyncAndDrainUS[B](parallelism: Int)(
          f: A => FutureUnlessShutdown[B]
      )(implicit loggingContext: NamedLoggingContext): U#Repr[B] =
        AkkaUtil.mapAsyncAndDrainUS(graph, parallelism)(f)
    }
    // Use separate implicit conversions for Sources and Flows to help IntelliJ
    // Otherwise IntelliJ gets very resource hungry.
    implicit def akkaUtilSyntaxForFlowOpsSource[A, Mat](
        graph: Source[A, Mat]
    ): AkkaUtilSyntaxForFlowOps[A, Mat, graph.type] =
      new AkkaUtilSyntaxForFlowOps(graph)
    implicit def akkaUtilSyntaxForFlowOpsFlow[A, B, Mat](
        graph: Flow[A, B, Mat]
    ): AkkaUtilSyntaxForFlowOps[B, Mat, graph.type] =
      new AkkaUtilSyntaxForFlowOps(graph)

    /** Defines extension methods for [[akka.stream.scaladsl.FlowOps]] with a [[akka.stream.KillSwitch]].
      * @see AkkaUtilSyntaxForFlowOps for an explanation of the type parameter U
      */
    private[util] class AkkaUtilSyntaxForFLowOpsWithKillSwitch[
        A,
        Mat,
        U <: FlowOps[WithKillSwitch[A], Mat],
    ](private val graph: U)
        extends AnyVal {
      def takeUntilThenDrain(condition: A => Boolean): U#Repr[WithKillSwitch[A]] =
        AkkaUtil.takeUntilThenDrain(graph, condition)
    }
    // Use separate implicit conversions for Sources and Flows to help IntelliJ
    // Otherwise IntelliJ gets very resource hungry.
    implicit def akkaUtilSyntaxForFlowOpsWithKillSwitchSource[A, Mat](
        graph: Source[WithKillSwitch[A], Mat]
    ): AkkaUtilSyntaxForFLowOpsWithKillSwitch[A, Mat, graph.type] =
      new AkkaUtilSyntaxForFLowOpsWithKillSwitch(graph)
    implicit def akkaUtilSyntaxForFlowOpsWithKillSwitchFlow[A, B, Mat](
        graph: Flow[A, WithKillSwitch[B], Mat]
    ): AkkaUtilSyntaxForFLowOpsWithKillSwitch[B, Mat, graph.type] =
      new AkkaUtilSyntaxForFLowOpsWithKillSwitch(graph)

    /** Defines extension methods for [[akka.stream.scaladsl.FlowOpsMat]] that map to the methods defined in this class.
      * @see AkkaUtilSyntaxForFlowOps for an explanation of the type parameter U
      */
    private[util] class AkkaUtilSyntaxForFlowOpsMat[A, Mat, U <: FlowOpsMat[A, Mat]](
        private val graph: U
    ) extends AnyVal {

      private[util] def withMaterializedValueMat[M, Mat2](create: => M)(
          mat: (Mat, M) => Mat2
      ): U#ReprMat[(A, M), Mat2] =
        AkkaUtil.withMaterializedValueMat(create)(graph)(mat)

      def withUniqueKillSwitchMat[Mat2](
      )(mat: (Mat, UniqueKillSwitch) => Mat2): U#ReprMat[WithKillSwitch[A], Mat2] =
        AkkaUtil.withUniqueKillSwitch(graph)(mat)

      def injectKillSwitch(killSwitch: Mat => KillSwitch): U#ReprMat[WithKillSwitch[A], Mat] =
        AkkaUtil.injectKillSwitch(graph)(killSwitch)
    }
    // Use separate implicit conversions for Sources and Flows to help IntelliJ
    // Otherwise IntelliJ gets very resource hungry.
    implicit def akkaUtilSyntaxForFlowOpsMatSource[A, Mat](
        graph: Source[A, Mat]
    ): AkkaUtilSyntaxForFlowOpsMat[A, Mat, graph.type] =
      new AkkaUtilSyntaxForFlowOpsMat(graph)
    implicit def akkaUtilSyntaxForFlowOpsMat[A, B, Mat](
        graph: Flow[A, B, Mat]
    ): AkkaUtilSyntaxForFlowOpsMat[B, Mat, graph.type] =
      new AkkaUtilSyntaxForFlowOpsMat(graph)
  }
}
