// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.topology.store

import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, PositiveInt}
import com.digitalasset.canton.crypto.{Fingerprint, Signature}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.topology.transaction.*
import com.digitalasset.canton.version.ProtocolVersion
import org.scalatest.Assertions.fail

import scala.annotation.nowarn
import scala.concurrent.ExecutionContext

@nowarn("msg=match may not be exhaustive")
class TopologyStoreXTestData(
    loggerFactory: NamedLoggerFactory,
    executionContext: ExecutionContext,
) {

  def makeSignedTx[Op <: TopologyChangeOpX, M <: TopologyMappingX](
      mapping: M,
      op: Op = TopologyChangeOpX.Replace,
      isProposal: Boolean = false,
      serial: PositiveInt = PositiveInt.one,
  ): SignedTopologyTransactionX[Op, M] =
    SignedTopologyTransactionX.apply[Op, M](
      TopologyTransactionX(
        op,
        serial,
        mapping,
        ProtocolVersion.CNTestNet,
      ),
      signatures = NonEmpty(Set, Signature.noSignature),
      isProposal = isProposal,
    )(
      SignedTopologyTransactionX.supportedProtoVersions
        .protocolVersionRepresentativeFor(
          ProtocolVersion.CNTestNet
        )
    )

  val Seq(ts1, ts2, ts3, ts4, ts5, ts6, ts7, ts8, ts9, ts10) =
    (1L to 10L).map(CantonTimestamp.Epoch.plusSeconds)

  val factory: TestingOwnerWithKeys =
    new TestingOwnerWithKeys(
      SequencerId(
        UniqueIdentifier(
          Identifier.tryCreate("da"),
          Namespace(Fingerprint.tryCreate("sequencer")),
        )
      ),
      loggerFactory,
      executionContext,
    )

  val daDomainNamespace = Namespace(Fingerprint.tryCreate("default"))
  val daDomainUid = UniqueIdentifier(
    Identifier.tryCreate("da"),
    daDomainNamespace,
  )
  val Seq(participantId1, participantId2) = Seq("participant1", "participant2").map(p =>
    ParticipantId(
      UniqueIdentifier(
        Identifier.tryCreate(p),
        Namespace(Fingerprint.tryCreate("participants")),
      )
    )
  )
  val domainId1 = DomainId(
    UniqueIdentifier(
      Identifier.tryCreate("domain1"),
      Namespace(Fingerprint.tryCreate("domains")),
    )
  )
  val mediatorId1 = MediatorId(
    Identifier.tryCreate("mediator1"),
    Namespace(Fingerprint.tryCreate("mediators")),
  )
  val mediatorId2 = MediatorId(
    Identifier.tryCreate("mediator2"),
    Namespace(Fingerprint.tryCreate("mediators")),
  )
  val sequencerId1 = SequencerId(
    Identifier.tryCreate("sequencer1"),
    Namespace(Fingerprint.tryCreate("sequencers")),
  )
  val sequencerId2 = SequencerId(
    Identifier.tryCreate("sequencer2"),
    Namespace(Fingerprint.tryCreate("sequencers")),
  )
  val signingKeys = NonEmpty(Seq, factory.SigningKeys.key1)
  val owners = NonEmpty(Set, Namespace(Fingerprint.tryCreate("owner1")))
  val fredOfCanton = PartyId(
    Identifier.tryCreate("fred"),
    Namespace(Fingerprint.tryCreate("canton")),
  )

  val tx1_NSD_Proposal = makeSignedTx(
    NamespaceDelegationX
      .create(daDomainNamespace, signingKeys.head1, isRootDelegation = false)
      .getOrElse(fail()),
    isProposal = true,
  )
  val tx2_OTK = makeSignedTx(
    OwnerToKeyMappingX(participantId1, domain = None, signingKeys)
  )
  val tx3_IDD_Removal = makeSignedTx(
    IdentifierDelegationX(daDomainUid, signingKeys.head1),
    op = TopologyChangeOpX.Remove,
    serial = PositiveInt.tryCreate(1),
  )
  val tx3_PTP_Proposal = makeSignedTx(
    PartyToParticipantX(
      partyId = fredOfCanton,
      domainId = None,
      threshold = PositiveInt.one,
      participants = Seq(HostingParticipant(participantId1, ParticipantPermissionX.Submission)),
      groupAddressing = true,
    ),
    isProposal = true,
  )
  val tx3_NSD = makeSignedTx(
    NamespaceDelegationX
      .create(daDomainNamespace, signingKeys.head1, isRootDelegation = false)
      .getOrElse(fail())
  )
  val tx4_USD = makeSignedTx(
    UnionspaceDefinitionX
      .create(
        Namespace(Fingerprint.tryCreate("unionspace")),
        PositiveInt.one,
        owners = owners,
      )
      .getOrElse(fail())
  )
  val tx4_OTK_Proposal = makeSignedTx(
    OwnerToKeyMappingX(participantId1, domain = None, signingKeys),
    isProposal = true,
    serial = PositiveInt.tryCreate(2),
  )
  val tx5_PTP = makeSignedTx(
    PartyToParticipantX(
      partyId = fredOfCanton,
      domainId = None,
      threshold = PositiveInt.one,
      participants = Seq(HostingParticipant(participantId1, ParticipantPermissionX.Submission)),
      groupAddressing = true,
    )
  )
  val tx5_DTC = makeSignedTx(
    DomainTrustCertificateX(
      participantId2,
      domainId1,
      transferOnlyToGivenTargetDomains = false,
      targetDomains = Seq.empty,
    )
  )
  val tx6_DTC_Update = makeSignedTx(
    DomainTrustCertificateX(
      participantId2,
      domainId1,
      transferOnlyToGivenTargetDomains = true,
      targetDomains = Seq(domainId1),
    ),
    serial = PositiveInt.tryCreate(2),
  )
  val tx6_MDS = makeSignedTx(
    MediatorDomainStateX
      .create(
        domain = domainId1,
        group = NonNegativeInt.one,
        threshold = PositiveInt.one,
        active = Seq(mediatorId1),
        observers = Seq.empty,
      )
      .getOrElse(fail())
  )

  val tx7_MDS_Update = makeSignedTx(
    MediatorDomainStateX
      .create(
        domain = domainId1,
        group = NonNegativeInt.one,
        threshold = PositiveInt.one,
        active = Seq(mediatorId1, mediatorId2),
        observers = Seq.empty,
      )
      .getOrElse(fail()),
    serial = PositiveInt.tryCreate(2),
  )

  val tx8_SDS = makeSignedTx(
    SequencerDomainStateX
      .create(
        domain = domainId1,
        threshold = PositiveInt.one,
        active = Seq(sequencerId1),
        observers = Seq.empty,
      )
      .getOrElse(fail())
  )

  val tx9_SDS_Update = makeSignedTx(
    SequencerDomainStateX
      .create(
        domain = domainId1,
        threshold = PositiveInt.one,
        active = Seq(sequencerId1, sequencerId2),
        observers = Seq.empty,
      )
      .getOrElse(fail()),
    serial = PositiveInt.tryCreate(2),
  )
}
