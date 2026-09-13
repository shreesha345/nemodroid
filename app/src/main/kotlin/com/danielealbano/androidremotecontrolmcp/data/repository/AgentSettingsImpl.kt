package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.AgentConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

private val AGENT_LLM_BASE_URL_KEY = stringPreferencesKey("agent_llm_base_url")
private val AGENT_LLM_API_KEY_KEY = stringPreferencesKey("agent_llm_api_key")
private val AGENT_LLM_MODEL_KEY = stringPreferencesKey("agent_llm_model")
private val AGENT_SEND_SCREENSHOT_KEY = booleanPreferencesKey("agent_send_screenshot")
private val AGENT_MAX_STEPS_KEY = intPreferencesKey("agent_max_steps")
private val ALLOWED_URL_SCHEMES = setOf("http", "https")
private const val INVALID_URL_MESSAGE = "URL must be an http or https URL with a host and no embedded credentials"

private fun Preferences.toAgentConfig(): AgentConfig =
    AgentConfig(
        llmBaseUrl = this[AGENT_LLM_BASE_URL_KEY] ?: "",
        llmApiKey = this[AGENT_LLM_API_KEY_KEY] ?: "",
        llmModel = this[AGENT_LLM_MODEL_KEY] ?: AgentConfig.DEFAULT_MODEL,
        sendScreenshot = this[AGENT_SEND_SCREENSHOT_KEY] ?: true,
        maxSteps = this[AGENT_MAX_STEPS_KEY] ?: AgentConfig.DEFAULT_MAX_STEPS,
    )

private fun isHttpUrlWithHost(uri: URI?): Boolean =
    uri != null && uri.scheme?.lowercase() in ALLOWED_URL_SCHEMES && hasHostWithoutCredentials(uri)

/** Uses the raw authority because `URI.host` is null for registry-based names such as `gpu_box`. */
private fun hasHostWithoutCredentials(uri: URI): Boolean {
    val authority = uri.rawAuthority.orEmpty()
    return authority.isNotBlank() && '@' !in authority && !authority.startsWith(":")
}

@Singleton
class AgentSettingsImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val settingsChangeLogger: SettingsChangeLogger,
    ) : AgentSettings {
        override val agentConfig: Flow<AgentConfig> = dataStore.data.map { it.toAgentConfig() }

        override suspend fun getAgentConfig(): AgentConfig = agentConfig.first()

        override suspend fun updateAgentLlmBaseUrl(url: String) =
            update(AGENT_LLM_BASE_URL_KEY, "agent_llm_base_url", url, "") { _, _ -> "Agent LLM endpoint changed" }

        override suspend fun updateAgentLlmApiKey(apiKey: String) =
            update(AGENT_LLM_API_KEY_KEY, "agent_llm_api_key", apiKey, "") { _, _ -> "Agent LLM API key changed" }

        override suspend fun updateAgentLlmModel(model: String) =
            update(AGENT_LLM_MODEL_KEY, "agent_llm_model", model, AgentConfig.DEFAULT_MODEL) { o, n ->
                "Agent LLM model changed $o → $n"
            }

        override suspend fun updateAgentSendScreenshot(enabled: Boolean) =
            update(AGENT_SEND_SCREENSHOT_KEY, "agent_send_screenshot", enabled, true) { _, n ->
                "Agent screenshots ${if (n.toBoolean()) "enabled" else "disabled"}"
            }

        override suspend fun updateAgentMaxSteps(maxSteps: Int) =
            update(AGENT_MAX_STEPS_KEY, "agent_max_steps", maxSteps, AgentConfig.DEFAULT_MAX_STEPS) { o, n ->
                "Agent max steps changed $o → $n"
            }

        override fun validateAgentLlmBaseUrl(url: String): Result<String> {
            val trimmed = url.trim()
            return if (isHttpUrlWithHost(runCatching { URI(trimmed) }.getOrNull())) {
                Result.success(trimmed)
            } else {
                Result.failure(IllegalArgumentException(INVALID_URL_MESSAGE))
            }
        }

        override fun validateAgentLlmModel(model: String): Result<String> {
            val trimmed = model.trim()
            return if (trimmed.isNotEmpty() && trimmed.length <= AgentConfig.MAX_MODEL_LENGTH) {
                Result.success(trimmed)
            } else {
                Result.failure(
                    IllegalArgumentException("Model must be 1 to ${AgentConfig.MAX_MODEL_LENGTH} characters"),
                )
            }
        }

        override fun validateAgentMaxSteps(maxSteps: Int): Result<Int> =
            if (maxSteps in AgentConfig.MIN_MAX_STEPS..AgentConfig.MAX_MAX_STEPS) {
                Result.success(maxSteps)
            } else {
                Result.failure(
                    IllegalArgumentException(
                        "Max steps must be between ${AgentConfig.MIN_MAX_STEPS} and ${AgentConfig.MAX_MAX_STEPS}",
                    ),
                )
            }

        private suspend fun <T : Any> update(
            key: Preferences.Key<T>,
            coalesceKey: String,
            newValue: T,
            default: T,
            render: (old: String, new: String) -> String,
        ) {
            dataStore.edit { prefs ->
                val old = prefs[key] ?: default
                prefs[key] = newValue
                settingsChangeLogger.submit(coalesceKey, old.toString(), newValue.toString(), render)
            }
        }
    }
