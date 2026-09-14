package com.danielealbano.androidremotecontrolmcp.agent.core

import android.util.Log
import app.cash.turbine.test
import com.danielealbano.androidremotecontrolmcp.agent.llm.LlmClient
import com.danielealbano.androidremotecontrolmcp.agent.llm.LlmException
import com.danielealbano.androidremotecontrolmcp.agent.llm.LlmImage
import com.danielealbano.androidremotecontrolmcp.agent.llm.LlmMessage
import com.danielealbano.androidremotecontrolmcp.agent.llm.LlmResponse
import com.danielealbano.androidremotecontrolmcp.agent.llm.LlmToolCall
import com.danielealbano.androidremotecontrolmcp.agent.llm.LlmToolDefinition
import com.danielealbano.androidremotecontrolmcp.agent.tools.AgentToolBridge
import com.danielealbano.androidremotecontrolmcp.agent.tools.AgentToolBridgeException
import com.danielealbano.androidremotecontrolmcp.agent.tools.AgentToolProfile
import com.danielealbano.androidremotecontrolmcp.agent.tools.AgentToolResult
import com.danielealbano.androidremotecontrolmcp.data.model.AgentConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("AgentRunnerImpl")
class AgentRunnerImplTest {
    private val settingsRepository = mockk<SettingsRepository>()
    private val llmClient = mockk<LlmClient>()
    private val toolBridge = mockk<AgentToolBridge>()
    private val runner = AgentRunnerImpl(settingsRepository, llmClient, toolBridge)
    private val capturedMessages = mutableListOf<List<LlmMessage>>()

    private val emptySchema = buildJsonObject { put("type", "object") }
    private val tapDefinition = LlmToolDefinition("tap", "", emptySchema)
    private val idleDefinition = LlmToolDefinition(AgentToolProfile.WAIT_FOR_IDLE, "", emptySchema)
    private val screenDefinition = LlmToolDefinition(AgentToolProfile.GET_SCREEN_STATE, "", emptySchema)
    private val image = LlmImage(base64 = "AAA", mimeType = "image/jpeg")

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        coEvery { settingsRepository.getAgentConfig() } returns AgentConfig(llmBaseUrl = "http://h/v1", maxSteps = 5)
        coEvery { toolBridge.open() } returns Result.success(listOf(screenDefinition, tapDefinition, idleDefinition))
        coEvery { toolBridge.close() } just Runs
        coEvery { toolBridge.call(AgentToolProfile.GET_SCREEN_STATE, any()) } returns screenResult("screen nodes")
        coEvery { toolBridge.call("tap", any()) } returns ok()
        coEvery { toolBridge.call(AgentToolProfile.WAIT_FOR_IDLE, any()) } returns ok()
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun ok(text: String = "done"): AgentToolResult = AgentToolResult(text = text, image = null, isError = false)

    private fun failure(text: String): AgentToolResult = AgentToolResult(text = text, image = null, isError = true)

    private fun screenResult(text: String): AgentToolResult =
        AgentToolResult(
            text = text,
            image = image,
            isError = false,
        )

    private fun callOf(
        name: String,
        arguments: JsonObject = JsonObject(emptyMap()),
    ): Result<LlmResponse> =
        Result.success(
            LlmResponse(text = null, toolCalls = listOf(LlmToolCall("c", name, arguments))),
        )

    private fun finish(summary: String = "Done"): Result<LlmResponse> =
        callOf(AgentPrompts.FINISH_TOOL, buildJsonObject { put("summary", summary) })

    /** Answers LLM requests in order, recording the messages each request carried. */
    private fun llmReplies(replies: List<Result<LlmResponse>>) {
        val queue = ArrayDeque(replies)
        coEvery { llmClient.complete(any(), any(), any()) } coAnswers {
            capturedMessages += secondArg<List<LlmMessage>>()
            queue.removeFirst()
        }
    }

    private fun includeScreenshot(arguments: JsonObject): Boolean? {
        val value = arguments["include_screenshot"] ?: return null
        return value.jsonPrimitive.boolean
    }

    @Test
    fun `fails without opening bridge when endpoint blank`() =
        runTest {
            coEvery { settingsRepository.getAgentConfig() } returns AgentConfig(llmBaseUrl = "")

            runner.run("goal")

            assertTrue(runner.state.value is AgentRunState.Failed)
            coVerify(exactly = 0) { toolBridge.open() }
            coVerify(exactly = 1) { toolBridge.close() }
        }

    @Test
    fun `fails when bridge open fails`() =
        runTest {
            coEvery { toolBridge.open() } returns Result.failure(AgentToolBridgeException("Start the MCP server"))

            runner.run("goal")

            val state = runner.state.value as AgentRunState.Failed
            assertTrue(state.reason.contains("Start the MCP server"))
            coVerify(exactly = 1) { toolBridge.close() }
        }

    @Test
    fun `fails when agent settings cannot be read`() =
        runTest {
            coEvery { settingsRepository.getAgentConfig() } throws IOException("disk error")

            runner.run("goal")

            val state = runner.state.value as AgentRunState.Failed
            assertTrue(state.reason.startsWith("Cannot read agent settings"))
            coVerify(exactly = 1) { toolBridge.close() }
        }

