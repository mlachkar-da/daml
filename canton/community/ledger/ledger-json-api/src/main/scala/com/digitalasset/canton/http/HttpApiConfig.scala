// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.http

import com.digitalasset.canton.pureconfigutils.HttpServerConfig

final case class HttpApiConfig(
    server: HttpServerConfig = HttpServerConfig(),
    websocketConfig: Option[WebsocketConfig] = None,
    allowInsecureTokens: Boolean = false,
    staticContent: Option[StaticContentConfig] = None,
    debugLoggingOfHttpBodies: Boolean = false,
) {

  // TODO(#13303) Use directly instead of using JsonApiConfig as indirection
  def toConfig: JsonApiConfig = {
    JsonApiConfig(
      address = server.address,
      httpPort = server.port,
      portFile = server.portFile,
      staticContentConfig = staticContent,
      allowNonHttps = allowInsecureTokens,
      wsConfig = websocketConfig,
      debugLoggingOfHttpBodies = debugLoggingOfHttpBodies,
    )
  }
}
