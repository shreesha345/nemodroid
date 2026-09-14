package com.danielealbano.androidremotecontrolmcp.agent.core

import com.danielealbano.androidremotecontrolmcp.agent.llm.LlmClient
import com.danielealbano.androidremotecontrolmcp.agent.llm.LlmEndpoint
import com.danielealbano.androidremotecontrolmcp.agent.llm.LlmResponse
import com.danielealbano.androidremotecontrolmcp.agent.llm.LlmToolCall
import com.danielealbano.androidremotecontrolmcp.agent.llm.LlmToolDefinition
import com.danielealbano.androidremotecontrolmcp.agent.tools.AgentToolBridge
import com.danielealbano.androidremotecontrolmcp.agent.tools.AgentToolProfile
import com.danielealbano.androidremotecontrolmcp.agent.tools.AgentToolResult
import com.danielealbano.androidremotecontrolmcp.data.model.AgentConfig
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class AgentRunnerImpl
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val llmClient: LlmClient,
        private val toolBridge: AgentToolBridge,
    ) : AgentRunner {
        private val mutableState = MutableStateFlow<AgentRunState>(AgentRunState.Idle)
        override val state: StateFlow<AgentRunState> = mutableState.asStateFlow()
        private val active = AtomicBoolean(false)

        override suspend fun run(goal: String) {
            if (!active.compareAndSet(false, true)) {
                Logger.w(TAG, "Agent run ignored: another run is active")
                return
            }
            val context = AgentRunContext(goal)
            try {
                mutableState.value = execute(context)
            } catch (e: CancellationException) {
                mutableState.value = AgentRunState.Cancelled(goal, context.steps.toList())
                throw e
            } catch (e: IOException) {
                Logger.e(TAG, "Agent run failed on I/O", e)
                mutableState.value = context.failed("Cannot read agent settings: ${e.message}")
            } catch (e: IllegalStateException) {
                Logger.e(TAG, "Agent run failed", e)
                mutableState.value = context.failed(e.message ?: "Unexpected agent error")
            } finally {
                withContext(NonCancellable) { toolBridge.close() }
                active.set(false)
                Logger.i(TAG, "Agent run ended: ${mutableState.value::class.simpleName}")
            }
        }

        private suspend fun execute(context: AgentRunContext): AgentRunState {
            val config = settingsRepository.getAgentConfig()
            mutableState.value = AgentRunState.Running(context.goal, emptyList(), config.maxSteps)
            return if (config.llmBaseUrl.isBlank()) {
                context.failed("Configure the LLM endpoint in agent settings")
            } else {
                toolBridge.open().fold(
                    onSuccess = { tools ->
                        context.canWaitForIdle = tools.any { it.name == AgentToolProfile.WAIT_FOR_IDLE }
                        loop(context, config, tools + AgentPrompts.FINISH_DEFINITION)
                    },
                    onFailure = { context.failed(it.message ?: "Cannot open the agent tool session") },
                )
            }
        }

        private suspend fun loop(
            context: AgentRunContext,
            config: AgentConfig,
            tools: List<LlmToolDefinition>,
        ): AgentRunState {
            val endpoint = LlmEndpoint(config.llmBaseUrl, config.llmApiKey, config.llmModel)
            for (step in 1..config.maxSteps) {
                mutableState.value = AgentRunState.Running(context.goal, context.steps.toList(), config.maxSteps)
                runStep(context, step, config, endpoint, tools)?.let { return it }
            }
            return context.failed("Reached the limit of ${config.maxSteps} steps")
        }

        /** One observe → decide → act cycle; returns a terminal state or null to continue. */
        private suspend fun runStep(
            context: AgentRunContext,
            step: Int,
            config: AgentConfig,
            endpoint: LlmEndpoint,
            tools: List<LlmToolDefinition>,
        ): AgentRunState? {
            val screen = context.takePendingScreen() ?: observeScreen(config.sendScreenshot)
            return if (screen.isError) {
                context.failed("Cannot read the screen: ${screen.text.take(MAX_REASON_CHARS)}")
            } else {
                context.addObservation(step, config.maxSteps, screen)
                llmClient.complete(endpoint, context.messages.toList(), tools).fold(
                    onSuccess = { handleResponse(context, it) },
                    onFailure = { context.failed(it.message ?: "LLM request failed") },
                )
            }
        }

        /** Captures the screen; when a screenshot was requested and capture fails, retries once with nodes only. */
        private suspend fun observeScreen(withScreenshot: Boolean): AgentToolResult {
            val screen = captureScreen(withScreenshot)
            if (!screen.isError || !withScreenshot) return screen
            val nodesOnly = captureScreen(includeScreenshot = false)
            return if (nodesOnly.isError) {
                nodesOnly
            } else {
                nodesOnly.copy(text = "${AgentPrompts.SCREENSHOT_UNAVAILABLE}\n${nodesOnly.text}")
            }
        }

        private suspend fun captureScreen(includeScreenshot: Boolean): AgentToolResult =
            toolBridge.call(
                AgentToolProfile.GET_SCREEN_STATE,
                buildJsonObject { put("include_screenshot", includeScreenshot) },
            )

        private suspend fun handleResponse(
            context: AgentRunContext,
            response: LlmResponse,
        ): AgentRunState? {
            val call = response.toolCalls.firstOrNull()
            return when {
                call == null -> {
                    context.onMissingToolCall(response.text)
                }

                call.name == AgentPrompts.FINISH_TOOL -> {
                    context.finished(call, response.text)
                }

                else -> {
                    act(context, call, response.text)
                    null
                }
            }
        }

        private suspend fun act(
            context: AgentRunContext,
            call: LlmToolCall,
            thought: String?,
        ) {
            Logger.i(TAG, "Agent step ${context.steps.size + 1}: ${call.name}")
            val result = toolBridge.call(call.name, call.arguments)
            context.recordAction(call, thought, result)
            if (!result.isError && call.name != AgentToolProfile.GET_SCREEN_STATE) {
                if (context.canWaitForIdle) {
                    toolBridge.call(AgentToolProfile.WAIT_FOR_IDLE, buildJsonObject { put("timeout", IDLE_TIMEOUT_MS) })
                } else {
                    delay(SETTLE_DELAY_MS)
                }
            }
        }

        companion object {
            private const val TAG = "MCP:AgentRunner"
            private const val MAX_REASON_CHARS = 300
            private const val IDLE_TIMEOUT_MS = 3_000
            private const val SETTLE_DELAY_MS = 1_500L
        }
    }
