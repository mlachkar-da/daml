// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.index

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.daml_lf_dev.DamlLf
import com.daml.error.ContextualizedErrorLogger
import com.daml.ledger.api.v1.event_query_service.GetEventsByContractKeyResponse
import com.daml.ledger.api.v2.command_completion_service.CompletionStreamResponse
import com.daml.ledger.api.v2.event_query_service.GetEventsByContractIdResponse
import com.daml.ledger.api.v2.state_service.GetActiveContractsResponse
import com.daml.ledger.api.v2.update_service.{
  GetTransactionResponse,
  GetTransactionTreeResponse,
  GetUpdateTreesResponse,
  GetUpdatesResponse,
}
import com.daml.lf.data.Ref
import com.daml.lf.data.Ref.{ApplicationId, Identifier}
import com.daml.lf.data.Time.Timestamp
import com.daml.lf.transaction.GlobalKey
import com.daml.lf.value.Value.{ContractId, VersionedContractInstance}
import com.daml.metrics.InstrumentedGraph.*
import com.daml.tracing.{Event, SpanAttribute, Spans}
import com.digitalasset.canton.concurrent.DirectExecutionContext
import com.digitalasset.canton.ledger.api.domain.ConfigurationEntry.Accepted
import com.digitalasset.canton.ledger.api.domain.{
  Filters,
  InclusiveFilters,
  LedgerId,
  LedgerOffset,
  PackageEntry,
  TransactionFilter,
  TransactionId,
}
import com.digitalasset.canton.ledger.api.health.HealthStatus
import com.digitalasset.canton.ledger.api.{TraceIdentifiers, domain}
import com.digitalasset.canton.ledger.configuration.Configuration
import com.digitalasset.canton.ledger.error.CommonErrors
import com.digitalasset.canton.ledger.error.groups.RequestValidationErrors
import com.digitalasset.canton.ledger.offset.Offset
import com.digitalasset.canton.ledger.participant.state.index.v2
import com.digitalasset.canton.ledger.participant.state.index.v2.MeteringStore.ReportData
import com.digitalasset.canton.ledger.participant.state.index.v2.*
import com.digitalasset.canton.logging.{
  ErrorLoggingContext,
  LoggingContextWithTrace,
  NamedLoggerFactory,
  NamedLogging,
}
import com.digitalasset.canton.metrics.Metrics
import com.digitalasset.canton.platform.ApiOffset.ApiOffsetConverter
import com.digitalasset.canton.platform.akkastreams.dispatcher.Dispatcher
import com.digitalasset.canton.platform.akkastreams.dispatcher.DispatcherImpl.DispatcherIsClosedException
import com.digitalasset.canton.platform.akkastreams.dispatcher.SubSource.RangeSource
import com.digitalasset.canton.platform.index.IndexServiceImpl.*
import com.digitalasset.canton.platform.store.dao.{
  EventProjectionProperties,
  LedgerDaoCommandCompletionsReader,
  LedgerDaoTransactionsReader,
  LedgerReadDao,
}
import com.digitalasset.canton.platform.store.entries.PartyLedgerEntry
import com.digitalasset.canton.platform.store.packagemeta.{PackageMetadata, PackageMetadataView}
import com.digitalasset.canton.platform.{ApiOffset, Party, PruneBuffers, TemplatePartiesFilter}
import io.grpc.StatusRuntimeException
import scalaz.syntax.tag.ToTagOps

import scala.concurrent.Future
import scala.util.Success

