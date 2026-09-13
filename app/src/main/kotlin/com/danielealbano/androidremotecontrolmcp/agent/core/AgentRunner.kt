package com.danielealbano.androidremotecontrolmcp.agent.core

import kotlinx.coroutines.flow.StateFlow

/** Runs one natural-language task at a time against the device. */
interface AgentRunner {
    /** State of the current run, or the terminal state of the last run. */
    val state: StateFlow<AgentRunState>

    /**
     * Executes [goal] until a terminal state. Cancelling the caller publishes [AgentRunState.Cancelled].
     * Ignored when a run is already active.
     */
    suspend fun run(goal: String)
}
