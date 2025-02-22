// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.protocol

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.crypto.DomainSyncCryptoClient
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.protocol.DomainParameters.MaxRequestSize
import com.digitalasset.canton.time.{Clock, NonNegativeFiniteDuration}
import com.digitalasset.canton.topology.{
  DomainId,
  SequencerId,
  TestingIdentityFactory,
  TestingTopology,
}

/** Domain parameters used for unit testing with sane default values. */
object TestDomainParameters {
  def identityFactory(
      loggerFactory: NamedLoggerFactory,
      clock: Clock,
      transformDefaults: DynamicDomainParameters => DynamicDomainParameters =
        identity[DynamicDomainParameters],
  ) = TestingIdentityFactory(
    TestingTopology(),
    loggerFactory,
    transformDefaults(DynamicDomainParameters.initialValues(clock, BaseTest.testedProtocolVersion)),
  )

  def domainSyncCryptoApi(
      domainId: DomainId,
      loggerFactory: NamedLoggerFactory,
      clock: Clock,
      transformDefaults: DynamicDomainParameters => DynamicDomainParameters =
        identity[DynamicDomainParameters],
  ): DomainSyncCryptoClient =
    identityFactory(loggerFactory, clock, transformDefaults).forOwnerAndDomain(
      SequencerId(domainId),
      domainId,
    )

  val defaultDynamic: DynamicDomainParameters =
    DynamicDomainParameters.initialValues(
      topologyChangeDelay = NonNegativeFiniteDuration.tryOfMillis(250),
      BaseTest.testedProtocolVersion,
    )

  def defaultDynamic(
      maxRatePerParticipant: NonNegativeInt,
      maxRequestSize: MaxRequestSize,
  ): DynamicDomainParameters =
    DynamicDomainParameters.tryInitialValues(
      topologyChangeDelay = NonNegativeFiniteDuration.tryOfMillis(250),
      protocolVersion = BaseTest.testedProtocolVersion,
      maxRatePerParticipant = maxRatePerParticipant,
      maxRequestSize = maxRequestSize,
    )
}
