package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.data.model.AgentConfig
import kotlinx.coroutines.flow.Flow

/** On-device agent settings slice. Accessed only through [SettingsRepository]. */
interface AgentSettings {
    /** Observes the agent configuration. */
    val agentConfig: Flow<AgentConfig>

    /** Returns the current agent configuration as a one-shot read. */
    suspend fun getAgentConfig(): AgentConfig

    /** Updates the LLM base URL. Must pass [validateAgentLlmBaseUrl] first. */
    suspend fun updateAgentLlmBaseUrl(url: String)

    /** Updates the LLM API key; an empty string clears it. */
    suspend fun updateAgentLlmApiKey(apiKey: String)

    /** Updates the LLM model identifier. Must pass [validateAgentLlmModel] first. */
    suspend fun updateAgentLlmModel(model: String)

    /** Updates whether observations include a screenshot. */
    suspend fun updateAgentSendScreenshot(enabled: Boolean)

    /** Updates the per-task step limit. Must pass [validateAgentMaxSteps] first. */
    suspend fun updateAgentMaxSteps(maxSteps: Int)

    /** Validates an LLM base URL (http/https, with a host, without user info). Pure; returns the trimmed URL. */
    fun validateAgentLlmBaseUrl(url: String): Result<String>

    /** Validates a model identifier (1..[AgentConfig.MAX_MODEL_LENGTH] chars after trimming). Pure. */
    fun validateAgentLlmModel(model: String): Result<String>

    /** Validates a step limit in [AgentConfig.MIN_MAX_STEPS]..[AgentConfig.MAX_MAX_STEPS]. Pure. */
    fun validateAgentMaxSteps(maxSteps: Int): Result<Int>
}
