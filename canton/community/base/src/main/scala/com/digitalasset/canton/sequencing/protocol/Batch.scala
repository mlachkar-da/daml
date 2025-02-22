// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.sequencing.protocol

import cats.Applicative
import cats.implicits.*
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.ProtoDeserializationError.FieldNotSet
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.crypto.HashOps
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.protocol.messages.ProtocolMessage
import com.digitalasset.canton.protocol.{v0, v1}
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.canton.serialization.ProtoConverter.ParsingResult
import com.digitalasset.canton.topology.{MediatorId, Member}
import com.digitalasset.canton.util.ByteStringUtil
import com.digitalasset.canton.version.{
  HasProtocolVersionedCompanion2,
  HasProtocolVersionedWrapper,
  ProtoVersion,
  ProtocolVersion,
  RepresentativeProtocolVersion,
}
import com.google.common.annotations.VisibleForTesting
import com.google.protobuf.ByteString

/** A '''batch''' is a a list of `n` tuples `(m`,,i,,` , recipients`,,i,,),
  * where `m`,,i,, is a message, and
  *  `recipients`,,i,, is the list of recipients of m,,i,,,
  *  for `0 <= i < n`.
  */
final case class Batch[+Env <: Envelope[_]] private (envelopes: List[Env])(
    override val representativeProtocolVersion: RepresentativeProtocolVersion[Batch.type]
) extends HasProtocolVersionedWrapper[Batch[Envelope[_]]]
    with PrettyPrinting {

  @transient override protected lazy val companionObj: Batch.type = Batch

  /** builds a set of recipients from all messages in this message batch
    */
  lazy val allMembers: Set[Member] = allRecipients.collect { case MemberRecipient(member) =>
    member
  }

  lazy val allRecipients: Set[Recipient] = envelopes.flatMap { e =>
    e.recipients.allRecipients
  }.toSet

  lazy val allMediatorRecipients: Set[Recipient] = {
    allRecipients.collect {
      case r @ MemberRecipient(_: MediatorId) => r
      case r: MediatorsOfDomain => r
      case AllMembersOfDomain => AllMembersOfDomain
    }
  }

  private[protocol] def toProtoV0: v0.CompressedBatch = {
    val batch = v0.Batch(envelopes = envelopes.map(_.closeEnvelope.toProtoV0))
    val compressed = ByteStringUtil.compressGzip(batch.toByteString)
    v0.CompressedBatch(
      algorithm = v0.CompressedBatch.CompressionAlgorithm.Gzip,
      compressedBatch = compressed,
    )
  }

  private[protocol] def toProtoV1: v1.CompressedBatch = {
    val batch = v1.Batch(envelopes = envelopes.map(_.closeEnvelope.toProtoV1))
    val compressed = ByteStringUtil.compressGzip(batch.toByteString)
    v1.CompressedBatch(
      algorithm = v0.CompressedBatch.CompressionAlgorithm.Gzip,
      compressedBatch = compressed,
    )
  }

  def map[Env2 <: Envelope[_]](f: Env => Env2): Batch[Env2] =
    Batch(envelopes.map(f))(representativeProtocolVersion)

  def copy[Env2 <: Envelope[_]](envelopes: List[Env2]): Batch[Env2] =
    Batch(envelopes)(representativeProtocolVersion)

  def envelopesCount: Int = envelopes.size

  private[sequencing] def traverse[F[_], Env2 <: Envelope[_]](f: Env => F[Env2])(implicit
      F: Applicative[F]
  ): F[Batch[Env2]] =
    F.map(envelopes.traverse(f))(Batch(_)(representativeProtocolVersion))

  override def pretty: Pretty[Batch[Envelope[_]]] = prettyOfClass(unnamedParam(_.envelopes))
}

object Batch extends HasProtocolVersionedCompanion2[Batch[Envelope[_]], Batch[ClosedEnvelope]] {
  override def name: String = "Batch"

  override val supportedProtoVersions: SupportedProtoVersions = SupportedProtoVersions(
    ProtoVersion(0) -> VersionedProtoConverter(ProtocolVersion.v3)(v0.CompressedBatch)(
      supportedProtoVersion(_)(
        // TODO(i10428) Prevent zip bombing when decompressing the request
        Batch.fromProtoV0(_, maxRequestSize = MaxRequestSizeToDeserialize.NoLimit)
      ),
      _.toProtoV0.toByteString,
    ),
    ProtoVersion(1) -> VersionedProtoConverter(
      ProtocolVersion.CNTestNet
    )(v1.CompressedBatch)(
      supportedProtoVersion(_)(
        // TODO(i10428) Prevent zip bombing when decompressing the request
        Batch.fromProtoV1(_, maxRequestSize = MaxRequestSizeToDeserialize.NoLimit)
      ),
      _.toProtoV1.toByteString,
    ),
  )

  def apply[Env <: Envelope[_]](
      envelopes: List[Env],
      protocolVersion: ProtocolVersion,
  ): Batch[Env] = Batch(envelopes)(protocolVersionRepresentativeFor(protocolVersion))

