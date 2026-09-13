package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Settings for the on-device agent.
 *
 * @property llmBaseUrl OpenAI-compatible API base URL including the version path (e.g. `https://host/v1`);
 *   empty = not configured.
 * @property llmApiKey Bearer API key for the LLM endpoint; empty = no Authorization header.
 * @property llmModel Model identifier sent with chat completion requests.
 * @property sendScreenshot Whether each observation includes the annotated screenshot.
 * @property maxSteps Maximum number of actions per task.
 */
data class AgentConfig(
    val llmBaseUrl: String = "",
    val llmApiKey: String = "",
    val llmModel: String = DEFAULT_MODEL,
    val sendScreenshot: Boolean = true,
    val maxSteps: Int = DEFAULT_MAX_STEPS,
) {
    /** Masks [llmApiKey] so the key never reaches logs through string interpolation. */
    override fun toString(): String =
        "AgentConfig(llmBaseUrl=$llmBaseUrl, llmApiKey=${if (llmApiKey.isEmpty()) "" else MASK}, " +
            "llmModel=$llmModel, sendScreenshot=$sendScreenshot, maxSteps=$maxSteps)"

    companion object {
        const val DEFAULT_MODEL = "nemotron-3-nano-omni"
        const val DEFAULT_MAX_STEPS = 30
        const val MIN_MAX_STEPS = 1
        const val MAX_MAX_STEPS = 100
        const val MAX_MODEL_LENGTH = 200
        private const val MASK = "***"
    }
}
