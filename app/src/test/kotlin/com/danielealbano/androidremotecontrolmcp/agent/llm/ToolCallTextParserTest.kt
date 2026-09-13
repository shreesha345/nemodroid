package com.danielealbano.androidremotecontrolmcp.agent.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ToolCallTextParser")
class ToolCallTextParserTest {
    private val tools =
        listOf(
            tool("tap_node", "node_id" to "string"),
            tool("type_append_text", "node_id" to "string", "text" to "string"),
            tool("tap", "x" to "number", "y" to "number"),
            tool("open_app", "package_id" to "string", "extras" to "object"),
        )

    private fun tool(
        name: String,
        vararg properties: Pair<String, String>,
    ): LlmToolDefinition =
        LlmToolDefinition(
            name = name,
            description = "",
            parameters =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        properties.forEach { (property, type) -> putJsonObject(property) { put("type", type) } }
                    }
                },
        )

    private fun xmlCall(
        function: String,
        vararg parameters: Pair<String, String>,
    ): String =
        buildString {
            append("<tool_call>\n<function=").append(function).append(">\n")
            parameters.forEach { (name, value) -> append("<parameter=$name>\n$value\n</parameter>\n") }
            append("</function>\n</tool_call>")
        }

    private fun stringValue(
        arguments: JsonObject,
        name: String,
    ): JsonPrimitive = arguments.getValue(name) as JsonPrimitive

    @Test
    fun `parses nemotron xml tool call`() {
        val calls = ToolCallTextParser.parse(xmlCall("tap_node", "node_id" to "node_ab12"), tools)

        val call = calls.single()
        assertEquals("tap_node", call.name)
        assertTrue(stringValue(call.arguments, "node_id").isString)
        assertEquals("node_ab12", stringValue(call.arguments, "node_id").content)
    }

    @Test
    fun `parses untagged xml function`() {
        val text = "<function=tap_node>\n<parameter=node_id>\nnode_1\n</parameter>\n</function>"

        val call = ToolCallTextParser.parse(text, tools).single()

        assertEquals("tap_node", call.name)
        assertEquals("node_1", stringValue(call.arguments, "node_id").content)
    }

    @Test
    fun `string typed xml parameters stay strings`() {
        listOf("2024", "true", "null", """{"a":1}""").forEach { value ->
            val call = ToolCallTextParser.parse(xmlCall("type_append_text", "text" to value), tools).single()

            val text = stringValue(call.arguments, "text")
            assertTrue(text.isString, "expected string for '$value'")
            assertEquals(value, text.content)
        }
    }

    @Test
    fun `non string typed xml parameters are parsed as json`() {
        val tap = ToolCallTextParser.parse(xmlCall("tap", "x" to "120", "y" to "abc"), tools).single()
        val openApp = ToolCallTextParser.parse(xmlCall("open_app", "extras" to """{"k":"v"}"""), tools).single()

        assertFalse(stringValue(tap.arguments, "x").isString)
        assertEquals(120, stringValue(tap.arguments, "x").int)
        assertTrue(stringValue(tap.arguments, "y").isString)
        assertEquals(buildJsonObject { put("k", "v") }, openApp.arguments.getValue("extras"))
    }

    @Test
    fun `unknown tool or parameter keeps raw string`() {
        val call = ToolCallTextParser.parse(xmlCall("mystery", "count" to "5"), tools).single()

        assertTrue(stringValue(call.arguments, "count").isString)
        assertEquals("5", stringValue(call.arguments, "count").content)
    }

    @Test
    fun `multi line string parameter preserved`() {
        val call = ToolCallTextParser.parse(xmlCall("type_append_text", "text" to "line1\nline2"), tools).single()

        assertEquals("line1\nline2", stringValue(call.arguments, "text").content)
    }

    @Test
    fun `parses multiple xml calls in order`() {
        val text = xmlCall("tap_node", "node_id" to "a") + "\n" + xmlCall("tap_node", "node_id" to "b")

        val calls = ToolCallTextParser.parse(text, tools)

        assertEquals(listOf("text_call_0", "text_call_1"), calls.map { it.id })
        assertEquals(listOf("a", "b"), calls.map { stringValue(it.arguments, "node_id").content })
    }

    @Test
    fun `parses json inside tool_call tags with nested arguments`() {
        val text = """<tool_call>{"name":"tap","arguments":{"x":1,"y":{"z":2}}}</tool_call>"""

        val call = ToolCallTextParser.parse(text, tools).single()

        assertEquals("tap", call.name)
        assertEquals(1, call.arguments.getValue("x").jsonPrimitive.int)
        assertTrue(call.arguments.getValue("y") is JsonObject)
    }

    @Test
    fun `parses bare json object`() {
        val call = ToolCallTextParser.parse("""{"name":"press_back","arguments":{}}""", tools).single()

        assertEquals("press_back", call.name)
    }

    @Test
    fun `parses bare json array`() {
        val calls = ToolCallTextParser.parse("""[{"name":"a","arguments":{}},{"name":"b"}]""", tools)

        assertEquals(listOf("a", "b"), calls.map { it.name })
    }

    @Test
    fun `parses fenced json object`() {
        val call = ToolCallTextParser.parse("```json\n{\"name\":\"press_home\"}\n```", tools).single()

        assertEquals("press_home", call.name)
    }

    @Test
    fun `ignores text before last think end tag`() {
        val text =
            "plan {\"name\":\"wrong\"}</think>\n" +
                """<tool_call>{"name":"tap","arguments":{"x":1}}</tool_call>"""

        val calls = ToolCallTextParser.parse(text, tools)

        assertEquals(listOf("tap"), calls.map { it.name })
    }

    @Test
    fun `accepts parameters alias`() {
        val call = ToolCallTextParser.parse("""{"name":"tap","parameters":{"x":3}}""", tools).single()

        assertEquals(3, call.arguments.getValue("x").jsonPrimitive.int)
    }

    @Test
    fun `returns empty for prose`() {
        assertTrue(ToolCallTextParser.parse("I will open the settings app now.", tools).isEmpty())
    }

    @Test
    fun `returns empty when name missing or blank`() {
        assertTrue(ToolCallTextParser.parse("""{"arguments":{}}""", tools).isEmpty())
        assertTrue(ToolCallTextParser.parse("""{"name":"  "}""", tools).isEmpty())
    }

    @Test
    fun `stripToolCalls removes tagged calls and keeps prose`() {
        val text = "draft</think>Opening settings.\n" + xmlCall("tap_node", "node_id" to "n1")

        assertEquals("Opening settings.", ToolCallTextParser.stripToolCalls(text))
    }

    @Test
    fun `stripToolCalls removes untagged xml function`() {
        val text = "Tap it\n<function=tap_node>\n<parameter=node_id>\nn1\n</parameter>\n</function>"

        assertEquals("Tap it", ToolCallTextParser.stripToolCalls(text))
    }

    @Test
    fun `stripToolCalls returns null for bare json call`() {
        assertNull(ToolCallTextParser.stripToolCalls("""{"name":"press_back","arguments":{}}"""))
    }

    @Test
    fun `stripToolCalls returns null for fenced json call`() {
        assertNull(ToolCallTextParser.stripToolCalls("```json\n{\"name\":\"press_home\"}\n```"))
    }
}
