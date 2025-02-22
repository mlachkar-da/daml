// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.api.testtool

import com.daml.ledger.api.testtool.infrastructure.LedgerTestSuite
import com.daml.ledger.api.tls.TlsConfiguration

object Tests {
  def default(timeoutScaleFactor: Double): Vector[LedgerTestSuite] =
    suites.v1_14.default(timeoutScaleFactor)

  def optional(tlsConfig: Option[TlsConfiguration]): Vector[LedgerTestSuite] =
    suites.v1_14.optional(tlsConfig)
}
