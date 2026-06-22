package com.akhavanskii.aichallenge.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Named

class DeepSeekAgent
    @Inject
    constructor(
        @param:Named(DEEPSEEK_API_KEY_NAME) private val apiKey: String,
        @param:Named(DEEPSEEK_ENDPOINT_NAME) private val endpoint: String,
        private val callFactory: Call.Factory,
        private val json: Json,
        @param:NetworkDispatcher private val dispatcher: CoroutineDispatcher,
    ) : LlmAgent {
        override suspend fun countTokens(
            messages: List<AgentMessage>,
            systemInstruction: String?,
            modelName: String?,
        ): AgentResult<Int> =
            GeminiResult.Failure(
                GeminiNetworkError.UnsupportedOperation(
                    providerName = PROVIDER_NAME,
                    operation = "Token counting",
                ),
            )

        override suspend fun sendMessage(
            messages: List<AgentMessage>,
            systemInstruction: String?,
            generationConfig: GeminiGenerationConfig?,
            modelName: String?,
            totalTokenLimit: Int?,
        ): AgentResult<String> {
            val normalizedMessages = messages.normalized()
            val latestUserMessage = normalizedMessages.lastOrNull() as? AgentMessage.User
            if (latestUserMessage == null) {
                Timber.tag(LOG_TAG).w("DeepSeek request skipped: empty prompt.")
                return GeminiResult.Failure(GeminiNetworkError.EmptyPrompt)
            }
            if (apiKey.isBlank()) {
                Timber.tag(LOG_TAG).w("DeepSeek request skipped: missing API key.")
                return GeminiResult.Failure(
                    GeminiNetworkError.MissingProviderApiKey(
                        providerName = PROVIDER_NAME,
                        keyName = "DEEPSEEK_API_KEY",
                    ),
                )
            }

            val normalizedModelName = modelName?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MODEL_NAME
            val requestId = REQUEST_IDS.incrementAndGet()
            return withContext(dispatcher) {
                runCatching {
                    executeRequest(
                        requestId = requestId,
                        messages = normalizedMessages,
                        systemInstruction = systemInstruction?.trim()?.takeIf { it.isNotEmpty() },
                        generationConfig = generationConfig,
                        modelName = normalizedModelName,
                    )
                }.getOrElse { throwable ->
                    Timber
                        .tag(LOG_TAG)
                        .e(
                            throwable,
                            "DeepSeek request #%d failed before a parsed result. model=%s messages=%d latestPromptChars=%d",
                            requestId,
                            normalizedModelName,
                            normalizedMessages.size,
                            latestUserMessage.text.length,
                        )
                    when (throwable) {
                        is CancellationException -> throw throwable
                        is IOException ->
                            GeminiResult.Failure(
                                GeminiNetworkError.ProviderNetwork(
                                    providerName = PROVIDER_NAME,
                                    cause = throwable.message,
                                ),
                            )
                        is SerializationException ->
                            GeminiResult.Failure(
                                GeminiNetworkError.ProviderSerialization(
                                    providerName = PROVIDER_NAME,
                                    cause = throwable.message,
                                ),
                            )
                        else ->
                            GeminiResult.Failure(
                                GeminiNetworkError.ProviderNetwork(
                                    providerName = PROVIDER_NAME,
                                    cause = throwable.message,
                                ),
                            )
                    }
                }
            }
        }

        private suspend fun executeRequest(
            requestId: Long,
            messages: List<AgentMessage>,
            systemInstruction: String?,
            generationConfig: GeminiGenerationConfig?,
            modelName: String,
        ): AgentResult<String> {
            val requestJson =
                json.encodeToString(
                    DeepSeekChatCompletionRequest.fromMessages(
                        modelName = modelName,
                        messages = messages,
                        systemInstruction = systemInstruction,
                        generationConfig = generationConfig,
                    ),
                )

            Timber
                .tag(LOG_TAG)
                .d(
                    "DeepSeek request #%d prepared. model=%s endpoint=%s messages=%d requestChars=%d",
                    requestId,
                    modelName,
                    endpoint,
                    messages.size,
                    requestJson.length,
                )
            logPayload(requestId = requestId, label = "requestJson", payload = requestJson)

            val request =
                Request
                    .Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", JSON_MEDIA_TYPE.toString())
                    .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

            repeat(MAX_ATTEMPTS) { attempt ->
                Timber
                    .tag(LOG_TAG)
                    .d(
                        "DeepSeek request #%d attempt %d/%d started. model=%s",
                        requestId,
                        attempt + 1,
                        MAX_ATTEMPTS,
                        modelName,
                    )
                val result =
                    callFactory.newCall(request).execute().use { response ->
                        val responseBody = response.body.string()
                        Timber
                            .tag(LOG_TAG)
                            .d(
                                "DeepSeek request #%d attempt %d/%d finished. status=%d success=%s responseChars=%d",
                                requestId,
                                attempt + 1,
                                MAX_ATTEMPTS,
                                response.code,
                                response.isSuccessful,
                                responseBody.length,
                            )
                        logPayload(requestId = requestId, label = "responseBody", payload = responseBody)

                        if (!response.isSuccessful) {
                            Timber
                                .tag(LOG_TAG)
                                .w(
                                    "DeepSeek request #%d failed with HTTP %d. model=%s",
                                    requestId,
                                    response.code,
                                    modelName,
                                )
                            GeminiResult.Failure(
                                GeminiNetworkError.ProviderHttp(
                                    providerName = PROVIDER_NAME,
                                    statusCode = response.code,
                                    body = responseBody.takeIf { it.isNotBlank() },
                                ),
                            )
                        } else {
                            parseResponse(responseBody)
                        }
                    }

                if (result !is GeminiResult.Failure || !result.error.shouldRetry(attempt)) {
                    return result
                }

                val retryableError = result.error as GeminiNetworkError.ProviderHttp
                Timber
                    .tag(LOG_TAG)
                    .w(
                        "DeepSeek request #%d scheduling retry after retryable HTTP %d.",
                        requestId,
                        retryableError.statusCode,
                    )
                delay(RETRY_DELAYS_MS[attempt])
            }

            error("Retry loop should always return before exhausting attempts.")
        }

        private fun parseResponse(responseBody: String): AgentResult<String> {
            if (responseBody.isBlank()) {
                Timber.tag(LOG_TAG).w("DeepSeek response body is blank.")
                return GeminiResult.Failure(GeminiNetworkError.ProviderEmptyResponse(PROVIDER_NAME))
            }

            val decoded = json.decodeFromString<DeepSeekChatCompletionResponse>(responseBody)
            val text = decoded.textOrNull()
            return if (text == null) {
                Timber.tag(LOG_TAG).w("DeepSeek response has no non-blank assistant message.")
                GeminiResult.Failure(GeminiNetworkError.ProviderEmptyResponse(PROVIDER_NAME))
            } else {
                GeminiResult.Success(
                    value = text,
                    tokenUsage = decoded.usage?.toGeminiTokenUsage(),
                )
            }
        }

        private fun GeminiNetworkError.shouldRetry(attempt: Int): Boolean {
            val hasAttemptsLeft = attempt < MAX_ATTEMPTS - 1
            return hasAttemptsLeft && this is GeminiNetworkError.ProviderHttp && statusCode in RETRYABLE_HTTP_STATUS_CODES
        }

        private fun List<AgentMessage>.normalized(): List<AgentMessage> =
            mapNotNull { message ->
                val text = message.text.trim()
                if (text.isEmpty()) {
                    null
                } else {
                    when (message) {
                        is AgentMessage.User -> AgentMessage.User(text)
                        is AgentMessage.Model -> AgentMessage.Model(text)
                    }
                }
            }

        private fun logPayload(
            requestId: Long,
            label: String,
            payload: String,
        ) {
            if (payload.isBlank()) {
                Timber.tag(LOG_TAG).d("DeepSeek request #%d %s=<blank>", requestId, label)
                return
            }

            val chunks = payload.chunked(LOG_CHUNK_SIZE)
            chunks.forEachIndexed { index, chunk ->
                Timber
                    .tag(LOG_TAG)
                    .d(
                        "DeepSeek request #%d %s chunk %d/%d: %s",
                        requestId,
                        label,
                        index + 1,
                        chunks.size,
                        chunk,
                    )
            }
        }

        private companion object {
            const val PROVIDER_NAME = "DeepSeek"
            const val LOG_TAG = "DeepSeekNetwork"
            const val DEFAULT_MODEL_NAME = "deepseek-v4-flash"
            const val MAX_ATTEMPTS = 3
            const val LOG_CHUNK_SIZE = 3500
            val REQUEST_IDS = AtomicLong(0L)
            val RETRY_DELAYS_MS = longArrayOf(300L, 900L)
            val RETRYABLE_HTTP_STATUS_CODES = setOf(429, 500, 502, 503, 504)
            val JSON_MEDIA_TYPE = "application/json".toMediaType()
        }
    }
