// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.topology.processing

import cats.Monoid
import com.digitalasset.canton.topology.processing.AuthorizedTopologyTransactionX.{
  AuthorizedIdentifierDelegationX,
  AuthorizedNamespaceDelegationX,
  AuthorizedUnionspaceDefinitionX,
}

import scala.collection.mutable

/** authorization data
  *
  * this type is returned by the authorization validator. it contains the series of transactions
  * that authorize a certain topology transaction.
  *
  * note that the order of the namespace delegation is in "authorization order".
  */
final case class AuthorizationChainX(
    identifierDelegation: Seq[AuthorizedIdentifierDelegationX],
    namespaceDelegations: Seq[AuthorizedNamespaceDelegationX],
    unionspaceDefinitions: Seq[AuthorizedUnionspaceDefinitionX],
) {

  def addIdentifierDelegation(aid: AuthorizedIdentifierDelegationX): AuthorizationChainX =
    copy(identifierDelegation = identifierDelegation :+ aid)

  def merge(other: AuthorizationChainX): AuthorizationChainX = {
    AuthorizationChainX(
      mergeUnique(this.identifierDelegation, other.identifierDelegation),
      mergeUnique(this.namespaceDelegations, other.namespaceDelegations),
      mergeUnique(this.unionspaceDefinitions, other.unionspaceDefinitions),
    )
  }

  private def mergeUnique[T](left: Seq[T], right: Seq[T]): Seq[T] = {
    mutable.LinkedHashSet.from(left).addAll(right).toSeq
  }

}

object AuthorizationChainX {
  val empty = AuthorizationChainX(Seq(), Seq(), Seq())

  implicit val monoid = new Monoid[AuthorizationChainX] {
    override def empty: AuthorizationChainX = AuthorizationChainX.empty

    override def combine(x: AuthorizationChainX, y: AuthorizationChainX): AuthorizationChainX =
      x.merge(y)
  }
}
