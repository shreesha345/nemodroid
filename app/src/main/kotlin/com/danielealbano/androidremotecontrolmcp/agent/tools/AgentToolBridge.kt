package com.danielealbano.androidremotecontrolmcp.agent.tools

import com.danielealbano.androidremotecontrolmcp.agent.llm.LlmImage
import com.danielealbano.androidremotecontrolmcp.agent.llm.LlmToolDefinition
import kotlinx.serialization.json.JsonObject

/** Outcome of one tool call. */
data class AgentToolResult(
    val text: String,
    val image: LlmImage?,
    val isError: Boolean,
)

/** Setup failure with a user-presentable message. */
class AgentToolBridgeException(
    message: String,
) : Exception(message)

/** Session over this app's MCP server restricted to [AgentToolProfile]. One session at a time. */
interface AgentToolBridge {
    /**
     * Opens a session (closing any previous one) and returns model-facing definitions of the enabled tools.
     * Failures, including timeouts, are returned; only the caller's own cancellation is thrown.
     */
    suspend fun open(): Result<List<LlmToolDefinition>>

    /**
     * Calls a tool of the open session by un-prefixed name. Only the caller's own cancellation is thrown.
     * A concurrent [close] aborts an in-flight call, which then returns an error result.
     */
    suspend fun call(
        toolName: String,
        arguments: JsonObject,
    ): AgentToolResult

    /** Closes the session. Idempotent. */
    suspend fun close()
}
