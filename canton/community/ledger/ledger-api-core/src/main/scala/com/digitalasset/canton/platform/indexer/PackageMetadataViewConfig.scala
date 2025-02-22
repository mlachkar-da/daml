// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.indexer

import com.digitalasset.canton.platform.indexer.PackageMetadataViewConfig.{
  DefaultInitLoadParallelism,
  DefaultInitProcessParallelism,
  DefaultInitTakesTooLongInitialDelay,
  DefaultInitTakesTooLongInterval,
}

import scala.concurrent.duration.{FiniteDuration, *}

final case class PackageMetadataViewConfig(
    initLoadParallelism: Int = DefaultInitLoadParallelism,
    initProcessParallelism: Int = DefaultInitProcessParallelism,
    initTakesTooLongInitialDelay: FiniteDuration = DefaultInitTakesTooLongInitialDelay,
    initTakesTooLongInterval: FiniteDuration = DefaultInitTakesTooLongInterval,
)

object PackageMetadataViewConfig {
  val DefaultInitLoadParallelism: Int = 16
  val DefaultInitProcessParallelism: Int = 16
  val DefaultInitTakesTooLongInitialDelay: FiniteDuration = 1.minute
  val DefaultInitTakesTooLongInterval: FiniteDuration = 10.seconds

  val Default = PackageMetadataViewConfig()
}