private[index] class IndexServiceImpl(
    val ledgerId: LedgerId,
    participantId: Ref.ParticipantId,
    ledgerDao: LedgerReadDao,
    transactionsReader: LedgerDaoTransactionsReader,
    commandCompletionsReader: LedgerDaoCommandCompletionsReader,
    contractStore: ContractStore,
    pruneBuffers: PruneBuffers,
    dispatcher: () => Dispatcher[Offset],
    packageMetadataView: PackageMetadataView,
    metrics: Metrics,
    override protected val loggerFactory: NamedLoggerFactory,
) extends IndexService
    with NamedLogging {

  private val directEc = DirectExecutionContext(noTracingLogger)

  // An Akka stream buffer is added at the end of all streaming queries,
  // allowing to absorb temporary downstream backpressure.
  // (e.g. when the client is temporarily slower than upstream delivery throughput)
  private val LedgerApiStreamsBufferSize = 128

  private val maximumLedgerTimeService = new ContractStoreBasedMaximumLedgerTimeService(
    contractStore,
    loggerFactory,
  )

  override def getParticipantId(): Future[Ref.ParticipantId] =
    Future.successful(participantId)

  override def currentHealth(): HealthStatus = ledgerDao.currentHealth()

  override def lookupContractKey(readers: Set[Ref.Party], key: GlobalKey)(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[Option[ContractId]] =
    contractStore.lookupContractKey(readers, key)

  override def transactions(
      startExclusive: domain.LedgerOffset,
      endInclusive: Option[domain.LedgerOffset],
      transactionFilter: domain.TransactionFilter,
      verbose: Boolean,
      multiDomainEnabled: Boolean,
  )(implicit loggingContext: LoggingContextWithTrace): Source[GetUpdatesResponse, NotUsed] =
    withValidatedFilter(transactionFilter, packageMetadataView.current()) {
      between(startExclusive, endInclusive) { (from, to) =>
        from.foreach(offset =>
          Spans.setCurrentSpanAttribute(SpanAttribute.OffsetFrom, offset.toHexString)
        )
        to.foreach(offset =>
          Spans.setCurrentSpanAttribute(SpanAttribute.OffsetTo, offset.toHexString)
        )
        dispatcher()
          .startingAt(
            from.getOrElse(Offset.beforeBegin),
            RangeSource {
              val memoFilter =
                memoizedTransactionFilterProjection(
                  packageMetadataView,
                  transactionFilter,
                  verbose,
                  alwaysPopulateArguments = false,
                )
              (startExclusive, endInclusive) =>
                Source(memoFilter().toList)
                  .flatMapConcat { case (templateFilter, eventProjectionProperties) =>
                    transactionsReader
                      .getFlatTransactions(
                        startExclusive,
                        endInclusive,
                        templateFilter,
                        eventProjectionProperties,
                        multiDomainEnabled,
                      )
                  }
            },
            to,
          )
          .mapError(shutdownError)
          .map(_._2)
          .buffered(metrics.daml.index.flatTransactionsBufferSize, LedgerApiStreamsBufferSize)
      }.wireTap(
        _.update match {
          case GetUpdatesResponse.Update.Transaction(transaction) =>
            Spans.addEventToCurrentSpan(
              Event(transaction.commandId, TraceIdentifiers.fromTransaction(transaction))
            )
          case _ => ()
        }
      )
    }(ErrorLoggingContext(logger, loggingContext))

  override def transactionTrees(
      startExclusive: LedgerOffset,
      endInclusive: Option[LedgerOffset],
      transactionFilter: domain.TransactionFilter,
      verbose: Boolean,
      multiDomainEnabled: Boolean,
  )(implicit loggingContext: LoggingContextWithTrace): Source[GetUpdateTreesResponse, NotUsed] =
    withValidatedFilter(transactionFilter, packageMetadataView.current()) {
      val parties = transactionFilter.filtersByParty.keySet
      between(startExclusive, endInclusive) { (from, to) =>
        from.foreach(offset =>
          Spans.setCurrentSpanAttribute(SpanAttribute.OffsetFrom, offset.toHexString)
        )
        to.foreach(offset =>
          Spans.setCurrentSpanAttribute(SpanAttribute.OffsetTo, offset.toHexString)
        )
        dispatcher()
          .startingAt(
            from.getOrElse(Offset.beforeBegin),
            RangeSource {
              val memoFilter =
                memoizedTransactionFilterProjection(
                  packageMetadataView,
                  transactionFilter,
                  verbose,
                  alwaysPopulateArguments = true,
                )
              (startExclusive, endInclusive) =>
                Source(memoFilter().toList)
                  .flatMapConcat { case (_, eventProjectionProperties) =>
                    transactionsReader
                      .getTransactionTrees(
                        startExclusive,
                        endInclusive,
                        parties, // on the query filter side we treat every party as wildcard party
                        eventProjectionProperties,
                        multiDomainEnabled,
                      )
                  }
            },
            to,
          )
          .mapError(shutdownError)
          .map(_._2)
          .buffered(metrics.daml.index.transactionTreesBufferSize, LedgerApiStreamsBufferSize)
      }.wireTap(
        _.update match {
          case GetUpdateTreesResponse.Update.TransactionTree(transactionTree) =>
            Spans.addEventToCurrentSpan(
              Event(
                transactionTree.commandId,
                TraceIdentifiers.fromTransactionTree(transactionTree),
              )
            )
          case _ => ()
        }
      )
    }(ErrorLoggingContext(logger, loggingContext))

  override def getCompletions(
      startExclusive: LedgerOffset,
      applicationId: Ref.ApplicationId,
      parties: Set[Ref.Party],
  )(implicit loggingContext: LoggingContextWithTrace): Source[CompletionStreamResponse, NotUsed] =
    convertOffset(startExclusive)
      .flatMapConcat { beginOpt =>
        dispatcher()
          .startingAt(
            beginOpt,
            RangeSource(
              commandCompletionsReader.getCommandCompletions(_, _, applicationId, parties)
            ),
            None,
          )
          .mapError(shutdownError)
          .map(_._2)
      }
      .buffered(metrics.daml.index.completionsBufferSize, LedgerApiStreamsBufferSize)

  override def getCompletions(
      startExclusive: LedgerOffset,
      endInclusive: LedgerOffset,
      applicationId: Ref.ApplicationId,
      parties: Set[Ref.Party],
  )(implicit loggingContext: LoggingContextWithTrace): Source[CompletionStreamResponse, NotUsed] =
    between(startExclusive, Some(endInclusive)) { (start, end) =>
      dispatcher()
        .startingAt(
          start.getOrElse(Offset.beforeBegin),
          RangeSource(commandCompletionsReader.getCommandCompletions(_, _, applicationId, parties)),
          end,
        )
        .mapError(shutdownError)
        .map(_._2)
    }
      .buffered(metrics.daml.index.completionsBufferSize, LedgerApiStreamsBufferSize)

  override def getActiveContracts(
      transactionFilter: TransactionFilter,
      verbose: Boolean,
      activeAtO: Option[Offset],
      multiDomainEnabled: Boolean,
  )(implicit
      loggingContext: LoggingContextWithTrace
  ): Source[GetActiveContractsResponse, NotUsed] = {
    implicit val errorLoggingContext = ErrorLoggingContext(logger, loggingContext)
    foldToSource {
      for {
        _ <- validateTransactionFilter(transactionFilter, packageMetadataView.current())
        endOffset = ledgerEnd()
        activeAt = activeAtO.getOrElse(endOffset)
        _ <- validatedAcsActiveAtOffset(activeAt = activeAt, ledgerEnd = endOffset)
      } yield {
        val activeContractsSource =
          Source(
            transactionFilterProjection(
              transactionFilter,
              verbose,
              packageMetadataView.current(),
              alwaysPopulateArguments = false,
            ).toList
          ).flatMapConcat { case (templateFilter, eventProjectionProperties) =>
            ledgerDao.transactionsReader
              .getActiveContracts(
                activeAt = activeAt,
                filter = templateFilter,
                eventProjectionProperties = eventProjectionProperties,
                multiDomainEnabled = multiDomainEnabled,
              )
          }
        activeContractsSource
          .concat(
            Source.single(
              GetActiveContractsResponse(offset = ApiOffset.toApiString(activeAt))
            )
          )
          .buffered(metrics.daml.index.activeContractsBufferSize, LedgerApiStreamsBufferSize)
      }
    }
  }
  override def lookupActiveContract(
      forParties: Set[Ref.Party],
      contractId: ContractId,
  )(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[Option[VersionedContractInstance]] =
    contractStore.lookupActiveContract(forParties, contractId)

  override def getTransactionById(
      transactionId: TransactionId,
      requestingParties: Set[Ref.Party],
  )(implicit loggingContext: LoggingContextWithTrace): Future[Option[GetTransactionResponse]] =
    transactionsReader
      .lookupFlatTransactionById(transactionId.unwrap, requestingParties)

  override def getTransactionTreeById(
      transactionId: TransactionId,
      requestingParties: Set[Ref.Party],
  )(implicit loggingContext: LoggingContextWithTrace): Future[Option[GetTransactionTreeResponse]] =
    transactionsReader
      .lookupTransactionTreeById(transactionId.unwrap, requestingParties)

  override def getEventsByContractId(
      contractId: ContractId,
      requestingParties: Set[Ref.Party],
  )(implicit loggingContext: LoggingContextWithTrace): Future[GetEventsByContractIdResponse] =
    ledgerDao.eventsReader.getEventsByContractId(
      contractId,
      requestingParties,
    )

  override def getEventsByContractKey(
      contractKey: com.daml.lf.value.Value,
      templateId: Ref.Identifier,
      requestingParties: Set[Ref.Party],
      endExclusiveSeqId: Option[Long],
  )(implicit loggingContext: LoggingContextWithTrace): Future[GetEventsByContractKeyResponse] = {
    ledgerDao.eventsReader.getEventsByContractKey(
      contractKey,
      templateId,
      requestingParties,
      endExclusiveSeqId,
      maxIterations = 1000,
    )
  }

  override def getParties(parties: Seq[Ref.Party])(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[List[IndexerPartyDetails]] =
    ledgerDao.getParties(parties)

  override def listKnownParties()(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[List[IndexerPartyDetails]] =
    ledgerDao.listKnownParties()

  override def partyEntries(
      startExclusive: Option[LedgerOffset.Absolute]
  )(implicit loggingContext: LoggingContextWithTrace): Source[PartyEntry, NotUsed] = {
    Source
      .future(concreteOffset(startExclusive))
      .flatMapConcat(dispatcher().startingAt(_, RangeSource(ledgerDao.getPartyEntries)))
      .mapError(shutdownError)
      .map {
        case (_, PartyLedgerEntry.AllocationRejected(subId, _, reason)) =>
          PartyEntry.AllocationRejected(subId, reason)
        case (_, PartyLedgerEntry.AllocationAccepted(subId, _, details)) =>
          PartyEntry.AllocationAccepted(subId, details)
      }
  }

  override def listLfPackages()(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[Map[Ref.PackageId, v2.PackageDetails]] =
    ledgerDao.listLfPackages()

  override def getLfArchive(packageId: Ref.PackageId)(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[Option[DamlLf.Archive]] =
    ledgerDao.getLfArchive(packageId)

  override def packageEntries(
      startExclusive: Option[LedgerOffset.Absolute]
  )(implicit loggingContext: LoggingContextWithTrace): Source[PackageEntry, NotUsed] =
    Source
      .future(concreteOffset(startExclusive))
      .flatMapConcat(dispatcher().startingAt(_, RangeSource(ledgerDao.getPackageEntries)))
      .mapError(shutdownError)
      .map(_._2.toDomain)

  /** Looks up the current configuration, if set, and the offset from which
    * to subscribe to further configuration changes.
    * The offset is internal and not exposed over Ledger API.
    */
  override def lookupConfiguration()(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[Option[(LedgerOffset.Absolute, Configuration)]] =
    ledgerDao
      .lookupLedgerConfiguration()
      .map(
        _.map { case (offset, config) => (toAbsolute(offset), config) }
      )(directEc)

  /** Looks up the current configuration, if set, and continues to stream configuration changes.
    */
  override def getLedgerConfiguration()(implicit
      loggingContext: LoggingContextWithTrace
  ): Source[LedgerConfiguration, NotUsed] = {
    Source
      .future(lookupConfiguration())
      .flatMapConcat { optResult =>
        val offset = optResult.map(_._1)
        val foundConfig = optResult.map(_._2)

        val initialConfig = Source(foundConfig.toList)
        val configStream = configurationEntries(offset).collect {
          case (_, Accepted(_, configuration)) => configuration
        }
        initialConfig
          .concat(configStream)
          .map(cfg => LedgerConfiguration(cfg.maxDeduplicationDuration))
      }
  }

  /** Retrieve configuration entries. */
  override def configurationEntries(startExclusive: Option[LedgerOffset.Absolute])(implicit
      loggingContext: LoggingContextWithTrace
  ): Source[(domain.LedgerOffset.Absolute, domain.ConfigurationEntry), NotUsed] =
    Source
      .future(concreteOffset(startExclusive))
      .flatMapConcat(
        dispatcher()
          .startingAt(_, RangeSource(ledgerDao.getConfigurationEntries))
          .mapError(shutdownError)
          .map { case (offset, config) =>
            toAbsolute(offset) -> config.toDomain
          }
      )

  override def prune(pruneUpToInclusive: Offset, pruneAllDivulgedContracts: Boolean)(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[Unit] = {
    pruneBuffers(pruneUpToInclusive)
    ledgerDao.prune(pruneUpToInclusive, pruneAllDivulgedContracts)
  }

  override def getMeteringReportData(
      from: Timestamp,
      to: Option[Timestamp],
      applicationId: Option[ApplicationId],
  )(implicit loggingContext: LoggingContextWithTrace): Future[ReportData] =
    ledgerDao.meteringReportData(
      from: Timestamp,
      to: Option[Timestamp],
      applicationId: Option[ApplicationId],
    )

  override def currentLedgerEnd(): Future[LedgerOffset.Absolute] = {
    val absoluteApiOffset = toApiOffset(ledgerEnd())
    Future.successful(absoluteApiOffset)
  }

  private def toApiOffset(ledgerDomainOffset: Offset): LedgerOffset.Absolute = {
    val offset =
      if (ledgerDomainOffset == Offset.beforeBegin) ApiOffset.begin
      else ledgerDomainOffset
    toAbsolute(offset)
  }

  private def ledgerEnd(): Offset = dispatcher().getHead()

  // Returns a function that memoizes the current end
  // Can be used directly or shared throughout a request processing
  private def convertOffset: LedgerOffset => Source[Offset, NotUsed] = { ledgerOffset =>
    (ledgerOffset match {
      case LedgerOffset.LedgerBegin => Success(Offset.beforeBegin)
      case LedgerOffset.LedgerEnd => Success(ledgerEnd())
      case LedgerOffset.Absolute(offset) => ApiOffset.tryFromString(offset)
    }).fold(Source.failed, off => Source.single(off))
  }

  private def between[A](
      startExclusive: domain.LedgerOffset,
      endInclusive: Option[domain.LedgerOffset],
  )(f: (Option[Offset], Option[Offset]) => Source[A, NotUsed])(implicit
      loggingContext: LoggingContextWithTrace
  ): Source[A, NotUsed] = {
    val convert = convertOffset
    convert(startExclusive).flatMapConcat { begin =>
      endInclusive
        .map(convert(_).map(Some(_)))
        .getOrElse(Source.single(None))
        .flatMapConcat {
          case Some(`begin`) =>
            Source.empty
          case Some(end) if begin > end =>
            Source.failed(
              RequestValidationErrors.OffsetOutOfRange
                .Reject(
                  s"End offset ${end.toApiString} is before Begin offset ${begin.toApiString}."
                )(ErrorLoggingContext(logger, loggingContext))
                .asGrpcError
            )
          case endOpt: Option[Offset] =>
            f(Some(begin), endOpt)
        }
    }
  }

  private def concreteOffset(startExclusive: Option[LedgerOffset.Absolute]): Future[Offset] =
    startExclusive
      .map(off => Future.fromTry(ApiOffset.tryFromString(off.value)))
      .getOrElse(Future.successful(Offset.beforeBegin))

  private def toAbsolute(offset: Offset): LedgerOffset.Absolute =
    LedgerOffset.Absolute(offset.toApiString)

  private def shutdownError(implicit
      loggingContext: LoggingContextWithTrace
  ): PartialFunction[scala.Throwable, scala.Throwable] = { case _: DispatcherIsClosedException =>
    toGrpcError
  }

  private def toGrpcError(implicit
      loggingContext: LoggingContextWithTrace
  ): StatusRuntimeException =
    CommonErrors.ServiceNotRunning
      .Reject("Index Service")(ErrorLoggingContext(logger, loggingContext))
      .asGrpcError

  override def lookupContractStateWithoutDivulgence(contractId: ContractId)(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[ContractState] =
    contractStore.lookupContractStateWithoutDivulgence(contractId)

  override def lookupMaximumLedgerTimeAfterInterpretation(ids: Set[ContractId])(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[MaximumLedgerTime] =
    maximumLedgerTimeService.lookupMaximumLedgerTimeAfterInterpretation(ids)

  override def latestPrunedOffsets()(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[(LedgerOffset.Absolute, LedgerOffset.Absolute)] =
    ledgerDao.pruningOffsets
      .map { case (prunedUpToInclusiveO, divulgencePrunedUpToO) =>
        toApiOffset(prunedUpToInclusiveO.getOrElse(Offset.beforeBegin)) -> toApiOffset(
          divulgencePrunedUpToO.getOrElse(Offset.beforeBegin)
        )
      }(directEc)

  override def resolveToTemplateIds(templateQualifiedName: Ref.QualifiedName)(implicit
      loggingContext: ContextualizedErrorLogger
  ): Either[StatusRuntimeException, PackageMetadata.TemplatesForQualifiedName] =
    packageMetadataView
      .current()
      .templates
      .get(templateQualifiedName)
      .toRight(
        RequestValidationErrors.NotFound.TemplateQualifiedNameNotFound
          .Reject(templateQualifiedName)(loggingContext)
          .asGrpcError
      )
}

object IndexServiceImpl {

  private[index] def checkUnknownTemplatesOrInterfaces(
      domainTransactionFilter: domain.TransactionFilter,
      metadata: PackageMetadata,
  ): List[Either[Identifier, Identifier]] =
    (for {
      (_, inclusiveFilterOption) <- domainTransactionFilter.filtersByParty.iterator
      inclusiveFilter <- inclusiveFilterOption.inclusive.iterator
      unknownInterfaces =
        inclusiveFilter.interfaceFilters
          .map(_.interfaceId)
          .diff(metadata.interfaces)
          .map(Right(_))
      unknownTemplates = inclusiveFilter.templateFilters
        .map(_.templateId)
        .diff(metadata.templates.view.values.flatMap(_.all).toSet)
        .map(Left(_))
      unknownTemplateOrInterface <- unknownInterfaces ++ unknownTemplates
    } yield unknownTemplateOrInterface).toList

  private[index] def foldToSource[A, B](
      either: Either[StatusRuntimeException, Source[A, NotUsed]]
  ): Source[A, NotUsed] =
    either match {
      case Left(e: StatusRuntimeException) => Source.failed[A](e)
      case Right(result) => result
    }

  private[index] def withValidatedFilter[T](
      domainTransactionFilter: domain.TransactionFilter,
      metadata: PackageMetadata,
  )(
      source: => Source[T, NotUsed]
  )(implicit errorLogger: ContextualizedErrorLogger): Source[T, NotUsed] =
    foldToSource(
      for {
        _ <- validateTransactionFilter(domainTransactionFilter, metadata)(errorLogger)
      } yield source
    )

  private[index] def validateTransactionFilter[T](
      domainTransactionFilter: domain.TransactionFilter,
      metadata: PackageMetadata,
  )(implicit errorLogger: ContextualizedErrorLogger): Either[StatusRuntimeException, Unit] = {
    val unknownTemplatesOrInterfaces: Seq[Either[Identifier, Identifier]] =
      checkUnknownTemplatesOrInterfaces(domainTransactionFilter, metadata)
    if (unknownTemplatesOrInterfaces.nonEmpty) {
      Left(
        RequestValidationErrors.NotFound.TemplateOrInterfaceIdsNotFound
          .Reject(unknownTemplatesOrInterfaces)
          .asGrpcError
      )
    } else
      Right(())
  }

  private[index] def validatedAcsActiveAtOffset[T](
      activeAt: Offset,
      ledgerEnd: Offset,
  )(implicit errorLogger: ContextualizedErrorLogger): Either[StatusRuntimeException, Unit] = {
    if (activeAt > ledgerEnd) {
      Left(
        RequestValidationErrors.OffsetAfterLedgerEnd
          .Reject(
            offsetType = "active_at_offset",
            requestedOffset = activeAt.toApiString,
            ledgerEnd = ledgerEnd.toApiString,
          )
          .asGrpcError
      )
    } else {
      Right(())
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Null", "org.wartremover.warts.Var"))
  private[index] def memoizedTransactionFilterProjection(
      packageMetadataView: PackageMetadataView,
      transactionFilter: domain.TransactionFilter,
      verbose: Boolean,
      alwaysPopulateArguments: Boolean,
  ): () => Option[(TemplatePartiesFilter, EventProjectionProperties)] = {
    @volatile var metadata: PackageMetadata = null
    @volatile var filters: Option[(TemplatePartiesFilter, EventProjectionProperties)] = None
    () =>
      val currentMetadata = packageMetadataView.current()
      if (metadata ne currentMetadata) {
        metadata = currentMetadata
        filters = transactionFilterProjection(
          transactionFilter,
          verbose,
          metadata,
          alwaysPopulateArguments,
        )
      }
      filters
  }

  private def transactionFilterProjection(
      transactionFilter: domain.TransactionFilter,
      verbose: Boolean,
      metadata: PackageMetadata,
      alwaysPopulateArguments: Boolean,
  ): Option[(TemplatePartiesFilter, EventProjectionProperties)] = {
    val templateFilter: Map[Identifier, Set[Party]] =
      IndexServiceImpl.templateFilter(metadata, transactionFilter)

    val wildcardFilter: Set[Party] = IndexServiceImpl.wildcardFilter(transactionFilter)

    if (templateFilter.isEmpty && wildcardFilter.isEmpty) {
      None
    } else {
      val eventProjectionProperties = EventProjectionProperties(
        transactionFilter,
        verbose,
        interfaceId => metadata.interfacesImplementedBy.getOrElse(interfaceId, Set.empty),
        alwaysPopulateArguments,
      )
      Some((TemplatePartiesFilter(templateFilter, wildcardFilter), eventProjectionProperties))
    }
  }

  private def templateIds(
      metadata: PackageMetadata,
      inclusiveFilters: InclusiveFilters,
  ): Set[Identifier] =
    inclusiveFilters.interfaceFilters.iterator
      .map(_.interfaceId)
      .flatMap(metadata.interfacesImplementedBy.getOrElse(_, Set.empty))
      .toSet
      .++(inclusiveFilters.templateFilters.map(_.templateId))

  private[index] def templateFilter(
      metadata: PackageMetadata,
      transactionFilter: domain.TransactionFilter,
  ): Map[Identifier, Set[Party]] = {
    transactionFilter.filtersByParty.view.foldLeft(Map.empty[Identifier, Set[Party]]) {
      case (acc, (party, Filters(Some(inclusiveFilters)))) =>
        templateIds(metadata, inclusiveFilters).foldLeft(acc) { case (acc, templateId) =>
          val updatedPartySet = acc.getOrElse(templateId, Set.empty[Party]) + party
          acc.updated(templateId, updatedPartySet)
        }
      case (acc, _) =>
        acc
    }
  }

  private[index] def wildcardFilter(
      transactionFilter: domain.TransactionFilter
  ): Set[Party] = {
    transactionFilter.filtersByParty.view.collect {
      case (party, Filters(None)) =>
        party
      case (party, Filters(Some(InclusiveFilters(templateIds, interfaceFilters))))
          if templateIds.isEmpty && interfaceFilters.isEmpty =>
        party
    }.toSet
  }
}
