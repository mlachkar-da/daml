// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.topology.transaction

import com.digitalasset.canton.ProtoDeserializationError.*
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.logging.pretty.PrettyInstances.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.protocol.v2
import com.digitalasset.canton.serialization.ProtoConverter.ParsingResult
import com.digitalasset.canton.serialization.{ProtoConverter, ProtocolVersionedMemoizedEvidence}
import com.digitalasset.canton.topology.transaction.TopologyTransactionX.TxHash
import com.digitalasset.canton.version.*
import com.google.protobuf.ByteString
import slick.jdbc.SetParameter

import scala.reflect.ClassTag

/** Replace or Remove */
sealed trait TopologyChangeOpX extends TopologyChangeOpCommon {
  def toProto: v2.TopologyChangeOpX

  final def select[TargetOp <: TopologyChangeOpX](implicit
      O: ClassTag[TargetOp]
  ): Option[TargetOp] = O.unapply(this)
}

object TopologyChangeOpX {

  /** Adds or replaces an existing record */
  final case object Replace extends TopologyChangeOpX {
    override def toProto: v2.TopologyChangeOpX = v2.TopologyChangeOpX.Replace
  }
  final case object Remove extends TopologyChangeOpX {
    override def toProto: v2.TopologyChangeOpX = v2.TopologyChangeOpX.Remove
  }

  type Remove = Remove.type
  type Replace = Replace.type

  def unapply(
      tx: TopologyTransactionX[TopologyChangeOpX, TopologyMappingX]
  ): Option[TopologyChangeOpX] = Some(tx.op)
  def unapply(
      tx: SignedTopologyTransactionX[TopologyChangeOpX, TopologyMappingX]
  ): Option[TopologyChangeOpX] = Some(tx.transaction.op)

  def fromProtoV2(
      protoOp: v2.TopologyChangeOpX
  ): ParsingResult[TopologyChangeOpX] =
    protoOp match {
      case v2.TopologyChangeOpX.Remove => Right(Remove)
      case v2.TopologyChangeOpX.Replace => Right(Replace)
      case v2.TopologyChangeOpX.Unrecognized(x) => Left(UnrecognizedEnum(protoOp.name, x))
    }

  implicit val setParameterTopologyChangeOp: SetParameter[TopologyChangeOpX] = (v, pp) =>
    v match {
      case Remove => pp.setInt(1)
      case Replace => pp.setInt(2)
    }

}

/** Change to the distributed domain topology
  *
  * A topology transaction is a state change to the domain topology. There are different
  * types of topology states (so called mappings, because they map some id to some value).
  *
  * Each mapping has some variables and some combination of these variables makes a
  * "unique key". Subsequent changes to that key need to have an incremental serial number.
  *
  * Topology changes always affect certain identities. Therefore, these topology
  * transactions need to be authorized through signatures.
  *
  * An authorized transaction is called a [[SignedTopologyTransactionX]]
  */
