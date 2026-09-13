package com.danielealbano.androidremotecontrolmcp.agent.tools

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.mcp.McpServerStatusProvider
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

@DisplayName("LoopbackMcpToolBridge")
class LoopbackMcpToolBridgeTest {
    private val settingsRepository = mockk<SettingsRepository>()
    private val statusFlow =
        MutableStateFlow<ServerStatus>(ServerStatus.Running(port = 8080, bindingAddress = "127.0.0.1"))
    private val statusProvider = mockk<McpServerStatusProvider>()
    private val createdClients = CopyOnWriteArrayList<HttpClient>()

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { statusProvider.status } returns statusFlow
        coEvery { settingsRepository.getServerConfig() } returns ServerConfig(bearerToken = "token")
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
        createdClients.forEach { it.close() }
    }

    private fun bridgeWith(
        handler: MockRequestHandler,
        configure: HttpClientConfig<MockEngineConfig>.() -> Unit = {},
    ): LoopbackMcpToolBridge =
        LoopbackMcpToolBridge(settingsRepository, statusProvider).apply {
            httpClientProvider = {
                HttpClient(MockEngine) {
                    engine { addHandler(handler) }
                    install(SSE)
                    configure()
                }.also { createdClients += it }
            }
        }

    /** A released client has had close() called, so its job completes once in-flight work is gone. */
    private fun assertReleased(client: HttpClient) =
        runBlocking {
            withTimeout(RELEASE_TIMEOUT_MS) { client.coroutineContext[Job]?.join() }
        }

    @Test
    fun `default endpoint url targets loopback port`() {
        val bridge = LoopbackMcpToolBridge(settingsRepository, statusProvider)

        assertEquals("http://127.0.0.1:8123/mcp", bridge.endpointUrl(8123))
    }

    @Test
    fun `open uses running status port not config port`() =
        runBlocking {
            statusFlow.value = ServerStatus.Running(port = 9123, bindingAddress = "127.0.0.1")
            coEvery { settingsRepository.getServerConfig() } returns ServerConfig(port = 8080, bearerToken = "token")
            val ports = CopyOnWriteArrayList<Int>()
            val bridge =
                bridgeWith({ respond("fail", HttpStatusCode.InternalServerError) }).apply {
                    endpointUrl = { port ->
                        ports += port
                        "http://127.0.0.1:$port/mcp"
                    }
                }

            val result = bridge.open()

            assertTrue(result.isFailure)
            assertEquals(listOf(9123), ports.toList())
        }

    @Test
    fun `open fails without creating http client when preconditions fail`() =
        runBlocking {
            statusFlow.value = ServerStatus.Stopped
            val bridge = bridgeWith({ respond("unused", HttpStatusCode.OK) })

            val result = bridge.open()

            assertTrue(result.isFailure)
            assertTrue(createdClients.isEmpty())
        }

    @Test
    fun `open returns failure instead of throwing on request timeout`() =
        runBlocking {
            val bridge =
                bridgeWith({ awaitCancellation() }) {
                    install(HttpTimeout) { requestTimeoutMillis = SHORT_TIMEOUT_MS }
                }

            val result = bridge.open()

            assertTrue(result.isFailure)
            assertEquals(1, createdClients.size)
            assertReleased(createdClients.single())
        }

    @Test
    fun `cancelled open releases http client`() =
        runBlocking {
            val bridge = bridgeWith({ awaitCancellation() })

            val job = launch(Dispatchers.Default) { bridge.open() }
            withTimeout(RELEASE_TIMEOUT_MS) { while (createdClients.isEmpty()) delay(POLL_MS) }
            delay(SETTLE_MS)
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertEquals(1, createdClients.size)
            assertReleased(createdClients.single())
            bridge.close()
        }

    private companion object {
        const val SHORT_TIMEOUT_MS = 200L
        const val RELEASE_TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
        const val SETTLE_MS = 100L
    }
}
