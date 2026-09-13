package com.danielealbano.androidremotecontrolmcp.agent.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Extracts tool calls written into a text reply: XML (`<tool_call><function=name><parameter=p>value</parameter>
 * </function></tool_call>`, tags optional), JSON inside `<tool_call>` tags, or one bare / fenced JSON object or array.
 */
internal object ToolCallTextParser {
    private const val STRING_TYPE = "string"
    private val json = Json { ignoreUnknownKeys = true }
    private val taggedCall = Regex("<tool_call>\\s*(.*?)\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
    private val xmlFunction = Regex("<function=([^>\\s]+)>(.*?)</function>", RegexOption.DOT_MATCHES_ALL)
    private val xmlParameter = Regex("<parameter=([^>\\s]+)>\\n?(.*?)\\n?</parameter>", RegexOption.DOT_MATCHES_ALL)

    /** Parses calls from [text]; [tools] supply the parameter schema types used to type XML values. */
    fun parse(
        text: String,
        tools: List<LlmToolDefinition>,
    ): List<LlmToolCall> {
        val visible = text.substringAfterLast(THINK_END_TAG).trim()
        val tagged = taggedCall.findAll(visible).map { it.groupValues[1] }.toList()
        return tagged
            .ifEmpty { listOf(stripCodeFence(visible)) }
            .flatMap { parseBody(it, tools) }
            .mapIndexed { index, (name, arguments) ->
                LlmToolCall(id = "text_call_$index", name = name, arguments = arguments)
            }
    }

    /** Returns [text] without reasoning and without any parsed call, or null when nothing remains. */
    fun stripToolCalls(text: String): String? {
        val withoutTagged = taggedCall.replace(text.substringAfterLast(THINK_END_TAG), "")
        val remaining = xmlFunction.replace(withoutTagged, "").trim()
        val remainingIsCall = parseJson(stripCodeFence(remaining)).isNotEmpty()
        return remaining.takeUnless { it.isEmpty() || remainingIsCall }
    }

    private fun parseBody(
        body: String,
        tools: List<LlmToolDefinition>,
    ): List<Pair<String, JsonObject>> {
        val xmlCalls =
            xmlFunction
                .findAll(body)
                .map { it.groupValues[1] to parseXmlParameters(it.groupValues[1], it.groupValues[2], tools) }
                .toList()
        return xmlCalls.ifEmpty { parseJson(body) }
    }

    private fun parseXmlParameters(
        function: String,
        body: String,
        tools: List<LlmToolDefinition>,
    ): JsonObject {
        val properties = tools.firstOrNull { it.name == function }?.parameters?.get("properties") as? JsonObject
        return JsonObject(
            xmlParameter.findAll(body).associate { match ->
                val name = match.groupValues[1]
                name to parseParameterValue(match.groupValues[2], propertyType(properties, name))
            },
        )
    }

    private fun propertyType(
        properties: JsonObject?,
        name: String,
    ): String? = ((properties?.get(name) as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull

    /** Schema type `string` or unknown keeps the raw text; other types are parsed as JSON when possible. */
    private fun parseParameterValue(
        raw: String,
        schemaType: String?,
    ): JsonElement =
        if (schemaType == null || schemaType == STRING_TYPE) {
            JsonPrimitive(raw)
        } else {
            runCatching { json.parseToJsonElement(raw.trim()) }.getOrNull() ?: JsonPrimitive(raw)
        }

    private fun parseJson(body: String): List<Pair<String, JsonObject>> =
        when (val element = runCatching { json.parseToJsonElement(body) }.getOrNull()) {
            is JsonObject -> listOfNotNull(toNamedArguments(element))
            is JsonArray -> element.mapNotNull { (it as? JsonObject)?.let(::toNamedArguments) }
            else -> emptyList()
        }

    private fun toNamedArguments(obj: JsonObject): Pair<String, JsonObject>? {
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull
        val arguments = (obj["arguments"] ?: obj["parameters"]) as? JsonObject
        return if (name.isNullOrBlank()) null else name to (arguments ?: JsonObject(emptyMap()))
    }

    private fun stripCodeFence(text: String): String =
        text
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
}