final case class TopologyTransactionX[+Op <: TopologyChangeOpX, +M <: TopologyMappingX] private (
    op: Op,
    serial: PositiveInt,
    mapping: M,
)(
    override val representativeProtocolVersion: RepresentativeProtocolVersion[
      TopologyTransactionX.type
    ],
    override val deserializedFrom: Option[ByteString] = None,
) extends ProtocolVersionedMemoizedEvidence
    with PrettyPrinting
    with HasProtocolVersionedWrapper[TopologyTransactionX[TopologyChangeOpX, TopologyMappingX]] {

  def reverse: TopologyTransactionX[TopologyChangeOpX, M] = {
    val next = (op: TopologyChangeOpX) match {
      case TopologyChangeOpX.Replace => TopologyChangeOpX.Remove
      case TopologyChangeOpX.Remove => TopologyChangeOpX.Replace
    }
    TopologyTransactionX(next, serial = serial.increment, mapping = mapping)(
      representativeProtocolVersion,
      None,
    )
  }
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def selectMapping[TargetMapping <: TopologyMappingX: ClassTag]
      : Option[TopologyTransactionX[Op, TargetMapping]] =
    mapping
      .select[TargetMapping]
      .map(_ => this.asInstanceOf[TopologyTransactionX[Op, TargetMapping]])

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def selectOp[TargetOp <: TopologyChangeOpX: ClassTag]: Option[TopologyTransactionX[TargetOp, M]] =
    op.select[TargetOp].map(_ => this.asInstanceOf[TopologyTransactionX[TargetOp, M]])

  /** returns hash of the given transaction */
  lazy val hash: TxHash = {
    TxHash(
      Hash.digest(
        HashPurpose.TopologyTransactionSignature,
        // TODO(#14048) use digest directly to avoid protobuf serialization for hashing
        this.getCryptographicEvidence,
        HashAlgorithm.Sha256,
      )
    )
  }

  override def toByteStringUnmemoized: ByteString = super[HasProtocolVersionedWrapper].toByteString

  def toProtoV2: v2.TopologyTransactionX = v2.TopologyTransactionX(
    operation = op.toProto,
    serial = serial.value,
    mapping = Some(mapping.toProtoV2),
  )

  def asVersion(
      protocolVersion: ProtocolVersion
  ): TopologyTransactionX[Op, M] = {
    TopologyTransactionX[Op, M](op, serial, mapping)(
      TopologyTransactionX.protocolVersionRepresentativeFor(protocolVersion)
    )
  }

  /** Indicates how to pretty print this instance.
    * See `PrettyPrintingTest` for examples on how to implement this method.
    */
  override def pretty: Pretty[TopologyTransactionX.this.type] =
    prettyOfClass(
      unnamedParam(_.mapping),
      param("serial", _.serial),
      param("op", _.op),
    )

  @transient override protected lazy val companionObj: TopologyTransactionX.type =
    TopologyTransactionX
}

object TopologyTransactionX
    extends HasMemoizedProtocolVersionedWrapperCompanion[
      TopologyTransactionX[TopologyChangeOpX, TopologyMappingX]
    ] {

  final case class TxHash(hash: Hash) extends AnyVal {}

  override val name: String = "TopologyTransaction"

  type GenericTopologyTransactionX = TopologyTransactionX[TopologyChangeOpX, TopologyMappingX]

  val supportedProtoVersions = SupportedProtoVersions(
    ProtoVersion(-1) -> UnsupportedProtoCodec(ProtocolVersion.minimum),
    ProtoVersion(2) -> VersionedProtoConverter(ProtocolVersion.CNTestNet)(v2.TopologyTransactionX)(
      supportedProtoVersionMemoized(_)(fromProtoV2),
      _.toProtoV2.toByteString,
    ),
  )

  def apply[Op <: TopologyChangeOpX, M <: TopologyMappingX](
      op: Op,
      serial: PositiveInt,
      mapping: M,
      protocolVersion: ProtocolVersion,
  ): TopologyTransactionX[Op, M] = TopologyTransactionX[Op, M](op, serial, mapping)(
    protocolVersionRepresentativeFor(protocolVersion),
    None,
  )

  private def fromProtoV2(transactionP: v2.TopologyTransactionX)(
      bytes: ByteString
  ): ParsingResult[TopologyTransactionX[TopologyChangeOpX, TopologyMappingX]] = {
    val v2.TopologyTransactionX(opP, serialP, mappingP) = transactionP
    for {
      mapping <- ProtoConverter.parseRequired(TopologyMappingX.fromProtoV2, "mapping", mappingP)
      serial <- ProtoConverter.parsePositiveInt(serialP)
      op <- TopologyChangeOpX.fromProtoV2(opP)
    } yield TopologyTransactionX(op, serial, mapping)(
      protocolVersionRepresentativeFor(ProtoVersion(2)),
      Some(bytes),
    )
  }
}
