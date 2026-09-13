package com.danielealbano.androidremotecontrolmcp.agent.core

import com.danielealbano.androidremotecontrolmcp.agent.llm.LlmMessage
import com.danielealbano.androidremotecontrolmcp.agent.llm.LlmToolCall
import com.danielealbano.androidremotecontrolmcp.agent.llm.THINK_END_TAG
import com.danielealbano.androidremotecontrolmcp.agent.tools.AgentToolProfile
import com.danielealbano.androidremotecontrolmcp.agent.tools.AgentToolResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Truncates [text] at a line boundary so the result is at most [maxChars] long, appending
 * [AgentPrompts.SCREEN_TRUNCATED] and keeping a trailing pagination note line when it fits.
 */
internal fun truncateAtLine(
    text: String,
    maxChars: Int,
): String {
    if (text.length <= maxChars) return text
    val marker = AgentPrompts.SCREEN_TRUNCATED
    val note =
        text
            .substringAfterLast('\n')
            .takeIf { it.startsWith(AgentPrompts.PAGINATION_NOTE_PREFIX) && it.length + marker.length + 1 <= maxChars }
    val budget = maxChars - marker.length - 1 - (note?.let { it.length + 1 } ?: 0)
    val head = text.take((budget + 1).coerceAtLeast(0)).substringBeforeLast('\n', missingDelimiterValue = "")
    return listOfNotNull(head.ifEmpty { null }, marker, note).joinToString("\n").take(maxChars)
}

/** Conversation and step bookkeeping for one run; confined to the coroutine executing the run. */
internal class AgentRunContext(
    val goal: String,
) {
    private val history = mutableListOf<LlmMessage>(LlmMessage.System(AgentPrompts.SYSTEM))
    private val recordedSteps = mutableListOf<AgentStep>()
    private val recentActions = ArrayDeque<String>()
    private var pendingHint: String? = null
    private var pendingScreen: AgentToolResult? = null
    private var missingToolCallStreak = 0

    /** Whether the open tool session includes `wait_for_idle`. */
    var canWaitForIdle = false

    val messages: List<LlmMessage>
        get() = history

    val steps: List<AgentStep>
        get() = recordedSteps

    /** Returns and clears a screen fetched by the model in the previous step, to use as this step's observation. */
    fun takePendingScreen(): AgentToolResult? {
        val screen = pendingScreen
        pendingScreen = null
        return screen
    }

    /** Appends the latest screen after compacting older observations, tool results, and thoughts. */
    fun addObservation(
        step: Int,
        maxSteps: Int,
        screen: AgentToolResult,
    ) {
        history.replaceAll { if (it is LlmMessage.User) LlmMessage.User(AgentPrompts.OMITTED_OBSERVATION) else it }
        compactOlder(
            isTarget = { it is LlmMessage.ToolResult && it.text != AgentPrompts.OMITTED_TOOL_RESULT },
            compact = { (it as LlmMessage.ToolResult).copy(text = AgentPrompts.OMITTED_TOOL_RESULT) },
        )
        compactOlder(
            isTarget = { it is LlmMessage.Assistant && it.text != null },
            compact = { (it as LlmMessage.Assistant).copy(text = null) },
        )
        val screenText = truncateAtLine(screen.text, MAX_SCREEN_CHARS)
        val observation = AgentPrompts.observation(goal, step, maxSteps, screenText, pendingHint)
        history += LlmMessage.User(observation, screen.image)
        pendingHint = null
    }

    /**
     * Records an executed call; its id is prefixed with the step number so ids stay unique in the history.
     * A successful model-requested screen is kept for the next observation instead of the tool result.
     */
    fun recordAction(
        call: LlmToolCall,
        thought: String?,
        result: AgentToolResult,
    ) {
        missingToolCallStreak = 0
        val index = recordedSteps.size + 1
        val uniqueCall = call.copy(id = "s${index}_${call.id}")
        val isScreenPage = call.name == AgentToolProfile.GET_SCREEN_STATE && !result.isError
        if (isScreenPage) pendingScreen = result
        val toolText =
            if (isScreenPage) {
                AgentPrompts.PAGE_SHOWN_AS_OBSERVATION
            } else {
                truncateAtLine(result.text, MAX_TOOL_RESULT_CHARS)
            }
        val condensedThought = condenseThought(thought)
        history += LlmMessage.Assistant(condensedThought, listOf(uniqueCall))
        history += LlmMessage.ToolResult(uniqueCall.id, toolText)
        recordedSteps +=
            AgentStep(
                index = index,
                thought = condensedThought,
                toolName = call.name,
                arguments = call.arguments.toString(),
                resultPreview = result.text.take(MAX_PREVIEW_CHARS),
                isError = result.isError,
            )
        trackRepetition("${call.name}${call.arguments}")
    }

    /** Returns [AgentRunState.Failed] after [MAX_MISSING_TOOL_CALLS] consecutive replies without a call, else null. */
    fun onMissingToolCall(text: String?): AgentRunState? {
        missingToolCallStreak++
        history += LlmMessage.Assistant(condenseThought(text), emptyList())
        pendingHint = AgentPrompts.NO_TOOL_CALL_HINT
        return if (missingToolCallStreak >= MAX_MISSING_TOOL_CALLS) failed("The model stopped calling tools") else null
    }

    fun finished(
        call: LlmToolCall,
        thought: String?,
    ): AgentRunState.Finished {
        val summary = (call.arguments["summary"] as? JsonPrimitive)?.contentOrNull ?: condenseThought(thought).orEmpty()
        return AgentRunState.Finished(goal, summary, recordedSteps.toList())
    }

    fun failed(reason: String): AgentRunState.Failed = AgentRunState.Failed(goal, reason, recordedSteps.toList())

    /**
     * Compacts matching messages except the last [KEEP_RECENT_STEPS], only once more than
     * [COMPACTION_THRESHOLD] are uncompacted, so the prompt prefix stays stable between batches.
     */
    private fun compactOlder(
        isTarget: (LlmMessage) -> Boolean,
        compact: (LlmMessage) -> LlmMessage,
    ) {
        val targets = history.indices.filter { isTarget(history[it]) }
        if (targets.size > COMPACTION_THRESHOLD) {
            targets.dropLast(KEEP_RECENT_STEPS).forEach { history[it] = compact(history[it]) }
        }
    }

    private fun condenseThought(text: String?): String? =
        text
            ?.substringAfterLast(THINK_END_TAG)
            ?.trim()
            ?.take(MAX_THOUGHT_CHARS)
            ?.ifEmpty { null }

    private fun trackRepetition(key: String) {
        recentActions.addLast(key)
        if (recentActions.size > REPETITION_WINDOW) recentActions.removeFirst()
        if (recentActions.size == REPETITION_WINDOW && recentActions.distinct().size == 1) {
            pendingHint = AgentPrompts.REPEATED_ACTION_HINT
            recentActions.clear()
        }
    }

    companion object {
        const val MAX_SCREEN_CHARS = 16_000
        const val MAX_TOOL_RESULT_CHARS = 4_000
        const val MAX_PREVIEW_CHARS = 500
        const val MAX_THOUGHT_CHARS = 1_000
        const val KEEP_RECENT_STEPS = 3
        const val COMPACTION_THRESHOLD = 2 * KEEP_RECENT_STEPS
        const val MAX_MISSING_TOOL_CALLS = 3
        const val REPETITION_WINDOW = 3
    }
}
