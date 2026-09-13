package com.danielealbano.androidremotecontrolmcp.agent.llm

/** Chat-completion client for OpenAI-compatible endpoints. */
interface LlmClient {
    /** Sends [messages] offering [tools]; failures are [LlmException]s. Cancellation propagates. */
    suspend fun complete(
        endpoint: LlmEndpoint,
        messages: List<LlmMessage>,
        tools: List<LlmToolDefinition>,
    ): Result<LlmResponse>
}
