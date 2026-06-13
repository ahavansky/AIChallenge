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

class GeminiAgent
    @Inject
    constructor(
        @param:Named(GEMINI_API_KEY_NAME) private val apiKey: String,
        @param:Named(GEMINI_ENDPOINT_NAME) private val endpoint: String,
        private val callFactory: Call.Factory,
        private val json: Json,
        @param:NetworkDispatcher private val dispatcher: CoroutineDispatcher,
    ) : LlmAgent {
        override suspend fun countTokens(
            messages: List<AgentMessage>,
            modelName: String?,
        ): AgentResult<Int> {
            val normalizedMessages = messages.normalized()
            if (normalizedMessages.isEmpty()) {
                Timber.tag(LOG_TAG).w("Gemini token count skipped: empty prompt.")
                return GeminiResult.Failure(GeminiNetworkError.EmptyPrompt)
            }
            if (apiKey.isBlank()) {
                Timber.tag(LOG_TAG).w("Gemini token count skipped: missing API key.")
                return GeminiResult.Failure(GeminiNetworkError.MissingApiKey)
            }

            val requestId = REQUEST_IDS.incrementAndGet()
            return withContext(dispatcher) {
                runCatching {
                    val requestEndpoint = endpoint.withModelName(modelName, requestId)
                    val tokenCount =
                        countTokensOrNull(
                            requestId = requestId,
                            requestEndpoint = requestEndpoint,
                            messages = normalizedMessages,
                        )
                    tokenCount
                        ?.let { GeminiResult.Success(it) }
                        ?: GeminiResult.Failure(GeminiNetworkError.EmptyResponse)
                }.getOrElse { throwable ->
                    Timber
                        .tag(LOG_TAG)
                        .e(
                            throwable,
                            "Gemini token count #%d failed before a parsed result. messages=%d",
                            requestId,
                            normalizedMessages.size,
                        )
                    when (throwable) {
                        is CancellationException -> throw throwable
                        is IOException -> GeminiResult.Failure(GeminiNetworkError.Network(throwable.message))
                        is SerializationException -> GeminiResult.Failure(GeminiNetworkError.Serialization(throwable.message))
                        else -> GeminiResult.Failure(GeminiNetworkError.Network(throwable.message))
                    }
                }
            }
        }

        override suspend fun sendMessage(
            messages: List<AgentMessage>,
            generationConfig: GeminiGenerationConfig?,
            modelName: String?,
            totalTokenLimit: Int?,
        ): AgentResult<String> {
            val normalizedMessages = messages.normalized()
            val latestUserMessage = normalizedMessages.lastOrNull() as? AgentMessage.User
            if (latestUserMessage == null) {
                Timber.tag(LOG_TAG).w("Gemini request skipped: empty prompt.")
                return GeminiResult.Failure(GeminiNetworkError.EmptyPrompt)
            }
            if (apiKey.isBlank()) {
                Timber.tag(LOG_TAG).w("Gemini request skipped: missing API key.")
                return GeminiResult.Failure(GeminiNetworkError.MissingApiKey)
            }

            val requestId = REQUEST_IDS.incrementAndGet()
            return withContext(dispatcher) {
                runCatching { executeRequest(requestId, normalizedMessages, generationConfig, modelName, totalTokenLimit) }
                    .getOrElse { throwable ->
                        Timber
                            .tag(LOG_TAG)
                            .e(
                                throwable,
                                "Gemini request #%d failed before a parsed result. configured=%s messages=%d latestPromptChars=%d",
                                requestId,
                                generationConfig != null,
                                normalizedMessages.size,
                                latestUserMessage.text.length,
                            )
                        when (throwable) {
                            is CancellationException -> throw throwable
                            is IOException -> GeminiResult.Failure(GeminiNetworkError.Network(throwable.message))
                            is SerializationException -> GeminiResult.Failure(GeminiNetworkError.Serialization(throwable.message))
                            else -> GeminiResult.Failure(GeminiNetworkError.Network(throwable.message))
                        }
                    }
            }
        }

        private suspend fun executeRequest(
            requestId: Long,
            messages: List<AgentMessage>,
            generationConfig: GeminiGenerationConfig?,
            modelName: String?,
            totalTokenLimit: Int?,
        ): GeminiResult<String> {
            val requestEndpoint = endpoint.withModelName(modelName, requestId)
            val latestUserMessage = messages.lastOrNull() as? AgentMessage.User
            val currentRequestTokens =
                latestUserMessage?.let { message ->
                    countTokensOrNull(
                        requestId = requestId,
                        requestEndpoint = requestEndpoint,
                        messages = listOf(message),
                    )
                }
            val tokenLimit = totalTokenLimit?.takeIf { it > 0 }
            val tokenWindow =
                messages.toSlidingTokenWindow(
                    requestId = requestId,
                    requestEndpoint = requestEndpoint,
                    totalTokenLimit = tokenLimit,
                )
            val tokenWindowOutputBudget =
                tokenLimit?.let { limit ->
                    tokenWindow.promptTokens?.let { promptTokens ->
                        (limit - promptTokens).coerceAtLeast(MIN_OUTPUT_TOKEN_BUDGET)
                    }
                }
            val effectiveGenerationConfig =
                generationConfig
                    .withMaxOutputTokens(tokenWindowOutputBudget)
                    ?.withoutUnsupportedFields(requestId, requestEndpoint)
            val maxOutputTokens = effectiveGenerationConfig?.maxOutputTokens
            val requestJson =
                json.encodeToString(
                    GenerateContentRequest.fromMessages(
                        messages = tokenWindow.messages,
                        generationConfig = effectiveGenerationConfig?.toDto(json),
                    ),
                )
            val isConfigured = effectiveGenerationConfig != null
            val latestPromptChars = latestUserMessage?.text?.length ?: 0
            Timber
                .tag(LOG_TAG)
                .d(
                    "Gemini request #%d prepared. configured=%s model=%s endpoint=%s messages=%d latestPromptChars=%d currentRequestTokens=%s promptTokens=%s slidingWindow=%s totalTokenLimit=%s maxOutputTokens=%s requestChars=%d",
                    requestId,
                    isConfigured,
                    requestEndpoint.modelNameOrUnknown(),
                    requestEndpoint,
                    tokenWindow.messages.size,
                    latestPromptChars,
                    currentRequestTokens?.toString() ?: "unknown",
                    tokenWindow.promptTokens?.toString() ?: "unknown",
                    tokenWindow.isTrimmed,
                    tokenLimit?.toString() ?: "none",
                    maxOutputTokens?.toString() ?: "default",
                    requestJson.length,
                )
            logPayload(requestId = requestId, label = "requestJson", payload = requestJson)

            val request =
                Request
                    .Builder()
                    .url(requestEndpoint)
                    .header("x-goog-api-key", apiKey)
                    .header("Content-Type", JSON_MEDIA_TYPE.toString())
                    .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

            repeat(MAX_ATTEMPTS) { attempt ->
                Timber
                    .tag(LOG_TAG)
                    .d(
                        "Gemini request #%d attempt %d/%d started. configured=%s",
                        requestId,
                        attempt + 1,
                        MAX_ATTEMPTS,
                        isConfigured,
                    )
                val result =
                    callFactory.newCall(request).execute().use { response ->
                        val responseBody = response.body.string()
                        Timber
                            .tag(LOG_TAG)
                            .d(
                                "Gemini request #%d attempt %d/%d finished. status=%d success=%s responseChars=%d",
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
                                    "Gemini request #%d failed with HTTP %d. configured=%s",
                                    requestId,
                                    response.code,
                                    isConfigured,
                                )
                            GeminiResult.Failure(
                                GeminiNetworkError.Http(
                                    statusCode = response.code,
                                    body = responseBody.takeIf { it.isNotBlank() },
                                ),
                            )
                        } else {
                            parseResponse(
                                responseBody = responseBody,
                                currentRequestTokens = currentRequestTokens,
                                slidingWindowApplied = tokenWindow.isTrimmed,
                            )
                        }
                    }

                if (result !is GeminiResult.Failure || !result.error.shouldRetry(attempt)) {
                    return result
                }

                val retryableError = result.error as GeminiNetworkError.Http
                Timber
                    .tag(LOG_TAG)
                    .w(
                        "Gemini request #%d scheduling retry after retryable HTTP %d.",
                        requestId,
                        retryableError.statusCode,
                    )
                delay(RETRY_DELAYS_MS[attempt])
            }

            error("Retry loop should always return before exhausting attempts.")
        }

        private fun countTokensOrNull(
            requestId: Long,
            requestEndpoint: String,
            messages: List<AgentMessage>,
        ): Int? =
            runCatching {
                val countTokensEndpoint =
                    requestEndpoint.toCountTokensEndpoint()
                        ?: return null.also {
                            Timber
                                .tag(LOG_TAG)
                                .d(
                                    "Gemini request #%d skips current request token count because endpoint=%s is not a generateContent URL.",
                                    requestId,
                                    requestEndpoint,
                                )
                        }
                val requestJson = json.encodeToString(CountTokensRequest.fromMessages(messages))
                val request =
                    Request
                        .Builder()
                        .url(countTokensEndpoint)
                        .header("x-goog-api-key", apiKey)
                        .header("Content-Type", JSON_MEDIA_TYPE.toString())
                        .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                callFactory.newCall(request).execute().use { response ->
                    val responseBody = response.body.string()
                    if (!response.isSuccessful || responseBody.isBlank()) {
                        Timber
                            .tag(LOG_TAG)
                            .w(
                                "Gemini request #%d current request token count unavailable. status=%d responseChars=%d",
                                requestId,
                                response.code,
                                responseBody.length,
                            )
                        return null
                    }

                    json.decodeFromString<CountTokensResponse>(responseBody).totalTokens.also { tokens ->
                        Timber
                            .tag(LOG_TAG)
                            .d(
                                "Gemini request #%d current request token count=%s",
                                requestId,
                                tokens?.toString() ?: "unknown",
                            )
                    }
                }
            }.getOrElse { throwable ->
                if (throwable is CancellationException) throw throwable
                Timber
                    .tag(LOG_TAG)
                    .w(throwable, "Gemini request #%d current request token count failed.", requestId)
                null
            }

        private fun List<AgentMessage>.toSlidingTokenWindow(
            requestId: Long,
            requestEndpoint: String,
            totalTokenLimit: Int?,
        ): SlidingTokenWindow {
            if (totalTokenLimit == null) {
                return SlidingTokenWindow(messages = this, promptTokens = null, isTrimmed = false)
            }

            var candidate = this
            var promptTokens =
                countTokensOrNull(
                    requestId = requestId,
                    requestEndpoint = requestEndpoint,
                    messages = candidate,
                )
            if (promptTokens == null || promptTokens <= totalTokenLimit) {
                return SlidingTokenWindow(messages = candidate, promptTokens = promptTokens, isTrimmed = false)
            }

            var isTrimmed = false
            while (candidate.size > 1 && promptTokens != null && promptTokens > totalTokenLimit) {
                val trimmedCandidate = candidate.withoutOldestContextTurn()
                if (trimmedCandidate == candidate) break
                candidate = trimmedCandidate
                isTrimmed = true
                promptTokens =
                    countTokensOrNull(
                        requestId = requestId,
                        requestEndpoint = requestEndpoint,
                        messages = candidate,
                    )
            }

            if (isTrimmed) {
                Timber
                    .tag(LOG_TAG)
                    .d(
                        "Gemini request #%d applied sliding window. retainedMessages=%d promptTokens=%s totalTokenLimit=%d",
                        requestId,
                        candidate.size,
                        promptTokens?.toString() ?: "unknown",
                        totalTokenLimit,
                    )
            }
            return SlidingTokenWindow(messages = candidate, promptTokens = promptTokens, isTrimmed = isTrimmed)
        }

        private fun List<AgentMessage>.withoutOldestContextTurn(): List<AgentMessage> {
            if (size <= 1) return this
            val latestUserMessage = last()
            val context = dropLast(1)
            if (context.isEmpty()) return listOf(latestUserMessage)

            val dropCount =
                if (context.firstOrNull() is AgentMessage.User && context.getOrNull(1) is AgentMessage.Model) {
                    2
                } else {
                    1
                }
            return context
                .drop(dropCount)
                .dropWhile { it is AgentMessage.Model }
                .plus(latestUserMessage)
        }

        private fun List<AgentMessage>.normalized(): List<AgentMessage> =
            mapNotNull { message ->
                val text = message.text.trim()
                if (text.isBlank()) {
                    null
                } else {
                    when (message) {
                        is AgentMessage.User -> AgentMessage.User(text)
                        is AgentMessage.Model -> AgentMessage.Model(text)
                    }
                }
            }

        private fun GeminiGenerationConfig.withoutUnsupportedFields(
            requestId: Long,
            requestEndpoint: String,
        ): GeminiGenerationConfig {
            val omittedPenaltyFields =
                listOfNotNull(
                    "presencePenalty".takeIf { presencePenalty != null },
                    "frequencyPenalty".takeIf { frequencyPenalty != null },
                )
            if (omittedPenaltyFields.isEmpty() || !requestEndpoint.targetsGemini35Flash()) {
                return this
            }

            Timber
                .tag(LOG_TAG)
                .w(
                    "Gemini request #%d omits unsupported generationConfig fields for model=%s: %s",
                    requestId,
                    GEMINI_MODEL_NAME,
                    omittedPenaltyFields.joinToString(),
                )
            return copy(
                presencePenalty = null,
                frequencyPenalty = null,
            )
        }

        private fun GeminiGenerationConfig?.withMaxOutputTokens(maxOutputTokens: Int?): GeminiGenerationConfig? {
            if (maxOutputTokens == null) return this
            val clampedMaxOutputTokens = maxOutputTokens.coerceAtLeast(MIN_OUTPUT_TOKEN_BUDGET)
            return this
                ?.copy(maxOutputTokens = this.maxOutputTokens?.coerceAtMost(clampedMaxOutputTokens) ?: clampedMaxOutputTokens)
                ?: GeminiGenerationConfig(maxOutputTokens = clampedMaxOutputTokens)
        }

        private fun String.targetsGemini35Flash(): Boolean = contains("/models/$GEMINI_MODEL_NAME:")

        private fun String.withModelName(
            modelName: String?,
            requestId: Long,
        ): String {
            val normalizedModelName = modelName?.trim()?.takeIf { it.isNotEmpty() } ?: return this
            if (!MODEL_NAME_PATTERN.matches(normalizedModelName)) {
                Timber
                    .tag(LOG_TAG)
                    .w(
                        "Gemini request #%d ignores invalid model name=%s and uses configured endpoint.",
                        requestId,
                        normalizedModelName,
                    )
                return this
            }
            if (!MODEL_PATH_REGEX.containsMatchIn(this)) {
                Timber
                    .tag(LOG_TAG)
                    .w(
                        "Gemini request #%d cannot swap model name because endpoint has no /models/{model}: segment.",
                        requestId,
                    )
                return this
            }
            return replace(MODEL_PATH_REGEX, "/models/$normalizedModelName:")
        }

        private fun String.modelNameOrUnknown(): String =
            MODEL_PATH_REGEX
                .find(this)
                ?.groupValues
                ?.getOrNull(1)
                ?: "unknown"

        private fun String.toCountTokensEndpoint(): String? =
            takeIf { it.contains(GENERATE_CONTENT_SUFFIX) }
                ?.replace(GENERATE_CONTENT_SUFFIX, COUNT_TOKENS_SUFFIX)

        private fun parseResponse(
            responseBody: String,
            currentRequestTokens: Int?,
            slidingWindowApplied: Boolean,
        ): GeminiResult<String> {
            if (responseBody.isBlank()) {
                Timber.tag(LOG_TAG).w("Gemini response body is blank.")
                return GeminiResult.Failure(GeminiNetworkError.EmptyResponse)
            }

            val decoded = json.decodeFromString<GenerateContentResponse>(responseBody)
            val text = decoded.textOrNull()
            return if (text == null) {
                Timber.tag(LOG_TAG).w("Gemini response has no non-blank text parts.")
                GeminiResult.Failure(GeminiNetworkError.EmptyResponse)
            } else {
                val tokenUsage =
                    (
                        decoded.usageMetadata?.toTokenUsage(currentRequestTokens)
                            ?: currentRequestTokens?.let { GeminiTokenUsage(currentRequestTokens = it) }
                    )?.copy(slidingWindowApplied = slidingWindowApplied)
                Timber
                    .tag(LOG_TAG)
                    .d(
                        "Gemini response token usage current=%s history=%s response=%s total=%s slidingWindow=%s",
                        tokenUsage?.currentRequestTokens?.toString() ?: "unknown",
                        tokenUsage?.conversationHistoryTokens?.toString() ?: "unknown",
                        tokenUsage?.modelResponseTokens?.toString() ?: "unknown",
                        tokenUsage?.totalTokens?.toString() ?: "unknown",
                        tokenUsage?.slidingWindowApplied?.toString() ?: "unknown",
                    )
                GeminiResult.Success(text, tokenUsage = tokenUsage)
            }
        }

        private fun GeminiNetworkError.shouldRetry(attempt: Int): Boolean {
            val hasAttemptsLeft = attempt < MAX_ATTEMPTS - 1
            return hasAttemptsLeft && this is GeminiNetworkError.Http && statusCode in RETRYABLE_HTTP_STATUS_CODES
        }

        private fun logPayload(
            requestId: Long,
            label: String,
            payload: String,
        ) {
            if (payload.isBlank()) {
                Timber.tag(LOG_TAG).d("Gemini request #%d %s=<blank>", requestId, label)
                return
            }

            val chunks = payload.chunked(LOG_CHUNK_SIZE)
            chunks.forEachIndexed { index, chunk ->
                Timber
                    .tag(LOG_TAG)
                    .d(
                        "Gemini request #%d %s chunk %d/%d: %s",
                        requestId,
                        label,
                        index + 1,
                        chunks.size,
                        chunk,
                    )
            }
        }

        private companion object {
            const val LOG_TAG = "GeminiNetwork"
            const val MAX_ATTEMPTS = 3
            const val MIN_OUTPUT_TOKEN_BUDGET = 1
            const val LOG_CHUNK_SIZE = 3500
            val REQUEST_IDS = AtomicLong(0L)
            val RETRY_DELAYS_MS = longArrayOf(300L, 900L)
            val RETRYABLE_HTTP_STATUS_CODES = setOf(429, 500, 502, 503, 504)
            val JSON_MEDIA_TYPE = "application/json".toMediaType()
            const val GENERATE_CONTENT_SUFFIX = ":generateContent"
            const val COUNT_TOKENS_SUFFIX = ":countTokens"
            val MODEL_NAME_PATTERN = Regex("[A-Za-z0-9._-]+")
            val MODEL_PATH_REGEX = Regex("/models/([^/:]+):")
        }

        private data class SlidingTokenWindow(
            val messages: List<AgentMessage>,
            val promptTokens: Int?,
            val isTrimmed: Boolean,
        )
    }
