package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.agent.tools.AgentToolProfile
import com.danielealbano.androidremotecontrolmcp.agent.tools.AgentToolResult
import com.danielealbano.androidremotecontrolmcp.agent.tools.LoopbackMcpToolBridge
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.mcp.installMcpBasePlugins
import com.danielealbano.androidremotecontrolmcp.mcp.installMcpStatelessTransport
import com.danielealbano.androidremotecontrolmcp.services.mcp.McpServerStatusProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

@DisplayName("LoopbackMcpToolBridge integration")
class LoopbackMcpToolBridgeIntegrationTest {
    private val settingsRepository = mockk<SettingsRepository>()
    private val statusProvider = mockk<McpServerStatusProvider>()
    private val createdClients = CopyOnWriteArrayList<HttpClient>()
    private val authorizationHeaders = CopyOnWriteArrayList<String>()
    private val requestCount = AtomicInteger(0)
    private val tapArguments =
        buildJsonObject {
            put("x", 10)
            put("y", 20)
        }

    private class Scenario(
        val deps: MockDependencies,
        val bridge: LoopbackMcpToolBridge,
    )

    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
        every { statusProvider.status } returns
            MutableStateFlow<ServerStatus>(ServerStatus.Running(port = 8080, bindingAddress = "127.0.0.1"))
        coEvery { settingsRepository.getServerConfig() } returns
            ServerConfig(bearerToken = McpIntegrationTestHelper.TEST_BEARER_TOKEN)
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    private fun runScenario(
        perms: ToolPermissionsConfig = ToolPermissionsConfig(),
        openServer: Boolean = false,
        block: suspend ApplicationTestBuilder.(Scenario) -> Unit,
    ) = testApplication {
        val deps = McpIntegrationTestHelper.createMockDependencies()
        val sdkServer = McpIntegrationTestHelper.createSdkServer(deps, perms = perms)
        application {
            installMcpBasePlugins {
                if (openServer) {
                    bearerTokenEnabled = false
                    oauthEnabled = false
                    expectedToken = ""
                } else {
                    expectedToken = McpIntegrationTestHelper.TEST_BEARER_TOKEN
                }
            }
            installMcpStatelessTransport { sdkServer }
        }
        val bridge =
            LoopbackMcpToolBridge(settingsRepository, statusProvider).apply {
                httpClientProvider = {
                    createClient { install(SSE) }.also { client ->
                        client.plugin(HttpSend).intercept { request ->
                            requestCount.incrementAndGet()
                            request.headers[HttpHeaders.Authorization]?.let { authorizationHeaders += it }
                            execute(request)
                        }
                        createdClients += client
                    }
                }
                endpointUrl = { "/mcp" }
            }
        block(Scenario(deps, bridge))
    }

    private suspend fun assertReleased(client: HttpClient) {
        withTimeout(RELEASE_TIMEOUT_MS) { client.coroutineContext[Job]?.join() }
    }

    @Test
    fun `open returns exactly the profile tools un-prefixed`() =
        runScenario { scenario ->
            val tools = scenario.bridge.open().getOrThrow()

            assertEquals(AgentToolProfile.TOOL_NAMES, tools.map { it.name }.toSet())
            scenario.bridge.close()
        }

    @Test
    fun `open fails with wrong bearer token`() =
        runScenario { scenario ->
            coEvery { settingsRepository.getServerConfig() } returns ServerConfig(bearerToken = "wrong-token")

            val result = scenario.bridge.open()

            assertTrue(result.isFailure)
            assertReleased(createdClients.single())
        }

    @Test
    fun `open without token when both auth methods disabled`() =
        runScenario(openServer = true) { scenario ->
            coEvery { settingsRepository.getServerConfig() } returns
                ServerConfig(
                    bearerToken = McpIntegrationTestHelper.TEST_BEARER_TOKEN,
                    bearerTokenEnabled = false,
                    oauthEnabled = false,
                )

            val result = scenario.bridge.open()

            assertTrue(result.isSuccess)
            assertTrue(requestCount.get() > 0)
            assertTrue(authorizationHeaders.isEmpty())
            scenario.bridge.close()
        }

