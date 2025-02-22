// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf
package transaction

import com.daml.lf.crypto.Hash
import com.daml.lf.data.Ref
import com.daml.lf.data.Ref.TypeConName
import com.daml.lf.value.Value

/** Useful in various circumstances -- basically this is what a ledger implementation must use as
  * a key. The 'hash' is guaranteed to be stable over time.
  */
final class GlobalKey private (
    val templateId: Ref.TypeConName,
    val key: Value,
    val hash: crypto.Hash,
) extends data.NoCopy {
  override def equals(obj: Any): Boolean = obj match {
    case that: GlobalKey => this.hash == that.hash
    case _ => false
  }

  // Ready for refactoring where packageId becomes optional (#14486)
  def packageId: Option[Ref.PackageId] = Some(templateId.packageId)
  def qualifiedName: Ref.QualifiedName = templateId.qualifiedName

  override def hashCode(): Int = hash.hashCode()

  override def toString: String = s"GlobalKey($templateId, $key)"
}

object GlobalKey {

  // TODO https://github.com/digital-asset/daml/issues/17732
  //   For temporary backward compatibility, will be deprecated
  def build(templateId: Ref.TypeConName, key: Value): Either[crypto.Hash.HashingError, GlobalKey] =
    build(templateId, key, shared = false)

  // TODO https://github.com/digital-asset/daml/issues/17732
  //   For temporary backward compatibility, will be deprecated
  def assertBuild(templateId: Ref.TypeConName, value: Value): GlobalKey =
    assertBuild(templateId, value, shared = false)

  def assertWithRenormalizedValue(key: GlobalKey, value: Value): GlobalKey = {
    if (
      key.key != value &&
      Hash.assertHashContractKey(key.templateId, value, true) != key.hash &&
      Hash.assertHashContractKey(key.templateId, value, false) != key.hash
    ) {
      throw new IllegalArgumentException(
        s"Hash must not change as a result of value renormalization key=$key, value=$value"
      )
    }

    new GlobalKey(key.templateId, value, key.hash)

  }

  // Will fail if key contains contract ids
  def build(
      templateId: Ref.TypeConName,
      key: Value,
      shared: Boolean,
  ): Either[crypto.Hash.HashingError, GlobalKey] =
    crypto.Hash
      .hashContractKey(templateId, key, shared)
      .map(new GlobalKey(templateId, key, _))

  // Like `build` but,  in case of error, throws an exception instead of returning a message.
  @throws[IllegalArgumentException]
  def assertBuild(templateId: Ref.TypeConName, key: Value, shared: Boolean): GlobalKey =
    data.assertRight(build(templateId, key, shared).left.map(_.msg))

  private[lf] def unapply(globalKey: GlobalKey): Some[(TypeConName, Value)] =
    Some((globalKey.templateId, globalKey.key))

  def isShared(key: GlobalKey): Boolean =
    Hash.hashContractKey(key.templateId, key.key, true) == Right(key.hash)

}

final case class GlobalKeyWithMaintainers(
    globalKey: GlobalKey,
    maintainers: Set[Ref.Party],
) {
  def value: Value = globalKey.key
}

object GlobalKeyWithMaintainers {

  // TODO https://github.com/digital-asset/daml/issues/17732
  //   For temporary backward compatibility, will be deprecated
  def assertBuild(
      templateId: Ref.TypeConName,
      value: Value,
      maintainers: Set[Ref.Party],
  ): GlobalKeyWithMaintainers =
    assertBuild(templateId, value, maintainers, shared = false)

  def assertBuild(
      templateId: Ref.TypeConName,
      value: Value,
      maintainers: Set[Ref.Party],
      shared: Boolean,
  ): GlobalKeyWithMaintainers =
    data.assertRight(build(templateId, value, maintainers, shared).left.map(_.msg))

  def build(
      templateId: Ref.TypeConName,
      value: Value,
      maintainers: Set[Ref.Party],
      shared: Boolean,
  ): Either[Hash.HashingError, GlobalKeyWithMaintainers] =
    GlobalKey.build(templateId, value, shared).map(GlobalKeyWithMaintainers(_, maintainers))
}

/** Controls whether the engine should error out when it encounters duplicate keys.
  * This is always turned on with the exception of Canton which allows turning this on or off
  * and forces it to be turned off in multi-domain mode.
  */
sealed abstract class ContractKeyUniquenessMode extends Product with Serializable

object ContractKeyUniquenessMode {

  /** Disable key uniqueness checks and only consider byKey operations.
    * Note that no stable semantics are provided for off mode.
    */
  case object Off extends ContractKeyUniquenessMode

  /** Considers all nodes mentioning keys as byKey operations and checks for contract key uniqueness. */
  case object Strict extends ContractKeyUniquenessMode
}
