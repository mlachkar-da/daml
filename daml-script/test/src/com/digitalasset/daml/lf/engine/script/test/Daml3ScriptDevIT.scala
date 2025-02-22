// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf.engine.script
package test

import com.daml.bazeltools.BazelRunfiles
import com.daml.lf.data.Ref._
import com.daml.lf.engine.script.ScriptTimeMode
import com.daml.lf.language.LanguageMajorVersion
import com.daml.lf.language.LanguageMajorVersion.V2
import com.daml.lf.speedy.SValue._

import java.nio.file.Paths
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

class Daml3ScriptDevIT extends AsyncWordSpec with AbstractScriptTest with Inside with Matchers {
  final override protected lazy val devMode = true
  final override protected lazy val timeMode = ScriptTimeMode.WallClock

  override val majorLanguageVersion: LanguageMajorVersion = V2

  lazy val trySubmitConcurrentlyTestDarPath =
    BazelRunfiles.rlocation(Paths.get("compiler/damlc/tests/try-submit-concurrently-test.dar"))
  lazy val trySubmitConcurrentlyTestDar: CompiledDar =
    CompiledDar.read(trySubmitConcurrentlyTestDarPath, Runner.compilerConfig(majorLanguageVersion))

  override protected lazy val darFiles = List(
    trySubmitConcurrentlyTestDarPath
  )

  "trySubmitConcurrently" should {
    "return exactly one result per 'Commands' in the same order as the input" in {
      for {
        clients <- scriptClients()
        r <-
          run(
            clients,
            QualifiedName.assertFromString("Daml3ScriptTrySubmitConcurrently:resultsMatchInputs"),
            dar = trySubmitConcurrentlyTestDar,
          )
      } yield r shouldBe SUnit
    }

    "return exactly one successful result and n-1 errors when attempting to create n contracts with the same key" in {
      for {
        clients <- scriptClients()
        r <-
          run(
            clients,
            QualifiedName.assertFromString("Daml3ScriptTrySubmitConcurrently:keyCollision"),
            dar = trySubmitConcurrentlyTestDar,
          )
      } yield r shouldBe SUnit
    }

    "return exactly one successful result and n-1 errors when attempting to exercise n consuming choices on the same contract" in {
      for {
        clients <- scriptClients()
        r <-
          run(
            clients,
            QualifiedName.assertFromString("Daml3ScriptTrySubmitConcurrently:noDoubleSpend"),
            dar = trySubmitConcurrentlyTestDar,
          )
      } yield r shouldBe SUnit
    }
  }
}