    @Test
    fun `open fails when get_screen_state disabled`() =
        runScenario(perms = SCREEN_STATE_DISABLED) { scenario ->
            val result = scenario.bridge.open()

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("get_screen_state"))
        }

    @Test
    fun `open omits disabled profile tools`() =
        runScenario(perms = WAIT_FOR_IDLE_DISABLED) { scenario ->
            val names = scenario.bridge.open().getOrThrow().map { it.name }

            assertFalse(AgentToolProfile.WAIT_FOR_IDLE in names)
            assertTrue(AgentToolProfile.GET_SCREEN_STATE in names)
            scenario.bridge.close()
        }

    @Test
    fun `call forwards arguments to prefixed tool`() =
        runScenario { scenario ->
            coEvery { scenario.deps.actionExecutor.tap(10f, 20f) } returns Result.success(Unit)
            scenario.bridge.open().getOrThrow()

            val result = scenario.bridge.call("tap", tapArguments)

            assertFalse(result.isError, result.text)
            coVerify(exactly = 1) { scenario.deps.actionExecutor.tap(10f, 20f) }
            scenario.bridge.close()
        }

    @Test
    fun `call returns error for tool outside profile`() =
        runScenario { scenario ->
            scenario.bridge.open().getOrThrow()
            val requestsBefore = requestCount.get()

            val result = scenario.bridge.call("delete_file", JsonObject(emptyMap()))

            assertTrue(result.isError)
            assertTrue(result.text.contains("not available"))
            assertEquals(requestsBefore, requestCount.get())
            scenario.bridge.close()
        }

    @Test
    fun `call returns error for profile tool not enabled in session`() =
        runScenario(perms = WAIT_FOR_IDLE_DISABLED) { scenario ->
            scenario.bridge.open().getOrThrow()
            val requestsBefore = requestCount.get()

            val result = scenario.bridge.call(AgentToolProfile.WAIT_FOR_IDLE, buildJsonObject { put("timeout", 100) })

            assertTrue(result.isError)
            assertEquals(requestsBefore, requestCount.get())
            scenario.bridge.close()
        }

    @Test
    fun `call returns error before open`() =
        runScenario { scenario ->
            val result = scenario.bridge.call("tap", tapArguments)

            assertTrue(result.isError)
        }

    @Test
    fun `call maps tool error result`() =
        runScenario { scenario ->
            coEvery { scenario.deps.actionExecutor.tap(any(), any()) } returns
                Result.failure(IllegalStateException("gesture rejected"))
            scenario.bridge.open().getOrThrow()

            val result = scenario.bridge.call("tap", tapArguments)

            assertTrue(result.isError)
            scenario.bridge.close()
        }

    @Test
    fun `call returns error after tool request timeout`() =
        runScenario { scenario ->
            coEvery { scenario.deps.actionExecutor.tap(any(), any()) } coAnswers { awaitCancellation() }
            scenario.bridge.toolRequestTimeout = SHORT_TIMEOUT_MS.milliseconds
            scenario.bridge.open().getOrThrow()
            val startedAt = System.nanoTime()

            val result = scenario.bridge.call("tap", tapArguments)

            assertTrue(result.isError)
            assertTrue(System.nanoTime() - startedAt < PROMPT_NANOS)
            scenario.bridge.close()
        }

    @Test
    fun `close during in flight call aborts it promptly`() =
        runScenario { scenario ->
            val tapStarted = CompletableDeferred<Unit>()
            coEvery { scenario.deps.actionExecutor.tap(any(), any()) } coAnswers {
                tapStarted.complete(Unit)
                awaitCancellation()
            }
            scenario.bridge.open().getOrThrow()
            var result: AgentToolResult? = null
            val call = launch { result = scenario.bridge.call("tap", tapArguments) }
            withTimeout(RELEASE_TIMEOUT_MS) { tapStarted.await() }
            val startedAt = System.nanoTime()

            scenario.bridge.close()
            withTimeout(RELEASE_TIMEOUT_MS) { call.join() }

            assertTrue(System.nanoTime() - startedAt < PROMPT_NANOS)
            assertNotNull(result)
            assertTrue(result?.isError == true)
            assertFalse(call.isCancelled)
        }

    @Test
    fun `call propagates caller cancellation`() =
        runScenario { scenario ->
            val firstTap = AtomicBoolean(true)
            val tapStarted = CompletableDeferred<Unit>()
            coEvery { scenario.deps.actionExecutor.tap(any(), any()) } coAnswers {
                if (firstTap.getAndSet(false)) {
                    tapStarted.complete(Unit)
                    awaitCancellation()
                }
                Result.success(Unit)
            }
            scenario.bridge.open().getOrThrow()
            val call = launch { scenario.bridge.call("tap", tapArguments) }
            withTimeout(RELEASE_TIMEOUT_MS) { tapStarted.await() }

            call.cancelAndJoin()
            val next = scenario.bridge.call("tap", tapArguments)

            assertTrue(call.isCancelled)
            assertFalse(next.isError, next.text)
            scenario.bridge.close()
        }

    @Test
    fun `close is idempotent and releases http client`() =
        runScenario { scenario ->
            scenario.bridge.open().getOrThrow()

            scenario.bridge.close()
            scenario.bridge.close()

            assertEquals(1, createdClients.size)
            assertReleased(createdClients.single())
            assertTrue(scenario.bridge.call("tap", tapArguments).isError)
        }

    private companion object {
        val SCREEN_STATE_DISABLED = ToolPermissionsConfig(disabledTools = setOf(AgentToolProfile.GET_SCREEN_STATE))
        val WAIT_FOR_IDLE_DISABLED = ToolPermissionsConfig(disabledTools = setOf(AgentToolProfile.WAIT_FOR_IDLE))
        const val SHORT_TIMEOUT_MS = 300L
        const val RELEASE_TIMEOUT_MS = 5_000L
        const val PROMPT_NANOS = 5_000_000_000L
    }
}
