package com.danielealbano.androidremotecontrolmcp.agent.tools

import com.danielealbano.androidremotecontrolmcp.agent.llm.LlmImage
import com.danielealbano.androidremotecontrolmcp.agent.llm.LlmToolDefinition
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Returns why the agent cannot use the loopback MCP server, or null when it can. */
internal fun loopbackPreconditionError(
    status: ServerStatus,
    config: ServerConfig,
): String? =
    when {
        status !is ServerStatus.Running -> "Start the MCP server before running the agent"
        // McpServerService does not populate Running.httpsEnabled, so the persisted config is checked as well.
        status.httpsEnabled || config.httpsEnabled -> "The on-device agent needs the MCP server on HTTP; disable HTTPS"
        config.bearerTokenEnabled && config.bearerToken.isEmpty() ->
            "Set a bearer token in Access settings so the on-device agent can connect"
        !config.bearerTokenEnabled && config.oauthEnabled ->
            "Enable bearer token authentication so the on-device agent can connect"
        else -> null
    }

/** Model-facing definition for a profile tool, or null when [Tool.name] is not a profile tool under [prefix]. */
internal fun Tool.toAgentDefinition(prefix: String): LlmToolDefinition? {
    val shortName = name.removePrefix(prefix)
    if (!name.startsWith(prefix) || shortName !in AgentToolProfile.TOOL_NAMES) return null
    return LlmToolDefinition(
        name = shortName,
        description = description.orEmpty(),
        parameters =
            buildJsonObject {
                put("type", "object")
                put("properties", inputSchema.properties ?: JsonObject(emptyMap()))
                putJsonArray("required") { inputSchema.required.orEmpty().forEach { add(it) } }
            },
    )
}

internal fun CallToolResult.toAgentResult(): AgentToolResult =
    AgentToolResult(
        text = content.filterIsInstance<TextContent>().joinToString("\n") { it.text },
        image = content.filterIsInstance<ImageContent>().firstOrNull()?.let { LlmImage(it.data, it.mimeType) },
        isError = isError == true,
    )

internal fun agentToolError(message: String): AgentToolResult =
    AgentToolResult(text = message, image = null, isError = true)
