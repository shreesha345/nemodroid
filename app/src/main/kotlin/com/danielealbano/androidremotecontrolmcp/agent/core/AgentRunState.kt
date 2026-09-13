package com.danielealbano.androidremotecontrolmcp.agent.core

/** One executed action. [resultPreview] is truncated for display. */
data class AgentStep(
    val index: Int,
    val thought: String?,
    val toolName: String,
    val arguments: String,
    val resultPreview: String,
    val isError: Boolean,
)

/** Observable state of the current or last agent run. */
sealed class AgentRunState {
    data object Idle : AgentRunState()

    data class Running(
        val goal: String,
        val steps: List<AgentStep>,
        val maxSteps: Int,
    ) : AgentRunState()

    data class Finished(
        val goal: String,
        val summary: String,
        val steps: List<AgentStep>,
    ) : AgentRunState()

    data class Failed(
        val goal: String,
        val reason: String,
        val steps: List<AgentStep>,
    ) : AgentRunState()

    data class Cancelled(
        val goal: String,
        val steps: List<AgentStep>,
    ) : AgentRunState()
}
