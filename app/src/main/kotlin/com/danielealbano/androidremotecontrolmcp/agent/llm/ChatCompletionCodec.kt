package com.danielealbano.androidremotecontrolmcp.agent.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Encodes requests to and decodes responses from the OpenAI `/chat/completions` wire format. */
internal object ChatCompletionCodec {
    private const val TEMPERATURE = 0.2
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeRequest(
        model: String,
        messages: List<LlmMessage>,
        tools: List<LlmToolDefinition>,
    ): JsonObject =
        buildJsonObject {
            put("model", model)
            put("temperature", TEMPERATURE)
            putJsonArray("messages") { messages.forEach { add(encodeMessage(it)) } }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") { tools.forEach { add(encodeTool(it)) } }
            }
        }

    /** @throws LlmException when [body] is not a chat completion with `choices[0].message`. */
    fun decodeResponse(body: String): LlmResponse {
        val message = parseMessage(body) ?: throw LlmException("LLM response has no choices[0].message")
        return LlmResponse(
            text = decodeContent(message["content"]),
            toolCalls = (message["tool_calls"] as? JsonArray)?.let { decodeToolCalls(it) }.orEmpty(),
        )
    }

    private fun parseMessage(body: String): JsonObject? =
        try {
            json
                .parseToJsonElement(body)
                .jsonObject["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
        } catch (e: IllegalArgumentException) {
            throw LlmException("LLM response is not a valid chat completion", e)
        }

    /** String content, or the text parts of array content joined by newlines; null when absent or empty. */
    private fun decodeContent(element: JsonElement?): String? =
        when (element) {
            is JsonPrimitive -> element.contentOrNull?.ifEmpty { null }
            is JsonArray ->
                element
                    .mapNotNull { part -> ((part as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }
                    .joinToString("\n")
                    .ifEmpty { null }
            else -> null
        }

    private fun decodeToolCalls(calls: JsonArray): List<LlmToolCall> =
        try {
            calls.mapIndexed { index, call ->
                val function = call.jsonObject.getValue("function").jsonObject
                LlmToolCall(
                    id = call.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "call_$index",
                    name = function.getValue("name").jsonPrimitive.content,
                    arguments = decodeArguments(function["arguments"]),
                )
            }
        } catch (e: IllegalArgumentException) {
            throw LlmException("LLM returned a malformed tool call", e)
        } catch (e: NoSuchElementException) {
            throw LlmException("LLM returned a malformed tool call", e)
        }

    private fun decodeArguments(element: JsonElement?): JsonObject =
        when (element) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> element
            is JsonPrimitive -> json.parseToJsonElement(element.content.ifBlank { "{}" }).jsonObject
            else -> throw LlmException("Tool call arguments must be a JSON object")
        }

    private fun encodeMessage(message: LlmMessage): JsonObject =
        when (message) {
            is LlmMessage.System -> textMessage("system", message.text)
            is LlmMessage.User -> encodeUser(message)
            is LlmMessage.Assistant ->
                buildJsonObject {
                    put("role", "assistant")
                    // Strict servers reject null content on assistant messages without tool_calls.
                    put("content", message.text.orEmpty())
                    if (message.toolCalls.isNotEmpty()) {
                        putJsonArray("tool_calls") { message.toolCalls.forEach { add(encodeToolCall(it)) } }
                    }
                }
            is LlmMessage.ToolResult ->
                buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", message.toolCallId)
                    put("content", message.text)
                }
        }

    private fun encodeUser(message: LlmMessage.User): JsonObject {
        val image = message.image ?: return textMessage("user", message.text)
        return buildJsonObject {
            put("role", "user")
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", message.text)
                }
                addJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") { put("url", "data:${image.mimeType};base64,${image.base64}") }
                }
            }
        }
    }

    private fun textMessage(
        role: String,
        text: String,
    ): JsonObject =
        buildJsonObject {
            put("role", role)
            put("content", text)
        }
}

private fun encodeTool(tool: LlmToolDefinition): JsonObject =
    buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", tool.name)
            put("description", tool.description)
            put("parameters", tool.parameters)
        }
    }

private fun encodeToolCall(call: LlmToolCall): JsonObject =
    buildJsonObject {
        put("id", call.id)
        put("type", "function")
        putJsonObject("function") {
            put("name", call.name)
            put("arguments", call.arguments.toString())
        }
    }
