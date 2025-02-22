// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.protocol.submission

import cats.implicits.toFunctorFilterOps
import com.digitalasset.canton.participant.protocol.submission.ContractEnrichmentFactory.ContractEnrichment
import com.digitalasset.canton.protocol.RollbackContext.RollbackScope
import com.digitalasset.canton.protocol.*
import com.digitalasset.canton.util.LfTransactionUtil
import com.digitalasset.canton.version.ProtocolVersion

/** Prior to protocol version 5 the metadata used in the [[ViewParticipantData.coreInputs]] contract map
  * was derived from `coreOtherNodes` passed to the `createViewParticipantData` method.
  *
  * In the situation where a node is looked up by key but then not resolved into a contract the core other nodes
  * will only contain a [[LfNodeLookupByKey]]. As this does not have signatories or stakeholder fields the global
  * key maintainers where (incorrectly) used.
  *
  * For v5 we have a fully formed [[SerializableContract]] available so do not need to enrich with metadata. Protocol
  * v4 participants should continue to use the maintainer based metadata to avoid model conformance problems.
  */
private[submission] trait ContractEnrichmentFactory {
  def apply(coreOtherNodes: List[(LfActionNode, RollbackScope)]): ContractEnrichment
}

private[submission] object ContractEnrichmentFactory {

  private type ContractEnrichment = SerializableContract => SerializableContract

  def apply(protocolVersion: ProtocolVersion): ContractEnrichmentFactory = {
    if (protocolVersion >= ProtocolVersion.v5) PV5 else PV0
  }

  private object PV0 extends ContractEnrichmentFactory {
    override def apply(coreOtherNodes: List[(LfActionNode, RollbackScope)]): ContractEnrichment = {
      val contractIdNodeMetadata = buildNodeMetadata(coreOtherNodes)
      contract => contract.copy(metadata = contractIdNodeMetadata(contract.contractId))
    }

    private def buildNodeMetadata(
        coreOtherNodes: List[(LfActionNode, RollbackScope)]
    ): Map[LfContractId, ContractMetadata] = {
      coreOtherNodes.mapFilter { case (node, _) =>
        usedContractIdMetadata(node)
      }.toMap
    }

    /** Uses maintainers for signatories and stakeholders of lookup (fixed for [[PV5]]) */
    private def usedContractIdMetadata(
        node: LfActionNode
    ): Option[(LfContractId, ContractMetadata)] = {
      node match {
        case _: LfNodeCreate => None
        case n: LfNodeFetch => Some(n.coid -> LfTransactionUtil.metadataFromFetch(n))
        case n: LfNodeExercises => Some(n.targetCoid -> LfTransactionUtil.metadataFromExercise(n))
        case nl: LfNodeLookupByKey =>
          nl.result.map(cid =>
            cid ->
              ContractMetadata.tryCreate(
                nl.keyMaintainers,
                nl.keyMaintainers,
                nl.versionedKeyOpt,
              )
          )
      }
    }
  }

  private object PV5 extends ContractEnrichmentFactory {
    override def apply(coreOtherNodes: List[(LfActionNode, RollbackScope)]): ContractEnrichment =
      identity
  }

}
