// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.apiserver

import com.daml.ledger.api.v1.commands.Commands
import com.daml.ledger.api.v2.commands.Commands as CommandsV2
import com.daml.tracing.{SpanAttribute, Telemetry, TelemetryContext}
import com.digitalasset.canton.tracing.TraceContext

package object services {
  def getAnnotedCommandTraceContext(
      commands: Option[Commands],
      telemetry: Telemetry,
  ): TraceContext = {
    val telemetryContext: TelemetryContext =
      telemetry.contextFromGrpcThreadLocalContext()
    commands.foreach { commands =>
      telemetryContext
        .setAttribute(SpanAttribute.ApplicationId, commands.applicationId)
        .setAttribute(SpanAttribute.CommandId, commands.commandId)
        .setAttribute(SpanAttribute.Submitter, commands.party)
        .setAttribute(SpanAttribute.WorkflowId, commands.workflowId)
    }
    TraceContext.fromDamlTelemetryContext(telemetry.contextFromGrpcThreadLocalContext())
  }

  def getAnnotedCommandTraceContextV2(
      commands: Option[CommandsV2],
      telemetry: Telemetry,
  ): TraceContext = {
    val telemetryContext: TelemetryContext =
      telemetry.contextFromGrpcThreadLocalContext()
    commands.foreach { commands =>
      telemetryContext
        .setAttribute(SpanAttribute.ApplicationId, commands.applicationId)
        .setAttribute(SpanAttribute.CommandId, commands.commandId)
        .setAttribute(SpanAttribute.Submitter, commands.party)
        .setAttribute(SpanAttribute.WorkflowId, commands.workflowId)
    }
    TraceContext.fromDamlTelemetryContext(telemetry.contextFromGrpcThreadLocalContext())
  }

}
