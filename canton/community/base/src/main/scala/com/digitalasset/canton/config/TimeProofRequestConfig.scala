// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.config

import cats.syntax.option.*
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.canton.serialization.ProtoConverter.ParsingResult
import com.digitalasset.canton.time.admin.v0

/** @param initialRetryDelay The initial retry delay if the request to send a sequenced event fails
  * @param maxRetryDelay The max retry delay if the request to send a sequenced event fails
  * @param maxSequencingDelay If our request for a sequenced event was successful, how long should we wait
  *                                      to observe it from the sequencer before starting a new request.
  */
final case class TimeProofRequestConfig(
    initialRetryDelay: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMillis(200),
    maxRetryDelay: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(5),
    maxSequencingDelay: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(10),
) {
  private[config] def toProtoV0: v0.TimeProofRequestConfig = v0.TimeProofRequestConfig(
    initialRetryDelay.toProtoPrimitive.some,
    maxRetryDelay.toProtoPrimitive.some,
    maxSequencingDelay.toProtoPrimitive.some,
  )
}

object TimeProofRequestConfig {
  private[config] def fromProtoV0(
      configP: v0.TimeProofRequestConfig
  ): ParsingResult[TimeProofRequestConfig] =
    for {
      initialRetryDelay <- ProtoConverter.parseRequired(
        NonNegativeFiniteDuration.fromProtoPrimitive("initialRetryDelay"),
        "initialRetryDelay",
        configP.initialRetryDelay,
      )
      maxRetryDelay <- ProtoConverter.parseRequired(
        NonNegativeFiniteDuration.fromProtoPrimitive("maxRetryDelay"),
        "maxRetryDelay",
        configP.maxRetryDelay,
      )
      maxSequencingDelay <- ProtoConverter.parseRequired(
        NonNegativeFiniteDuration.fromProtoPrimitive("maxSequencingDelay"),
        "maxSequencingDelay",
        configP.maxSequencingDelay,
      )
    } yield TimeProofRequestConfig(initialRetryDelay, maxRetryDelay, maxSequencingDelay)
}
