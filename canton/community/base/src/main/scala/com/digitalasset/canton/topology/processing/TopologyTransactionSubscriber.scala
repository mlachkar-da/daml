// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.topology.processing

import com.digitalasset.canton.SequencerCounter
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX.GenericSignedTopologyTransactionX
import com.digitalasset.canton.topology.transaction.{SignedTopologyTransaction, TopologyChangeOp}
import com.digitalasset.canton.tracing.TraceContext

trait TopologyTransactionProcessingSubscriberCommon {

  /** Move the most known timestamp ahead in future based of newly discovered information
    *
    * We don't know the most recent timestamp directly. However, we can guess it from two sources:
    * What was the timestamp of the latest topology transaction added? And what was the last processing timestamp.
    * We need to know both such that we can always deliver the latest valid set of topology information, and don't use
    * old snapshots.
    * Therefore, we expose the updateHead function on the public interface for initialisation purposes.
    *
    * @param effectiveTimestamp      sequencer timestamp + epsilon(sequencer timestamp)
    * @param approximateTimestamp    our current best guess of what the "best" timestamp is to get a valid current topology snapshot
    * @param potentialTopologyChange if true, the time advancement is related to a topology change that might have occurred or become effective
    */
  def updateHead(
      effectiveTimestamp: EffectiveTime,
      approximateTimestamp: ApproximateTime,
      potentialTopologyChange: Boolean,
  )(implicit traceContext: TraceContext): Unit = ()
}

trait TopologyTransactionProcessingSubscriber
    extends TopologyTransactionProcessingSubscriberCommon {

  def observed(
      sequencedTimestamp: SequencedTime,
      effectiveTimestamp: EffectiveTime,
      sequencerCounter: SequencerCounter,
      transactions: Seq[SignedTopologyTransaction[TopologyChangeOp]],
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[Unit]

}

trait TopologyTransactionProcessingSubscriberX
    extends TopologyTransactionProcessingSubscriberCommon {

  def observed(
      sequencedTimestamp: SequencedTime,
      effectiveTimestamp: EffectiveTime,
      sequencerCounter: SequencerCounter,
      transactions: Seq[GenericSignedTopologyTransactionX],
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[Unit]

}