    @Test
    fun `unexpected illegal state publishes failed`() =
        runTest {
            coEvery { llmClient.complete(any(), any(), any()) } throws IllegalStateException("boom")

            runner.run("goal")

            assertEquals("boom", (runner.state.value as AgentRunState.Failed).reason)
            coVerify(exactly = 1) { toolBridge.close() }
        }

    @Test
    fun `finishes on finish tool with summary`() =
        runTest {
            llmReplies(listOf(finish("All set")))

            runner.run("goal")

            assertEquals("All set", (runner.state.value as AgentRunState.Finished).summary)
            coVerify(exactly = 0) { toolBridge.call("tap", any()) }
            coVerify(exactly = 1) { toolBridge.close() }
        }

    @Test
    fun `executes action then waits for idle then finishes`() =
        runTest {
            llmReplies(listOf(callOf("tap"), finish()))

            runner.run("goal")

            coVerifyOrder {
                toolBridge.call("tap", any())
                toolBridge.call(AgentToolProfile.WAIT_FOR_IDLE, match { it["timeout"]?.jsonPrimitive?.int == 3_000 })
            }
            assertEquals(1, (runner.state.value as AgentRunState.Finished).steps.size)
            coVerify(exactly = 1) { toolBridge.close() }
        }

    @Test
    fun `pauses instead of wait_for_idle when tool not in session`() =
        runTest {
            coEvery { toolBridge.open() } returns Result.success(listOf(screenDefinition, tapDefinition))
            llmReplies(listOf(callOf("tap"), finish()))

            runner.run("goal")

            coVerify(exactly = 0) { toolBridge.call(AgentToolProfile.WAIT_FOR_IDLE, any()) }
            assertTrue(currentTime >= 1_500)
            assertTrue(runner.state.value is AgentRunState.Finished)
        }

    @Test
    fun `does not wait for idle after failed action`() =
        runTest {
            coEvery { toolBridge.call("tap", any()) } returns failure("gesture rejected")
            llmReplies(listOf(callOf("tap"), finish()))

            runner.run("goal")

            coVerify(exactly = 0) { toolBridge.call(AgentToolProfile.WAIT_FOR_IDLE, any()) }
            assertEquals(0L, currentTime)
        }

    @Test
    fun `does not wait for idle after get_screen_state action`() =
        runTest {
            llmReplies(listOf(callOf(AgentToolProfile.GET_SCREEN_STATE), finish()))

            runner.run("goal")

            coVerify(exactly = 0) { toolBridge.call(AgentToolProfile.WAIT_FOR_IDLE, any()) }
        }

    @Test
    fun `requests screenshot according to config`() =
        runTest {
            llmReplies(listOf(finish(), finish()))

            runner.run("with screenshot")
            coEvery { settingsRepository.getAgentConfig() } returns
                AgentConfig(llmBaseUrl = "http://h/v1", sendScreenshot = false)
            runner.run("without screenshot")

            coVerifyOrder {
                toolBridge.call(AgentToolProfile.GET_SCREEN_STATE, match { includeScreenshot(it) == true })
                toolBridge.call(AgentToolProfile.GET_SCREEN_STATE, match { includeScreenshot(it) == false })
            }
        }

    @Test
    fun `retries observation without screenshot when capture fails`() =
        runTest {
            coEvery {
                toolBridge.call(AgentToolProfile.GET_SCREEN_STATE, match { includeScreenshot(it) == true })
            } returns failure("capture unavailable")
            coEvery {
                toolBridge.call(AgentToolProfile.GET_SCREEN_STATE, match { includeScreenshot(it) == false })
            } returns ok("node list only")
            llmReplies(listOf(finish()))

            runner.run("goal")

            val observation =
                capturedMessages
                    .single()
                    .filterIsInstance<LlmMessage.User>()
                    .last()
                    .text
            assertTrue(observation.contains(AgentPrompts.SCREENSHOT_UNAVAILABLE))
            assertTrue(observation.contains("node list only"))
            assertTrue(runner.state.value is AgentRunState.Finished)
        }

    @Test
    fun `does not retry when screenshots disabled`() =
        runTest {
            coEvery { settingsRepository.getAgentConfig() } returns
                AgentConfig(llmBaseUrl = "http://h/v1", sendScreenshot = false)
            coEvery { toolBridge.call(AgentToolProfile.GET_SCREEN_STATE, any()) } returns failure("no screen")

            runner.run("goal")

            assertTrue(runner.state.value is AgentRunState.Failed)
            coVerify(exactly = 1) { toolBridge.call(AgentToolProfile.GET_SCREEN_STATE, any()) }
            coVerify(exactly = 1) { toolBridge.close() }
        }

