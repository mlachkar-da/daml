// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.store.dao

import com.daml.lf.data.Ref.Party
import com.digitalasset.canton.logging.LoggingContextWithTrace
import com.digitalasset.canton.platform.store.cache.InMemoryFanoutBuffer
import com.digitalasset.canton.platform.store.dao.BufferedTransactionByIdReader.{
  FetchTransactionByIdFromPersistence,
  ToApiResponse,
}
import com.digitalasset.canton.platform.store.interfaces.TransactionLogUpdate.TransactionAccepted
import com.digitalasset.canton.tracing.Traced

import scala.concurrent.Future

/** Generic class that helps serving Ledger API point-wise lookups
  *  (TransactionService.{GetTransactionByEventId, GetTransactionById, GetFlatTransactionByEventId, GetFlatTransactionById})
  *  from either the in-memory fan-out buffer or from persistence.
  *
  * @param inMemoryFanoutBuffer The in-memory fan-out buffer.
  * @param fetchFromPersistence Fetch a transaction by id from persistence.
  * @param toApiResponse Convert a [[com.digitalasset.canton.platform.store.interfaces.TransactionLogUpdate.TransactionAccepted]] to a specific API response
  *                      while also filtering for visibility.
  * @tparam API_RESPONSE The Ledger API response type.
  */
class BufferedTransactionByIdReader[API_RESPONSE](
    inMemoryFanoutBuffer: InMemoryFanoutBuffer,
    fetchFromPersistence: FetchTransactionByIdFromPersistence[API_RESPONSE],
    toApiResponse: ToApiResponse[API_RESPONSE],
) {

  /** Serves processed and filtered transaction from the buffer by transaction id,
    * with fallback to a persistence fetch if the transaction is not anymore in the buffer
    * (i.e. it was evicted)
    *
    * @param transactionId The transaction id.
    * @param requestingParties Parties requesting the transaction lookup
    * @param loggingContext The logging context
    * @return A future wrapping the API response if found.
    */
  def fetch(transactionId: String, requestingParties: Set[Party])(implicit
      loggingContext: LoggingContextWithTrace
  ): Future[Option[API_RESPONSE]] =
    inMemoryFanoutBuffer.lookup(transactionId) match {
      case Some(value) => toApiResponse(value, requestingParties, loggingContext)
      case None =>
        fetchFromPersistence(transactionId, requestingParties, loggingContext)
    }
}

object BufferedTransactionByIdReader {
  trait FetchTransactionByIdFromPersistence[API_RESPONSE] {
    def apply(
        transactionId: String,
        requestingParties: Set[Party],
        loggingContext: LoggingContextWithTrace,
    ): Future[Option[API_RESPONSE]]
  }

  trait ToApiResponse[API_RESPONSE] {
    def apply(
        transactionAccepted: Traced[TransactionAccepted],
        requestingParties: Set[Party],
        loggingContext: LoggingContextWithTrace,
    ): Future[Option[API_RESPONSE]]
  }
}
