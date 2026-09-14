package com.danielealbano.androidremotecontrolmcp.agent.tools

import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.agent.llm.LlmToolDefinition
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
import com.danielealbano.androidremotecontrolmcp.services.mcp.McpServerStatusProvider
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Singleton
class LoopbackMcpToolBridge
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val serverStatusProvider: McpServerStatusProvider,
    ) : AgentToolBridge {
        private class Session(
            val httpClient: HttpClient,
            val client: Client,
            val toolNamePrefix: String,
        ) {
            /** Cancelled by close() to abort in-flight calls. */
            val scope = CoroutineScope(SupervisorJob())

            // Written while holding the bridge mutex before open() returns.
            var toolNames: Set<String> = emptySet()
        }

        private val mutex = Mutex()

        // Guarded by mutex.
        private var session: Session? = null

        // Test seams, replaced before open().
        internal var httpClientProvider: () -> HttpClient = { buildClient() }
        internal var endpointUrl: (port: Int) -> String = { "http://$LOOPBACK_HOST:$it/mcp" }
        internal var toolRequestTimeout: Duration = DEFAULT_TOOL_REQUEST_TIMEOUT

        override suspend fun open(): Result<List<LlmToolDefinition>> =
            mutex.withLock {
                closeSessionLocked()
                runCatching { openSessionLocked() }.onFailure { error ->
                    closeSessionLocked()
                    if (error is Error || !currentCoroutineContext().isActive) throw error
                    Logger.w(TAG, "Agent tool session failed to open: ${error.message}")
                }
            }

        override suspend fun call(
            toolName: String,
            arguments: JsonObject,
        ): AgentToolResult {
            val current = mutex.withLock { session }
            return when {
                current == null -> agentToolError("Agent tool session is not open")
                toolName !in current.toolNames -> agentToolError("Tool '$toolName' is not available")
                else -> invoke(current, toolName, arguments)
            }
        }

        override suspend fun close() = mutex.withLock { closeSessionLocked() }

        private suspend fun openSessionLocked(): List<LlmToolDefinition> {
            val status = serverStatusProvider.status.value
            val config = settingsRepository.getServerConfig()
            loopbackPreconditionError(status, config)?.let { throw AgentToolBridgeException(it) }
            val port = (status as ServerStatus.Running).port
            val token = config.bearerToken.takeIf { config.bearerTokenEnabled }
            val current =
                Session(
                    httpClient = httpClientProvider(),
                    client =
                        Client(
                            clientInfo = Implementation(name = CLIENT_NAME, version = BuildConfig.VERSION_NAME),
                        ),
                    toolNamePrefix = McpToolUtils.buildToolNamePrefix(config.deviceSlug),
                )
            session = current
            current.client.connect(
                StreamableHttpClientTransport(
                    client = current.httpClient,
                    url = endpointUrl(port),
                    requestBuilder = { token?.let { headers.append(HttpHeaders.Authorization, "Bearer $it") } },
                ),
            )
            val definitions =
                current.client
                    .listTools(options = RequestOptions(timeout = toolRequestTimeout))
                    .tools
                    .mapNotNull { it.toAgentDefinition(current.toolNamePrefix) }
            current.toolNames = definitions.map { it.name }.toSet()
            if (AgentToolProfile.GET_SCREEN_STATE !in current.toolNames) {
                throw AgentToolBridgeException(SCREEN_TOOL_DISABLED)
            }
            return definitions
        }

        /** Returns an error result for failures, including a close() abort, unless the caller itself was cancelled. */
        private suspend fun invoke(
            current: Session,
            toolName: String,
            arguments: JsonObject,
        ): AgentToolResult =
            runCatching {
                val params = CallToolRequestParams(name = current.toolNamePrefix + toolName, arguments = arguments)
                current
                    .runInSession {
                        current.client.callTool(CallToolRequest(params), RequestOptions(timeout = toolRequestTimeout))
                    }.toAgentResult()
            }.getOrElse { error ->
                if (error is Error || !currentCoroutineContext().isActive) throw error
                agentToolError("Tool '$toolName' failed: ${error.message}")
            }

        /** Runs [block] in the session scope so close() can abort it; caller cancellation cancels it too. */
        private suspend fun <T> Session.runInSession(block: suspend () -> T): T {
            val deferred = scope.async { block() }
            return try {
                deferred.await()
            } catch (e: CancellationException) {
                deferred.cancel(e)
                throw e
            }
        }

        /** Aborts in-flight calls and releases the session even when the caller is cancelled. */
        private suspend fun closeSessionLocked() {
            val current = session ?: return
            session = null
            current.scope.cancel()
            withContext(NonCancellable) {
                try {
                    current.client.close()
                } catch (e: IOException) {
                    Logger.w(TAG, "Agent tool session close failed", e)
                } catch (e: IllegalStateException) {
                    Logger.w(TAG, "Agent tool session close failed", e)
                } finally {
                    current.httpClient.close()
                }
            }
        }

        private fun buildClient(): HttpClient =
            HttpClient(OkHttp) {
                install(SSE)
                install(HttpTimeout) {
                    requestTimeoutMillis = toolRequestTimeout.inWholeMilliseconds
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                }
            }

        companion object {
            private const val TAG = "MCP:AgentToolBridge"
            private const val CLIENT_NAME = "on-device-agent"
            private const val LOOPBACK_HOST = "127.0.0.1"
            private const val CONNECT_TIMEOUT_MS = 5_000L
            private const val SCREEN_TOOL_DISABLED =
                "Enable the get_screen_state MCP tool so the on-device agent can see the screen"
            private val DEFAULT_TOOL_REQUEST_TIMEOUT = 90.seconds
        }
    }
