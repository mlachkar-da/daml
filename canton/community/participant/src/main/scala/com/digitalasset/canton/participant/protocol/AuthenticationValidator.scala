// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.protocol

import cats.syntax.parallel.*
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.crypto.{DomainSnapshotSyncCryptoApi, Signature}
import com.digitalasset.canton.data.{SubmitterMetadata, TransactionViewTree, ViewPosition}
import com.digitalasset.canton.protocol.RequestId
import com.digitalasset.canton.util.FutureInstances.*
import com.digitalasset.canton.util.ShowUtil.*

import scala.concurrent.{ExecutionContext, Future}

class AuthenticationValidator()(implicit
    executionContext: ExecutionContext
) {

  def verifyViewSignatures(
      requestId: RequestId,
      rootViews: NonEmpty[Seq[(TransactionViewTree, Option[Signature])]],
      snapshot: DomainSnapshotSyncCryptoApi,
  ): Future[Map[ViewPosition, String]] = {

    def verifySignature(
        viewWithSignature: (TransactionViewTree, Option[Signature])
    ): Future[Option[(ViewPosition, String)]] = {

      val (view, signatureO) = viewWithSignature

      def err(details: String): String =
        show"Received a request with id $requestId with a view that is not correctly authenticated. Rejecting request...\n$details"

      view.tree.submitterMetadata.unwrap match {
        // RootHash -> is a blinded tree
        case Left(_) => Future(None)
        // SubmitterMetadata -> information on the submitter of the tree
        case Right(submitterMetadata: SubmitterMetadata) =>
          signatureO match {
            case Some(signature) =>
              (for {
                // check for an invalid signature
                _ <- snapshot.verifySignature(
                  view.rootHash.unwrap,
                  submitterMetadata.submitterParticipant,
                  signature,
                )
              } yield ()).value.map {
                _.swap.toOption.map(cause =>
                  (
                    view.viewPosition,
                    err(s"View ${view.viewPosition} has an invalid signature: ${cause.show}."),
                  )
                )
              }

            case None =>
              // the signature is missing
              Future(
                Some(
                  (
                    view.viewPosition,
                    err(s"View ${view.viewPosition} is missing a signature."),
                  )
                )
              )

          }
      }
    }

    for {
      decryptionResult <- rootViews.forgetNE.parTraverseFilter(verifySignature)
    } yield decryptionResult.toMap
  }
}
