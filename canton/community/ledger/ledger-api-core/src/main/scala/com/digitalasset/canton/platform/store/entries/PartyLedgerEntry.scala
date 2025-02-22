// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.store.entries

import com.daml.lf.data.Time.Timestamp
import com.digitalasset.canton.ledger.participant.state.index.v2.IndexerPartyDetails
import com.digitalasset.canton.platform.SubmissionId

private[platform] sealed abstract class PartyLedgerEntry() extends Product with Serializable {
  val submissionIdOpt: Option[SubmissionId]
  val recordTime: Timestamp
}

private[platform] object PartyLedgerEntry {

  final case class AllocationAccepted(
      submissionIdOpt: Option[SubmissionId],
      recordTime: Timestamp,
      partyDetails: IndexerPartyDetails,
  ) extends PartyLedgerEntry

  final case class AllocationRejected(
      submissionId: SubmissionId,
      recordTime: Timestamp,
      reason: String,
  ) extends PartyLedgerEntry {
    override val submissionIdOpt: Option[SubmissionId] = Some(submissionId)
  }
}
