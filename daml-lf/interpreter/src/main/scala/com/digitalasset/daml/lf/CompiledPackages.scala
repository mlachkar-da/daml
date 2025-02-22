// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf

import com.daml.lf.data.Ref.PackageId
import com.daml.lf.language.Ast.{Package, PackageSignature}
import com.daml.lf.language.{PackageInterface, Util}
import com.daml.lf.speedy.SExpr.SDefinitionRef
import com.daml.lf.speedy.{Compiler, SDefinition}

/** Trait to abstract over a collection holding onto Daml-LF package definitions + the
  * compiled speedy expressions.
  */
private[lf] abstract class CompiledPackages(
    val compilerConfig: Compiler.Config
) {
  def getDefinition(dref: SDefinitionRef): Option[SDefinition]
  def packageIds: scala.collection.Set[PackageId]
  def pkgInterface: PackageInterface
  def definitions: PartialFunction[SDefinitionRef, SDefinition] =
    Function.unlift(this.getDefinition)

  final def compiler: Compiler = new Compiler(pkgInterface, compilerConfig)
}

/** Important: use the constructor only if you _know_ you have all the definitions! Otherwise
  * use the apply in the companion object, which will compile them for you.
  */
private[lf] final case class PureCompiledPackages(
    val packageIds: Set[PackageId],
    val pkgInterface: PackageInterface,
    val defns: Map[SDefinitionRef, SDefinition],
    override val compilerConfig: Compiler.Config,
) extends CompiledPackages(compilerConfig) {
  override def getDefinition(dref: SDefinitionRef): Option[SDefinition] = defns.get(dref)
}

private[lf] object PureCompiledPackages {

  /** Important: use this method only if you _know_ you have all the definitions! Otherwise
    * use the other apply, which will compile them for you.
    */
  def apply(
      packages: Map[PackageId, PackageSignature],
      defns: Map[SDefinitionRef, SDefinition],
      compilerConfig: Compiler.Config,
  ): PureCompiledPackages =
    new PureCompiledPackages(packages.keySet, new PackageInterface(packages), defns, compilerConfig)

  def build(
      packages: Map[PackageId, Package],
      compilerConfig: Compiler.Config,
  ): Either[String, PureCompiledPackages] = {
    Compiler
      .compilePackages(PackageInterface(packages), packages, compilerConfig)
      .map(apply(Util.toSignatures(packages), _, compilerConfig))
  }

  def assertBuild(
      packages: Map[PackageId, Package],
      compilerConfig: Compiler.Config,
  ): PureCompiledPackages =
    data.assertRight(build(packages, compilerConfig))

  def Empty(compilerConfig: Compiler.Config): PureCompiledPackages =
    PureCompiledPackages(Map.empty, Map.empty, compilerConfig)

}
