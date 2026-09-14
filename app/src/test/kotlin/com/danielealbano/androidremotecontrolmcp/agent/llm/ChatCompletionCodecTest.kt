package com.danielealbano.androidremotecontrolmcp.agent.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ChatCompletionCodec")
class ChatCompletionCodecTest {
    private val tool =
        LlmToolDefinition(
            name = "tap",
            description = "Tap the screen",
            parameters = buildJsonObject { put("type", "object") },
        )

    private fun response(message: String): String = """{"choices":[{"message":$message}]}"""

    private fun messagesOf(request: JsonObject): JsonArray = request.getValue("messages").jsonArray

    @Test
    fun `encodeRequest includes model temperature and messages`() {
        val request = ChatCompletionCodec.encodeRequest("m", listOf(LlmMessage.System("s")), listOf(tool))

        assertEquals("m", request.getValue("model").jsonPrimitive.content)
        assertEquals(0.2, request.getValue("temperature").jsonPrimitive.double)
        assertEquals(1, messagesOf(request).size)
        assertEquals(
            "system",
            messagesOf(request)[0]
                .jsonObject
                .getValue("role")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `encodeRequest omits tools when empty`() {
        val request = ChatCompletionCodec.encodeRequest("m", listOf(LlmMessage.User("hi")), emptyList())

        assertFalse(request.containsKey("tools"))
    }

    @Test
    fun `encodeRequest encodes tools as functions`() {
        val request = ChatCompletionCodec.encodeRequest("m", emptyList(), listOf(tool))

        val encoded = request.getValue("tools").jsonArray[0].jsonObject
        assertEquals("function", encoded.getValue("type").jsonPrimitive.content)
        val function = encoded.getValue("function").jsonObject
        assertEquals("tap", function.getValue("name").jsonPrimitive.content)
        assertEquals("Tap the screen", function.getValue("description").jsonPrimitive.content)
        assertEquals(tool.parameters, function.getValue("parameters"))
    }

    @Test
    fun `encodeRequest encodes user image as text and image_url parts`() {
        val message = LlmMessage.User("screen", LlmImage(base64 = "AAA", mimeType = "image/jpeg"))

        val content =
            messagesOf(ChatCompletionCodec.encodeRequest("m", listOf(message), emptyList()))[0]
                .jsonObject
                .getValue("content")
                .jsonArray

        assertEquals(
            "text",
            content[0]
                .jsonObject
                .getValue("type")
                .jsonPrimitive.content,
        )
        assertEquals(
            "screen",
            content[0]
                .jsonObject
                .getValue("text")
                .jsonPrimitive.content,
        )
        assertEquals(
            "image_url",
            content[1]
                .jsonObject
                .getValue("type")
                .jsonPrimitive.content,
        )
        val url =
            content[1]
                .jsonObject
                .getValue("image_url")
                .jsonObject
                .getValue("url")
                .jsonPrimitive.content
        assertEquals("data:image/jpeg;base64,AAA", url)
    }

    @Test
    fun `encodeRequest encodes user without image as string content`() {
        val encoded = messagesOf(ChatCompletionCodec.encodeRequest("m", listOf(LlmMessage.User("hi")), emptyList()))[0]

        val content = encoded.jsonObject.getValue("content")
        assertTrue(content is JsonPrimitive && content.isString)
        assertEquals("hi", content.jsonPrimitive.content)
    }

    @Test
    fun `encodeRequest encodes assistant tool calls with stringified arguments`() {
        val arguments = buildJsonObject { put("x", 1) }
        val message = LlmMessage.Assistant("thinking", listOf(LlmToolCall("c1", "tap", arguments)))

        val encoded = messagesOf(ChatCompletionCodec.encodeRequest("m", listOf(message), emptyList()))[0].jsonObject

        val call = encoded.getValue("tool_calls").jsonArray[0].jsonObject
        assertEquals("c1", call.getValue("id").jsonPrimitive.content)
        val encodedArguments =
            call
                .getValue("function")
                .jsonObject
                .getValue("arguments")
                .jsonPrimitive
        assertTrue(encodedArguments.isString)
        assertEquals(arguments, Json.parseToJsonElement(encodedArguments.content))
    }

    @Test
    fun `encodeRequest encodes assistant without text as empty string content`() {
        val withCalls = LlmMessage.Assistant(null, listOf(LlmToolCall("c1", "tap", JsonObject(emptyMap()))))
        val withoutCalls = LlmMessage.Assistant(null, emptyList())

        val encoded = messagesOf(ChatCompletionCodec.encodeRequest("m", listOf(withCalls, withoutCalls), emptyList()))

        encoded.forEach {
            assertEquals(
                "",
                it.jsonObject
                    .getValue("content")
                    .jsonPrimitive.content,
            )
        }
        assertFalse(encoded[1].jsonObject.containsKey("tool_calls"))
    }

    @Test
    fun `encodeRequest encodes tool result with tool_call_id`() {
        val message = LlmMessage.ToolResult("c1", "done")

        val encoded = messagesOf(ChatCompletionCodec.encodeRequest("m", listOf(message), emptyList()))[0].jsonObject

        assertEquals("tool", encoded.getValue("role").jsonPrimitive.content)
        assertEquals("c1", encoded.getValue("tool_call_id").jsonPrimitive.content)
        assertEquals("done", encoded.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `decodeResponse parses text only reply`() {
        val decoded = ChatCompletionCodec.decodeResponse(response("""{"content":"hello"}"""))

        assertEquals("hello", decoded.text)
        assertTrue(decoded.toolCalls.isEmpty())
    }

    @Test
    fun `decodeResponse maps empty string content to null`() {
        assertNull(ChatCompletionCodec.decodeResponse(response("""{"content":""}""")).text)
    }

    @Test
    fun `decodeResponse treats null tool_calls as none`() {
        val decoded = ChatCompletionCodec.decodeResponse(response("""{"content":"hi","tool_calls":null}"""))

        assertTrue(decoded.toolCalls.isEmpty())
    }

    @Test
    fun `decodeResponse joins array content text parts`() {
        val body =
            response(
                """{"content":[{"type":"text","text":"a"},{"type":"image_url"},{"type":"text","text":"b"}]}""",
            )

        assertEquals("a\nb", ChatCompletionCodec.decodeResponse(body).text)
    }

    @Test
    fun `decodeResponse parses tool calls with string arguments`() {
        val body =
            response(
                """{"tool_calls":[{"id":"c1","function":{"name":"tap","arguments":"{\"x\":10}"}}]}""",
            )

        val call = ChatCompletionCodec.decodeResponse(body).toolCalls.single()
        assertEquals("c1", call.id)
        assertEquals("tap", call.name)
        assertEquals(
            10,
            call.arguments
                .getValue("x")
                .jsonPrimitive.content
                .toInt(),
        )
    }

    @Test
    fun `decodeResponse parses tool calls with object arguments`() {
        val body = response("""{"tool_calls":[{"id":"c1","function":{"name":"tap","arguments":{"x":10}}}]}""")

        assertEquals(buildJsonObject { put("x", 10) }, ChatCompletionCodec.decodeResponse(body).toolCalls[0].arguments)
    }

    @Test
    fun `decodeResponse assigns index id when id missing`() {
        val body = response("""{"tool_calls":[{"function":{"name":"press_back","arguments":"{}"}}]}""")

        assertEquals("call_0", ChatCompletionCodec.decodeResponse(body).toolCalls[0].id)
    }

    @Test
    fun `decodeResponse treats blank or null arguments as empty object`() {
        val body =
            response(
                """{"tool_calls":[{"function":{"name":"a","arguments":""}},""" +
                    """{"function":{"name":"b","arguments":null}}]}""",
            )

        ChatCompletionCodec.decodeResponse(body).toolCalls.forEach { assertTrue(it.arguments.isEmpty()) }
    }

    @Test
    fun `decodeResponse throws on invalid json`() {
        assertThrows(LlmException::class.java) { ChatCompletionCodec.decodeResponse("not json") }
    }

    @Test
    fun `decodeResponse throws when choices missing`() {
        assertThrows(LlmException::class.java) { ChatCompletionCodec.decodeResponse("""{"id":"x"}""") }
    }

    @Test
    fun `decodeResponse throws on malformed tool call`() {
        listOf(
            """{"tool_calls":[{"id":"c1"}]}""",
            """{"tool_calls":[{"function":{"arguments":"{}"}}]}""",
            """{"tool_calls":[{"function":{"name":"tap","arguments":[1]}}]}""",
            """{"tool_calls":[{"function":{"name":"tap","arguments":"{bad"}}]}""",
            """{"tool_calls":[{"function":{"name":"tap","arguments":"[1]"}}]}""",
        ).forEach { message ->
            assertThrows(LlmException::class.java, { ChatCompletionCodec.decodeResponse(response(message)) }, message)
        }
    }
}
