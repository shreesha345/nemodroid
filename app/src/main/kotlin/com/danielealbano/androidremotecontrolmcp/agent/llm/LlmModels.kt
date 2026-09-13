package com.danielealbano.androidremotecontrolmcp.agent.llm

import kotlinx.serialization.json.JsonObject

/** Closing tag of model reasoning; text before its last occurrence is never treated as a reply. */
internal const val THINK_END_TAG = "</think>"

/** Connection parameters for an OpenAI-compatible chat completions endpoint. */
data class LlmEndpoint(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
) {
    /** Masks [apiKey] so the key never reaches logs through string interpolation. */
    override fun toString(): String =
        "LlmEndpoint(baseUrl=$baseUrl, apiKey=${if (apiKey.isEmpty()) "" else "***"}, model=$model)"
}

/** Base64-encoded image attached to a user message. */
data class LlmImage(
    val base64: String,
    val mimeType: String,
)

/** A function the model may call; [parameters] is a JSON Schema object. */
data class LlmToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/** A function call requested by the model. */
data class LlmToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
)

/** Chat history entry. */
sealed class LlmMessage {
    data class System(
        val text: String,
    ) : LlmMessage()

    data class User(
        val text: String,
        val image: LlmImage? = null,
    ) : LlmMessage()

    data class Assistant(
        val text: String?,
        val toolCalls: List<LlmToolCall>,
    ) : LlmMessage()

    data class ToolResult(
        val toolCallId: String,
        val text: String,
    ) : LlmMessage()
}

/** Parsed model reply. */
data class LlmResponse(
    val text: String?,
    val toolCalls: List<LlmToolCall>,
)

/** LLM request failure carrying a user-presentable message. */
class LlmException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
