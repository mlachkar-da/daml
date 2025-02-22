// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.data

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.lf.data.Ref
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.*
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.ledger.api.DeduplicationPeriod
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.protocol.{v0, *}
import com.digitalasset.canton.serialization.ProtoConverter.ParsingResult
import com.digitalasset.canton.serialization.{ProtoConverter, ProtocolVersionedMemoizedEvidence}
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.version.*
import com.google.protobuf.ByteString

/** Information about the submitters of the transaction
  * `maxSequencingTimeO` was added in PV=5, so it will only be defined for PV >= 5, and will be `None` otherwise.
  */
final case class SubmitterMetadata private (
    actAs: NonEmpty[Set[LfPartyId]],
    applicationId: ApplicationId,
    commandId: CommandId,
    submitterParticipant: ParticipantId,
    salt: Salt,
    submissionId: Option[LedgerSubmissionId],
    dedupPeriod: DeduplicationPeriod,
    maxSequencingTimeO: Option[CantonTimestamp],
)(
    hashOps: HashOps,
    override val representativeProtocolVersion: RepresentativeProtocolVersion[
      SubmitterMetadata.type
    ],
    override val deserializedFrom: Option[ByteString],
) extends MerkleTreeLeaf[SubmitterMetadata](hashOps)
    with HasProtocolVersionedWrapper[SubmitterMetadata]
    with ProtocolVersionedMemoizedEvidence {

  override protected[this] def toByteStringUnmemoized: ByteString =
    super[HasProtocolVersionedWrapper].toByteString

  override val hashPurpose: HashPurpose = HashPurpose.SubmitterMetadata

  override def pretty: Pretty[SubmitterMetadata] = prettyOfClass(
    param("act as", _.actAs),
    param("application id", _.applicationId),
    param("command id", _.commandId),
    param("submitter participant", _.submitterParticipant),
    param("salt", _.salt),
    paramIfDefined("submission id", _.submissionId),
    param("deduplication period", _.dedupPeriod),
    paramIfDefined("max sequencing time", _.maxSequencingTimeO),
  )

  @transient override protected lazy val companionObj: SubmitterMetadata.type = SubmitterMetadata

  protected def toProtoV0: v0.SubmitterMetadata = v0.SubmitterMetadata(
    actAs = actAs.toSeq,
    applicationId = applicationId.toProtoPrimitive,
    commandId = commandId.toProtoPrimitive,
    submitterParticipant = submitterParticipant.toProtoPrimitive,
    salt = Some(salt.toProtoV0),
    submissionId = submissionId.getOrElse(""),
    dedupPeriod = Some(SerializableDeduplicationPeriod(dedupPeriod).toProtoV0),
  )

  protected def toProtoV1: v1.SubmitterMetadata = v1.SubmitterMetadata(
    actAs = actAs.toSeq,
    applicationId = applicationId.toProtoPrimitive,
    commandId = commandId.toProtoPrimitive,
    submitterParticipant = submitterParticipant.toProtoPrimitive,
    salt = Some(salt.toProtoV0),
    submissionId = submissionId.getOrElse(""),
    dedupPeriod = Some(SerializableDeduplicationPeriod(dedupPeriod).toProtoV0),
    maxSequencingTime = maxSequencingTimeO match {
      case Some(_) => maxSequencingTimeO.map(_.toProtoPrimitive)
      case None =>
        throw new IllegalStateException(
          "Trying to serialize a SubmitterMetadata to proto V1 with an empty maxSequencingTime"
        )
    },
  )
}

