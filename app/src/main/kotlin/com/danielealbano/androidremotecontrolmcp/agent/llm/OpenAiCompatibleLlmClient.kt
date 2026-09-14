package com.danielealbano.androidremotecontrolmcp.agent.llm

import com.danielealbano.androidremotecontrolmcp.agent.runCatchingNonCancellation
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLParserException
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenAiCompatibleLlmClient
    @Inject
    constructor(
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : LlmClient {
        // Process-lifetime client owned by this singleton; tests replace clientProvider before any call.
        private val sharedClient: HttpClient by lazy { buildClient() }
        internal var clientProvider: () -> HttpClient = { sharedClient }

        override suspend fun complete(
            endpoint: LlmEndpoint,
            messages: List<LlmMessage>,
            tools: List<LlmToolDefinition>,
        ): Result<LlmResponse> =
            withContext(ioDispatcher) {
                runCatchingNonCancellation { send(endpoint, messages, tools) }
                    .fold(onSuccess = { Result.success(it) }, onFailure = { Result.failure(toLlmException(it)) })
            }

        private suspend fun send(
            endpoint: LlmEndpoint,
            messages: List<LlmMessage>,
            tools: List<LlmToolDefinition>,
        ): LlmResponse {
            val response =
                clientProvider().post(chatCompletionsUrl(endpoint.baseUrl)) {
                    if (endpoint.apiKey.isNotBlank()) {
                        header(HttpHeaders.Authorization, "Bearer ${endpoint.apiKey}")
                    }
                    contentType(ContentType.Application.Json)
                    setBody(ChatCompletionCodec.encodeRequest(endpoint.model, messages, tools).toString())
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                val excerpt = body.take(MAX_ERROR_BODY_CHARS)
                throw LlmException("LLM endpoint returned HTTP ${response.status.value}: $excerpt")
            }
            return ChatCompletionCodec.decodeResponse(body).withTextToolCallFallback(tools)
        }

        /** @throws LlmException when [baseUrl] cannot be parsed into a request URL. */
        private fun chatCompletionsUrl(baseUrl: String): Url {
            val raw = "${baseUrl.trimEnd('/')}/chat/completions"
            return try {
                Url(URI(raw).toString())
            } catch (e: URISyntaxException) {
                throw LlmException("Invalid LLM endpoint URL: ${e.message}", e)
            } catch (e: URLParserException) {
                throw LlmException("Invalid LLM endpoint URL: ${e.message}", e)
            }
        }

        /** Maps request failures to user-presentable [LlmException]s; JVM [Error]s are rethrown. */
        private fun toLlmException(error: Throwable): LlmException =
            when (error) {
                is Error -> {
                    throw error
                }

                is LlmException -> {
                    error
                }

                is IOException -> {
                    Logger.w(TAG, "LLM request failed", error)
                    LlmException("Cannot reach the LLM endpoint: ${error.message}", error)
                }

                else -> {
                    Logger.w(TAG, "LLM request failed unexpectedly", error)
                    LlmException("LLM request failed: ${error.message}", error)
                }
            }

        private fun LlmResponse.withTextToolCallFallback(tools: List<LlmToolDefinition>): LlmResponse {
            val parsed =
                if (toolCalls.isEmpty() && !text.isNullOrBlank()) ToolCallTextParser.parse(text, tools) else emptyList()
            return if (parsed.isEmpty()) {
                this
            } else {
                LlmResponse(ToolCallTextParser.stripToolCalls(text.orEmpty()), parsed)
            }
        }

        private fun buildClient(): HttpClient =
            HttpClient(OkHttp) {
                install(HttpTimeout) {
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                    socketTimeoutMillis = REQUEST_TIMEOUT_MS
                }
            }

        companion object {
            private const val TAG = "MCP:AgentLlmClient"
            private const val REQUEST_TIMEOUT_MS = 180_000L
            private const val CONNECT_TIMEOUT_MS = 15_000L
            private const val MAX_ERROR_BODY_CHARS = 300
        }
    }