    @Test
    fun `fails when screen cannot be read even without screenshot`() =
        runTest {
            coEvery { toolBridge.call(AgentToolProfile.GET_SCREEN_STATE, any()) } returns failure("no screen")

            runner.run("goal")

            assertTrue((runner.state.value as AgentRunState.Failed).reason.startsWith("Cannot read the screen"))
            coVerify(exactly = 2) { toolBridge.call(AgentToolProfile.GET_SCREEN_STATE, any()) }
            coVerify(exactly = 1) { toolBridge.close() }
        }

    @Test
    fun `uses model requested page as next observation`() =
        runTest {
            val cursor = buildJsonObject { put("cursor", "s.2") }
            coEvery {
                toolBridge.call(AgentToolProfile.GET_SCREEN_STATE, match { "cursor" in it })
            } returns ok("page two nodes")
            llmReplies(listOf(callOf(AgentToolProfile.GET_SCREEN_STATE, cursor), finish()))

            runner.run("goal")

            coVerify(exactly = 1) { toolBridge.call(AgentToolProfile.GET_SCREEN_STATE, match { "cursor" !in it }) }
            val secondObservation = capturedMessages[1].filterIsInstance<LlmMessage.User>().last().text
            assertTrue(secondObservation.contains("page two nodes"))
        }

    @Test
    fun `fails when llm request fails`() =
        runTest {
            llmReplies(listOf(Result.failure(LlmException("LLM endpoint returned HTTP 503: busy"))))

            runner.run("goal")

            assertTrue((runner.state.value as AgentRunState.Failed).reason.contains("HTTP 503"))
            coVerify(exactly = 1) { toolBridge.close() }
        }

    @Test
    fun `fails after three replies without tool calls`() =
        runTest {
            val noCall = Result.success(LlmResponse(text = "thinking", toolCalls = emptyList()))
            llmReplies(listOf(noCall, noCall, noCall))

            runner.run("goal")

            assertTrue(runner.state.value is AgentRunState.Failed)
            coVerify(exactly = 3) { llmClient.complete(any(), any(), any()) }
            coVerify(exactly = 1) { toolBridge.close() }
        }

    @Test
    fun `fails when step limit reached`() =
        runTest {
            coEvery { settingsRepository.getAgentConfig() } returns
                AgentConfig(llmBaseUrl = "http://h/v1", maxSteps = 2)
            llmReplies(
                listOf(
                    callOf("tap", buildJsonObject { put("x", 1) }),
                    callOf("tap", buildJsonObject { put("x", 2) }),
                ),
            )

            runner.run("goal")

            assertTrue((runner.state.value as AgentRunState.Failed).reason.contains("2"))
            coVerify(exactly = 1) { toolBridge.close() }
        }

    @Test
    fun `publishes running state with steps`() =
        runTest {
            val replies = ArrayDeque(listOf(callOf("tap"), finish()))
            coEvery { llmClient.complete(any(), any(), any()) } coAnswers {
                delay(10)
                replies.removeFirst()
            }

            runner.state.test {
                assertEquals(AgentRunState.Idle, awaitItem())
                val job = launch { runner.run("goal") }
                val states = mutableListOf<AgentRunState>()
                do {
                    val state = awaitItem()
                    states += state
                } while (state is AgentRunState.Running)
                job.join()

                assertTrue(states.any { it is AgentRunState.Running && it.steps.size == 1 })
                assertTrue(states.last() is AgentRunState.Finished)
            }
        }

    @Test
    fun `sends only latest screenshot to llm`() =
        runTest {
            llmReplies(listOf(callOf("tap"), finish()))

            runner.run("goal")

            val secondRequest = capturedMessages[1].filterIsInstance<LlmMessage.User>()
            assertEquals(1, secondRequest.count { it.image != null })
        }

    @Test
    fun `cancellation during llm call publishes cancelled`() =
        runTest {
            coEvery { llmClient.complete(any(), any(), any()) } coAnswers { awaitCancellation() }

            val job = launch { runner.run("goal") }
            advanceUntilIdle()
            job.cancelAndJoin()

            assertTrue(runner.state.value is AgentRunState.Cancelled)
            coVerify(exactly = 1) { toolBridge.close() }
        }

    @Test
    fun `cancellation during tool call publishes cancelled`() =
        runTest {
            llmReplies(listOf(callOf("tap")))
            coEvery { toolBridge.call("tap", any()) } coAnswers { awaitCancellation() }

            val job = launch { runner.run("goal") }
            advanceUntilIdle()
            job.cancelAndJoin()

            assertTrue(runner.state.value is AgentRunState.Cancelled)
            coVerify(exactly = 1) { toolBridge.close() }
        }

    @Test
    fun `concurrent run is ignored`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            coEvery { llmClient.complete(any(), any(), any()) } coAnswers {
                gate.await()
                finish("first")
            }

            val first = launch { runner.run("first goal") }
            advanceUntilIdle()
            runner.run("second goal")
            gate.complete(Unit)
            first.join()

            assertEquals("first goal", (runner.state.value as AgentRunState.Finished).goal)
            coVerify(exactly = 1) { llmClient.complete(any(), any(), any()) }
            coVerify(exactly = 1) { toolBridge.close() }
        }
}