object SubmitterMetadata
    extends HasMemoizedProtocolVersionedWithContextCompanion[
      SubmitterMetadata,
      HashOps,
    ] {
  override val name: String = "SubmitterMetadata"

  val supportedProtoVersions = SupportedProtoVersions(
    ProtoVersion(0) -> VersionedProtoConverter(ProtocolVersion.v3)(v0.SubmitterMetadata)(
      supportedProtoVersionMemoized(_)(fromProtoV0),
      _.toProtoV0.toByteString,
    ),
    ProtoVersion(1) -> VersionedProtoConverter(ProtocolVersion.v5)(v1.SubmitterMetadata)(
      supportedProtoVersionMemoized(_)(fromProtoV1),
      _.toProtoV1.toByteString,
    ),
  )

  def apply(
      actAs: NonEmpty[Set[LfPartyId]],
      applicationId: ApplicationId,
      commandId: CommandId,
      submitterParticipant: ParticipantId,
      salt: Salt,
      submissionId: Option[LedgerSubmissionId],
      dedupPeriod: DeduplicationPeriod,
      maxSequencingTime: CantonTimestamp,
      hashOps: HashOps,
      protocolVersion: ProtocolVersion,
  ): SubmitterMetadata = SubmitterMetadata(
    actAs, // Canton ignores SubmitterInfo.readAs per https://github.com/digital-asset/daml/pull/12136
    applicationId,
    commandId,
    submitterParticipant,
    salt,
    submissionId,
    dedupPeriod,
    Option.when(protocolVersion >= ProtocolVersion.v5)(maxSequencingTime),
  )(hashOps, protocolVersionRepresentativeFor(protocolVersion), None)

  def fromSubmitterInfo(hashOps: HashOps)(
      submitterActAs: List[Ref.Party],
      submitterApplicationId: Ref.ApplicationId,
      submitterCommandId: Ref.CommandId,
      submitterSubmissionId: Option[Ref.SubmissionId],
      submitterDeduplicationPeriod: DeduplicationPeriod,
      submitterParticipant: ParticipantId,
      salt: Salt,
      maxSequencingTime: CantonTimestamp,
      protocolVersion: ProtocolVersion,
  ): Either[String, SubmitterMetadata] = {
    NonEmpty.from(submitterActAs.toSet).toRight("The actAs set must not be empty.").map {
      actAsNes =>
        SubmitterMetadata(
          actAsNes, // Canton ignores SubmitterInfo.readAs per https://github.com/digital-asset/daml/pull/12136
          ApplicationId(submitterApplicationId),
          CommandId(submitterCommandId),
          submitterParticipant,
          salt,
          submitterSubmissionId,
          submitterDeduplicationPeriod,
          maxSequencingTime,
          hashOps,
          protocolVersion,
        )
    }
  }

  private def fromProtoV0(hashOps: HashOps, metaDataP: v0.SubmitterMetadata)(
      bytes: ByteString
  ): ParsingResult[SubmitterMetadata] = {
    val protoVersion = ProtoVersion(0)

    val v0.SubmitterMetadata(
      saltOP,
      actAsP,
      applicationIdP,
      commandIdP,
      submitterParticipantP,
      submissionIdP,
      dedupPeriodOP,
    ) = metaDataP

    fromProtoV0V1(hashOps, protoVersion)(
      saltOP,
      actAsP,
      applicationIdP,
      commandIdP,
      submitterParticipantP,
      submissionIdP,
      dedupPeriodOP,
      None,
    )(bytes)
  }

  private def fromProtoV1(hashOps: HashOps, metaDataP: v1.SubmitterMetadata)(
      bytes: ByteString
  ): ParsingResult[SubmitterMetadata] = {
    val protoVersion = ProtoVersion(1)

    val v1.SubmitterMetadata(
      saltOP,
      actAsP,
      applicationIdP,
      commandIdP,
      submitterParticipantP,
      submissionIdP,
      dedupPeriodOP,
      maxSequencingTimeOP,
    ) = metaDataP

    fromProtoV0V1(hashOps, protoVersion)(
      saltOP,
      actAsP,
      applicationIdP,
      commandIdP,
      submitterParticipantP,
      submissionIdP,
      dedupPeriodOP,
      Some(maxSequencingTimeOP),
    )(bytes)
  }

  private def fromProtoV0V1(
      hashOps: HashOps,
      protoVersion: ProtoVersion,
  )(
      saltOP: Option[com.digitalasset.canton.crypto.v0.Salt],
      actAsP: Seq[String],
      applicationIdP: String,
      commandIdP: String,
      submitterParticipantP: String,
      submissionIdP: String,
      dedupPeriodOP: Option[com.digitalasset.canton.protocol.v0.DeduplicationPeriod],
      maxSequencingTimeOPO: Option[Option[com.google.protobuf.timestamp.Timestamp]],
  )(
      bytes: ByteString
  ): ParsingResult[SubmitterMetadata] = {
    for {
      submitterParticipant <- ParticipantId
        .fromProtoPrimitive(submitterParticipantP, "SubmitterMetadata.submitter_participant")
      actAs <- actAsP.traverse(
        ProtoConverter
          .parseLfPartyId(_)
          .leftMap(e => ProtoDeserializationError.ValueConversionError("actAs", e.message))
      )
      applicationId <- ApplicationId
        .fromProtoPrimitive(applicationIdP)
        .leftMap(ProtoDeserializationError.ValueConversionError("applicationId", _))
      commandId <- CommandId
        .fromProtoPrimitive(commandIdP)
        .leftMap(ProtoDeserializationError.ValueConversionError("commandId", _))
      salt <- ProtoConverter
        .parseRequired(Salt.fromProtoV0, "salt", saltOP)
        .leftMap(e => ProtoDeserializationError.ValueConversionError("salt", e.message))
      submissionIdO <- Option
        .when(submissionIdP.nonEmpty)(submissionIdP)
        .traverse(
          LedgerSubmissionId
            .fromString(_)
            .leftMap(ProtoDeserializationError.ValueConversionError("submissionId", _))
        )
      dedupPeriod <- ProtoConverter
        .parseRequired(
          SerializableDeduplicationPeriod.fromProtoV0,
          "SubmitterMetadata.deduplication_period",
          dedupPeriodOP,
        )
        .leftMap(e =>
          ProtoDeserializationError.ValueConversionError("deduplicationPeriod", e.message)
        )
      actAsNes <- NonEmpty
        .from(actAs.toSet)
        .toRight(
          ProtoDeserializationError.ValueConversionError("acsAs", "actAs set must not be empty.")
        )
      maxSequencingTimeO <- maxSequencingTimeOPO.traverse(maxSequencingTimeOP =>
        ProtoConverter
          .parseRequired(
            CantonTimestamp.fromProtoPrimitive,
            "SubmitterMetadata.max_sequencing_time",
            maxSequencingTimeOP,
          )
      )
    } yield SubmitterMetadata(
      actAsNes,
      applicationId,
      commandId,
      submitterParticipant,
      salt,
      submissionIdO,
      dedupPeriod,
      maxSequencingTimeO,
    )(hashOps, protocolVersionRepresentativeFor(protoVersion), Some(bytes))
  }
}
