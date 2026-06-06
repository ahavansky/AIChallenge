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

class RestHuggingFaceTextClient
    @Inject
    constructor(
        @param:Named(HUGGINGFACE_API_KEY_NAME) private val apiKey: String,
        @param:Named(HUGGINGFACE_ENDPOINT_NAME) private val endpoint: String,
        @param:HuggingFaceCallFactory private val callFactory: Call.Factory,
        private val json: Json,
        @param:NetworkDispatcher private val dispatcher: CoroutineDispatcher,
    ) : HuggingFaceTextClient {
        override suspend fun generate(
            prompt: String,
            modelName: String,
        ): HuggingFaceResult<HuggingFaceTextResponse> {
            val normalizedPrompt = prompt.trim()
            val normalizedModelName = modelName.trim()
            if (normalizedPrompt.isBlank()) {
                Timber.tag(LOG_TAG).w("HuggingFace request skipped: empty prompt.")
                return HuggingFaceResult.Failure(HuggingFaceNetworkError.EmptyPrompt)
            }
            if (normalizedModelName.isBlank()) {
                Timber.tag(LOG_TAG).w("HuggingFace request skipped: empty model id.")
                return HuggingFaceResult.Failure(HuggingFaceNetworkError.EmptyModel)
            }
            if (apiKey.isBlank()) {
                Timber.tag(LOG_TAG).w("HuggingFace request skipped: missing token.")
                return HuggingFaceResult.Failure(HuggingFaceNetworkError.MissingApiKey)
            }

            val requestId = REQUEST_IDS.incrementAndGet()
            return withContext(dispatcher) {
                runCatching {
                    executeRequest(
                        requestId = requestId,
                        prompt = normalizedPrompt,
                        modelName = normalizedModelName,
                    )
                }.getOrElse { throwable ->
                    Timber
                        .tag(LOG_TAG)
                        .e(
                            throwable,
                            "HuggingFace request #%d failed before a parsed result. model=%s promptChars=%d",
                            requestId,
                            normalizedModelName,
                            normalizedPrompt.length,
                        )
                    when (throwable) {
                        is CancellationException -> throw throwable
                        is IOException -> HuggingFaceResult.Failure(HuggingFaceNetworkError.Network(throwable.message))
                        is SerializationException -> HuggingFaceResult.Failure(HuggingFaceNetworkError.Serialization(throwable.message))
                        else -> HuggingFaceResult.Failure(HuggingFaceNetworkError.Network(throwable.message))
                    }
                }
            }
        }

        private suspend fun executeRequest(
            requestId: Long,
            prompt: String,
            modelName: String,
        ): HuggingFaceResult<HuggingFaceTextResponse> {
            val requestJson =
                json.encodeToString(
                    HuggingFaceChatCompletionRequest.fromPrompt(
                        modelName = modelName,
                        prompt = prompt,
                        maxTokens = MAX_OUTPUT_TOKENS,
                        reasoningEffort = REASONING_EFFORT,
                    ),
                )

            Timber
                .tag(LOG_TAG)
                .d(
                    "HuggingFace request #%d prepared. model=%s endpoint=%s promptChars=%d requestChars=%d",
                    requestId,
                    modelName,
                    endpoint,
                    prompt.length,
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
                        "HuggingFace request #%d attempt %d/%d started. model=%s",
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
                                "HuggingFace request #%d attempt %d/%d finished. status=%d success=%s responseChars=%d",
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
                                    "HuggingFace request #%d failed with HTTP %d. model=%s",
                                    requestId,
                                    response.code,
                                    modelName,
                                )
                            HuggingFaceResult.Failure(
                                HuggingFaceNetworkError.Http(
                                    statusCode = response.code,
                                    body = responseBody.takeIf { it.isNotBlank() },
                                ),
                            )
                        } else {
                            parseResponse(
                                responseBody = responseBody,
                                attemptCount = attempt + 1,
                            )
                        }
                    }

                if (result !is HuggingFaceResult.Failure || !result.error.shouldRetry(attempt)) {
                    return result
                }

                val retryableError = result.error as HuggingFaceNetworkError.Http
                Timber
                    .tag(LOG_TAG)
                    .w(
                        "HuggingFace request #%d scheduling retry after retryable HTTP %d.",
                        requestId,
                        retryableError.statusCode,
                    )
                delay(RETRY_DELAYS_MS[attempt])
            }

            error("Retry loop should always return before exhausting attempts.")
        }

        private fun parseResponse(
            responseBody: String,
            attemptCount: Int,
        ): HuggingFaceResult<HuggingFaceTextResponse> {
            if (responseBody.isBlank()) {
                Timber.tag(LOG_TAG).w("HuggingFace response body is blank.")
                return HuggingFaceResult.Failure(HuggingFaceNetworkError.EmptyResponse)
            }

            val decoded = json.decodeFromString<HuggingFaceChatCompletionResponse>(responseBody)
            decoded.logSummary { summary ->
                Timber.tag(LOG_TAG).d("HuggingFace response summary: attempts=%d %s", attemptCount, summary)
            }
            val response = decoded.toTextResponseOrNull(attemptCount = attemptCount)
            return if (response == null) {
                if (decoded.hasReasoningOnlyLengthResponse()) {
                    Timber
                        .tag(LOG_TAG)
                        .w("HuggingFace response stopped by token limit before visible assistant content.")
                    HuggingFaceResult.Failure(HuggingFaceNetworkError.ReasoningOnlyResponse)
                } else {
                    Timber.tag(LOG_TAG).w("HuggingFace response has no non-blank assistant message.")
                    HuggingFaceResult.Failure(HuggingFaceNetworkError.EmptyResponse)
                }
            } else {
                HuggingFaceResult.Success(response)
            }
        }

        private fun HuggingFaceNetworkError.shouldRetry(attempt: Int): Boolean {
            val hasAttemptsLeft = attempt < MAX_ATTEMPTS - 1
            return hasAttemptsLeft && this is HuggingFaceNetworkError.Http && statusCode in RETRYABLE_HTTP_STATUS_CODES
        }

        private fun logPayload(
            requestId: Long,
            label: String,
            payload: String,
        ) {
            if (payload.isBlank()) {
                Timber.tag(LOG_TAG).d("HuggingFace request #%d %s=<blank>", requestId, label)
                return
            }

            val chunks = payload.chunked(LOG_CHUNK_SIZE)
            chunks.forEachIndexed { index, chunk ->
                Timber
                    .tag(LOG_TAG)
                    .d(
                        "HuggingFace request #%d %s chunk %d/%d: %s",
                        requestId,
                        label,
                        index + 1,
                        chunks.size,
                        chunk,
                    )
            }
        }

        private companion object {
            const val LOG_TAG = "HuggingFaceNetwork"
            const val MAX_ATTEMPTS = 3
            const val MAX_OUTPUT_TOKENS = 1024
            const val REASONING_EFFORT = "low"
            const val LOG_CHUNK_SIZE = 3500
            val REQUEST_IDS = AtomicLong(0L)
            val RETRY_DELAYS_MS = longArrayOf(300L, 900L)
            val RETRYABLE_HTTP_STATUS_CODES = setOf(429, 500, 502, 503, 504)
            val JSON_MEDIA_TYPE = "application/json".toMediaType()
        }
    }
