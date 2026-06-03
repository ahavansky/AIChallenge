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

class RestGeminiTextClient
    @Inject
    constructor(
        @param:Named(GEMINI_API_KEY_NAME) private val apiKey: String,
        @param:Named(GEMINI_ENDPOINT_NAME) private val endpoint: String,
        private val callFactory: Call.Factory,
        private val json: Json,
        @param:NetworkDispatcher private val dispatcher: CoroutineDispatcher,
    ) : GeminiTextClient {
        override suspend fun generate(
            prompt: String,
            generationConfig: GeminiGenerationConfig?,
            modelName: String?,
        ): GeminiResult<String> {
            val normalizedPrompt = prompt.trim()
            if (normalizedPrompt.isBlank()) {
                Timber.tag(LOG_TAG).w("Gemini request skipped: empty prompt.")
                return GeminiResult.Failure(GeminiNetworkError.EmptyPrompt)
            }
            if (apiKey.isBlank()) {
                Timber.tag(LOG_TAG).w("Gemini request skipped: missing API key.")
                return GeminiResult.Failure(GeminiNetworkError.MissingApiKey)
            }

            val requestId = REQUEST_IDS.incrementAndGet()
            return withContext(dispatcher) {
                runCatching { executeRequest(requestId, normalizedPrompt, generationConfig, modelName) }
                    .getOrElse { throwable ->
                        Timber
                            .tag(LOG_TAG)
                            .e(
                                throwable,
                                "Gemini request #%d failed before a parsed result. configured=%s promptChars=%d",
                                requestId,
                                generationConfig != null,
                                normalizedPrompt.length,
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
            prompt: String,
            generationConfig: GeminiGenerationConfig?,
            modelName: String?,
        ): GeminiResult<String> {
            val requestEndpoint = endpoint.withModelName(modelName, requestId)
            val effectiveGenerationConfig = generationConfig?.withoutUnsupportedFields(requestId, requestEndpoint)
            val requestJson =
                json.encodeToString(
                    GenerateContentRequest.fromPrompt(
                        prompt = prompt,
                        generationConfig = effectiveGenerationConfig?.toDto(json),
                    ),
                )
            val isConfigured = generationConfig != null
            Timber
                .tag(LOG_TAG)
                .d(
                    "Gemini request #%d prepared. configured=%s model=%s endpoint=%s promptChars=%d requestChars=%d",
                    requestId,
                    isConfigured,
                    requestEndpoint.modelNameOrUnknown(),
                    requestEndpoint,
                    prompt.length,
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
                            parseResponse(responseBody)
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

        private fun parseResponse(responseBody: String): GeminiResult<String> {
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
                GeminiResult.Success(text)
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
            const val LOG_CHUNK_SIZE = 3500
            val REQUEST_IDS = AtomicLong(0L)
            val RETRY_DELAYS_MS = longArrayOf(300L, 900L)
            val RETRYABLE_HTTP_STATUS_CODES = setOf(429, 500, 502, 503, 504)
            val JSON_MEDIA_TYPE = "application/json".toMediaType()
            val MODEL_NAME_PATTERN = Regex("[A-Za-z0-9._-]+")
            val MODEL_PATH_REGEX = Regex("/models/([^/:]+):")
        }
    }
