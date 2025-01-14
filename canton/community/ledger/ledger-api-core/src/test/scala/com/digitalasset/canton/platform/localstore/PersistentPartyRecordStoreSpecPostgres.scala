// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.localstore

import com.digitalasset.canton.platform.store.backend.StorageBackendProviderPostgres
import org.scalatest.freespec.AsyncFreeSpec

class PersistentPartyRecordStoreSpecPostgres
    extends AsyncFreeSpec
    with PersistentPartyRecordStoreTests
    with StorageBackendProviderPostgres
