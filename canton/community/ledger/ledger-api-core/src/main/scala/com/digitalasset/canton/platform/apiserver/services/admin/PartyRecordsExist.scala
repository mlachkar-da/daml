// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.apiserver.services.admin

import com.daml.lf.data.Ref
import com.digitalasset.canton.ledger.api.domain.IdentityProviderId
import com.digitalasset.canton.logging.LoggingContextWithTrace
import com.digitalasset.canton.platform.localstore.api.PartyRecordStore

import scala.concurrent.Future

class PartyRecordsExist(partyRecordStore: PartyRecordStore) {

  def filterPartiesExistingInPartyRecordStore(id: IdentityProviderId, parties: Set[Ref.Party])(
      implicit loggingContext: LoggingContextWithTrace
  ): Future[Set[Ref.Party]] =
    partyRecordStore.filterExistingParties(parties, id)

  def filterPartiesExistingInPartyRecordStore(parties: Set[Ref.Party])(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[Set[Ref.Party]] =
    partyRecordStore.filterExistingParties(parties)

}
