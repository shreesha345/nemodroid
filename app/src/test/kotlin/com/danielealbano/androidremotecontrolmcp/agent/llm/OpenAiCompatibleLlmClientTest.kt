package com.danielealbano.androidremotecontrolmcp.agent.llm

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("OpenAiCompatibleLlmClient")
class OpenAiCompatibleLlmClientTest {
    private val endpoint = LlmEndpoint(baseUrl = "https://h/v1/", apiKey = "", model = "m")
    private val messages = listOf(LlmMessage.User("hi"))
    private val tapNodeTool =
        LlmToolDefinition(
            name = "tap_node",
            description = "",
            parameters =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("node_id") { put("type", "string") } }
                },
        )

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun clientWith(handler: MockRequestHandler): OpenAiCompatibleLlmClient =
        OpenAiCompatibleLlmClient(UnconfinedTestDispatcher()).apply {
            clientProvider = { HttpClient(MockEngine) { engine { addHandler(handler) } } }
        }

    private fun chatBody(content: String?): String =
        buildJsonObject {
            putJsonArray("choices") {
                addJsonObject {
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", content)
                    }
                }
            }
        }.toString()

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `posts to chat completions under base url`() =
        runTest {
            var url: String? = null
            var method: HttpMethod? = null
            val client =
                clientWith { request ->
                    url = request.url.toString()
                    method = request.method
                    respond(chatBody("ok"), HttpStatusCode.OK, jsonHeaders)
                }

            client.complete(endpoint, messages, emptyList())

            assertEquals("https://h/v1/chat/completions", url)
            assertEquals(HttpMethod.Post, method)
        }

    @Test
    fun `sends bearer header only when api key set`() =
        runTest {
            val headers = mutableListOf<String?>()
            val client =
                clientWith { request ->
                    headers += request.headers[HttpHeaders.Authorization]
                    respond(chatBody("ok"), HttpStatusCode.OK, jsonHeaders)
                }

            client.complete(endpoint.copy(apiKey = "k-1"), messages, emptyList())
            client.complete(endpoint, messages, emptyList())

            assertEquals(listOf("Bearer k-1", null), headers)
        }

    @Test
    fun `returns parsed response on success`() =
        runTest {
            val body =
                """{"choices":[{"message":{"content":null,"tool_calls":[{"id":"c1","type":"function",""" +
                    """"function":{"name":"tap_node","arguments":"{\"node_id\":\"n1\"}"}}]}}]}"""
            val client = clientWith { respond(body, HttpStatusCode.OK, jsonHeaders) }

            val response = client.complete(endpoint, messages, emptyList()).getOrThrow()

            assertEquals("tap_node", response.toolCalls.single().name)
            assertEquals("n1", response.toolCalls.single().arguments.getValue("node_id").jsonPrimitive.content)
        }

    @Test
    fun `falls back to schema aware text tool calls when structured calls absent`() =
        runTest {
            val content = "Tapping.\n<tool_call>\n<function=tap_node>\n<parameter=node_id>\n123\n</parameter>\n" +
                "</function>\n</tool_call>"
            val client = clientWith { respond(chatBody(content), HttpStatusCode.OK, jsonHeaders) }

            val response = client.complete(endpoint, messages, listOf(tapNodeTool)).getOrThrow()

            val nodeId = response.toolCalls.single().arguments.getValue("node_id").jsonPrimitive
            assertTrue(nodeId.isString)
            assertEquals("123", nodeId.content)
            assertEquals("Tapping.", response.text)
        }

    @Test
    fun `keeps text response when no text calls found`() =
        runTest {
            val client = clientWith { respond(chatBody("All done."), HttpStatusCode.OK, jsonHeaders) }

            val response = client.complete(endpoint, messages, listOf(tapNodeTool)).getOrThrow()

            assertEquals("All done.", response.text)
            assertTrue(response.toolCalls.isEmpty())
        }

    @Test
    fun `returns failure with status on non success`() =
        runTest {
            val client = clientWith { respond("x".repeat(1_000), HttpStatusCode.ServiceUnavailable) }

            val message = client.complete(endpoint, messages, emptyList()).exceptionOrNull()?.message.orEmpty()

            assertTrue(message.contains("503"))
            assertTrue(message.contains("x".repeat(300)))
            assertFalse(message.contains("x".repeat(301)))
        }

    @Test
    fun `returns failure on network error`() =
        runTest {
            val client = clientWith { throw IOException("down") }

            val error = client.complete(endpoint, messages, emptyList()).exceptionOrNull()

            assertTrue(error is LlmException)
            assertTrue(error?.message.orEmpty().startsWith("Cannot reach the LLM endpoint"))
        }

    @Test
    fun `returns failure on malformed body`() =
        runTest {
            val client = clientWith { respond("not json", HttpStatusCode.OK) }

            assertTrue(client.complete(endpoint, messages, emptyList()).exceptionOrNull() is LlmException)
        }

    @Test
    fun `returns invalid url failure only for unparsable base url`() =
        runTest {
            var requests = 0
            val client =
                clientWith {
                    requests++
                    respond(chatBody("ok"), HttpStatusCode.OK, jsonHeaders)
                }

            val error = client.complete(endpoint.copy(baseUrl = "http://[bad"), messages, emptyList()).exceptionOrNull()

            assertTrue(error is LlmException)
            assertTrue(error?.message.orEmpty().startsWith("Invalid LLM endpoint URL"))
            assertEquals(0, requests)
        }

    @Test
    fun `maps unexpected exception to generic failure`() =
        runTest {
            val client = clientWith { throw IllegalStateException("engine closed") }

            val message = client.complete(endpoint, messages, emptyList()).exceptionOrNull()?.message.orEmpty()

            assertTrue(message.startsWith("LLM request failed"))
            assertFalse(message.contains("Invalid LLM endpoint URL"))
        }

    @Test
    fun `propagates Error`() {
        val client = clientWith { throw AssertionError("fatal") }

        assertThrows(AssertionError::class.java) {
            runTest { client.complete(endpoint, messages, emptyList()) }
        }
    }

    @Test
    fun `propagates cancellation`() =
        runTest {
            val client = clientWith { awaitCancellation() }
            var result: Result<LlmResponse>? = null

            val job = launch { result = client.complete(endpoint, messages, emptyList()) }
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertNull(result)
        }
}
