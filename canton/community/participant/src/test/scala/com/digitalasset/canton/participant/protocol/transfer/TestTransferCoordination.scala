// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.protocol.transfer

import cats.data.EitherT
import com.digitalasset.canton.crypto.{DomainSnapshotSyncCryptoApi, SyncCryptoApiProvider}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.protocol.transfer.TransferProcessingSteps.{
  TransferProcessorError,
  UnknownDomain,
}
import com.digitalasset.canton.participant.store.memory.InMemoryTransferStore
import com.digitalasset.canton.protocol.ExampleTransactionFactory.*
import com.digitalasset.canton.protocol.{SourceDomainId, TargetDomainId}
import com.digitalasset.canton.time.TimeProofTestUtil
import com.digitalasset.canton.topology.transaction.ParticipantPermission.{
  Confirmation,
  Observation,
  Submission,
}
import com.digitalasset.canton.topology.transaction.VettedPackages
import com.digitalasset.canton.topology.{DomainId, ParticipantId, TestingTopology}
import com.digitalasset.canton.tracing.{TraceContext, Traced}
import com.digitalasset.canton.{BaseTest, LfPackageId}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.mockito.MockitoSugar.mock

import scala.concurrent.{ExecutionContext, Future}

private[transfer] object TestTransferCoordination {
  def apply(
      domains: Set[TargetDomainId],
      timeProofTimestamp: CantonTimestamp,
      snapshotOverride: Option[DomainSnapshotSyncCryptoApi] = None,
      awaitTimestampOverride: Option[Option[Future[Unit]]] = None,
      loggerFactory: NamedLoggerFactory,
      packages: Seq[LfPackageId] = Seq.empty,
  )(implicit ec: ExecutionContext): TransferCoordination = {

    val recentTimeProofProvider = mock[RecentTimeProofProvider]
    when(recentTimeProofProvider.get(any[TargetDomainId])(any[TraceContext]))
      .thenReturn(EitherT.pure(TimeProofTestUtil.mkTimeProof(timeProofTimestamp)))

    val transferStores =
      domains.map(domain => domain -> new InMemoryTransferStore(domain, loggerFactory)).toMap
    val transferInBySubmission = { _: DomainId => None }
    val protocolVersionGetter = (_: Traced[DomainId]) =>
      Future.successful(Some(BaseTest.testedProtocolVersion))

    new TransferCoordination(
      transferStoreFor = id =>
        transferStores.get(id).toRight(UnknownDomain(id.unwrap, "not found")),
      recentTimeProofFor = recentTimeProofProvider,
      inSubmissionById = transferInBySubmission,
      protocolVersionFor = protocolVersionGetter,
      syncCryptoApi = defaultSyncCryptoApi(domains.toSeq.map(_.unwrap), packages, loggerFactory),
      loggerFactory,
    ) {

      override def awaitTransferOutTimestamp(
          sourceDomain: SourceDomainId,
          timestamp: CantonTimestamp,
      )(implicit
          traceContext: TraceContext
      ): Either[TransferProcessingSteps.UnknownDomain, Future[Unit]] = {
        awaitTimestampOverride match {
          case None =>
            super.awaitTransferOutTimestamp(sourceDomain, timestamp)
          case Some(overridden) => Right(overridden.getOrElse(Future.unit))
        }
      }

      override def awaitTimestamp(
          domainId: DomainId,
          timestamp: CantonTimestamp,
          waitForEffectiveTime: Boolean,
      )(implicit
          traceContext: TraceContext
      ): Either[TransferProcessorError, Option[Future[Unit]]] =
        awaitTimestampOverride match {
          case None =>
            super.awaitTimestamp(domainId, timestamp, waitForEffectiveTime)
          case Some(overridden) => Right(overridden)
        }

      override def cryptoSnapshot(domain: DomainId, timestamp: CantonTimestamp)(implicit
          traceContext: TraceContext
      ): EitherT[Future, TransferProcessorError, DomainSnapshotSyncCryptoApi] = {
        snapshotOverride match {
          case None => super.cryptoSnapshot(domain, timestamp)
          case Some(cs) => EitherT.pure[Future, TransferProcessorError](cs)
        }
      }
    }
  }

  private def defaultSyncCryptoApi(
      domains: Seq[DomainId],
      packages: Seq[LfPackageId],
      loggerFactory: NamedLoggerFactory,
  ): SyncCryptoApiProvider =
    TestingTopology(domains = domains.toSet)
      .withReversedTopology(defaultTopology)
      .withPackages(defaultTopology.keys.map(VettedPackages(_, packages)).toSeq)
      .build(loggerFactory)
      .forOwner(submitterParticipant)

  private val observerParticipant1: ParticipantId = ParticipantId("observerParticipant1")
  private val observerParticipant2: ParticipantId = ParticipantId("observerParticipant2")

  private val defaultTopology = Map(
    submitterParticipant -> Map(submitter -> Submission),
    signatoryParticipant -> Map(signatory -> Submission),
    observerParticipant1 -> Map(observer -> Confirmation),
    observerParticipant2 -> Map(observer -> Observation),
  )

}