  def of[M <: ProtocolMessage](
      protocolVersion: ProtocolVersion,
      envs: (M, Recipients)*
  ): Batch[OpenEnvelope[M]] = {
    val envelopes = envs.map { case (m, recipients) =>
      OpenEnvelope[M](m, recipients)(protocolVersion)
    }.toList
    Batch[OpenEnvelope[M]](envelopes)(protocolVersionRepresentativeFor(protocolVersion))
  }

  @VisibleForTesting def fromClosed(
      protocolVersion: ProtocolVersion,
      envelopes: ClosedEnvelope*
  ): Batch[ClosedEnvelope] =
    Batch(envelopes.toList)(protocolVersionRepresentativeFor(protocolVersion))

  private[protocol] def fromProtoV0(
      batchProto: v0.CompressedBatch,
      maxRequestSize: MaxRequestSizeToDeserialize,
  ): ParsingResult[Batch[ClosedEnvelope]] = {
    val v0.CompressedBatch(algorithm, compressed) = batchProto

    for {
      uncompressed <- decompress(algorithm, compressed, maxRequestSize.toOption)
      uncompressedBatchProto <- ProtoConverter.protoParser(v0.Batch.parseFrom)(uncompressed)
      v0.Batch(envelopesProto) = uncompressedBatchProto
      envelopes <- envelopesProto.toList.traverse(ClosedEnvelope.fromProtoV0)
    } yield Batch[ClosedEnvelope](envelopes)(protocolVersionRepresentativeFor(ProtoVersion(0)))
  }

  private[protocol] def fromProtoV1(
      batchProto: v1.CompressedBatch,
      maxRequestSize: MaxRequestSizeToDeserialize,
  ): ParsingResult[Batch[ClosedEnvelope]] = {
    val v1.CompressedBatch(algorithm, compressed) = batchProto

    for {
      uncompressed <- decompress(algorithm, compressed, maxRequestSize.toOption)
      uncompressedBatchProto <- ProtoConverter.protoParser(v1.Batch.parseFrom)(uncompressed)
      v1.Batch(envelopesProto) = uncompressedBatchProto
      envelopes <- envelopesProto.toList.traverse(ClosedEnvelope.fromProtoV1)
    } yield Batch[ClosedEnvelope](envelopes)(protocolVersionRepresentativeFor(ProtoVersion(1)))
  }

  private def decompress(
      algorithm: v0.CompressedBatch.CompressionAlgorithm,
      compressed: ByteString,
      maxRequestSize: Option[NonNegativeInt],
  ): ParsingResult[ByteString] = {
    algorithm match {
      case v0.CompressedBatch.CompressionAlgorithm.None => Right(compressed)
      case v0.CompressedBatch.CompressionAlgorithm.Gzip =>
        ByteStringUtil
          .decompressGzip(compressed, maxBytesLimit = maxRequestSize.map(_.unwrap))
          .leftMap(_.toProtoDeserializationError)
      case _ => Left(FieldNotSet("CompressedBatch.Algorithm"))
    }
  }

  /** Constructs a batch with no envelopes */
  def empty[Env <: Envelope[_]](protocolVersion: ProtocolVersion): Batch[Env] =
    Batch(List.empty[Env])(protocolVersionRepresentativeFor(protocolVersion))

  def filterClosedEnvelopesFor(
      batch: Batch[ClosedEnvelope],
      member: Member,
      groupRecipients: Set[GroupRecipient],
  ): Batch[ClosedEnvelope] = {
    val newEnvs = batch.envelopes.mapFilter(e => e.forRecipient(member, groupRecipients))
    Batch(newEnvs)(batch.representativeProtocolVersion)
  }

  def filterOpenEnvelopesFor[T <: ProtocolMessage](
      batch: Batch[OpenEnvelope[T]],
      member: Member,
      groupRecipients: Set[GroupRecipient],
  ): Batch[OpenEnvelope[T]] = {
    val newEnvs = batch.envelopes.mapFilter(e => e.forRecipient(member, groupRecipients))
    Batch(newEnvs)(batch.representativeProtocolVersion)
  }

  def closeEnvelopes[T <: ProtocolMessage](batch: Batch[OpenEnvelope[T]]): Batch[ClosedEnvelope] = {
    val closedEnvs = batch.envelopes.map(env => env.closeEnvelope)
    Batch(closedEnvs)(batch.representativeProtocolVersion)
  }

  def openEnvelopes(batch: Batch[ClosedEnvelope])(
      protocolVersion: ProtocolVersion,
      hashOps: HashOps,
  ): (Batch[OpenEnvelope[ProtocolMessage]], Seq[ProtoDeserializationError]) = {
    val (openingErrors, openEnvelopes) =
      batch.envelopes.map(_.openEnvelope(hashOps, protocolVersion)).separate

    (Batch(openEnvelopes)(batch.representativeProtocolVersion), openingErrors)
  }
}
