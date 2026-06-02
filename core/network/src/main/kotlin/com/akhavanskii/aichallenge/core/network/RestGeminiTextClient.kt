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
import java.io.IOException
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
        override suspend fun generate(prompt: String): GeminiResult<String> {
            val normalizedPrompt = prompt.trim()
            if (normalizedPrompt.isBlank()) {
                return GeminiResult.Failure(GeminiNetworkError.EmptyPrompt)
            }
            if (apiKey.isBlank()) {
                return GeminiResult.Failure(GeminiNetworkError.MissingApiKey)
            }

            return withContext(dispatcher) {
                runCatching { executeRequest(normalizedPrompt) }
                    .getOrElse { throwable ->
                        when (throwable) {
                            is CancellationException -> throw throwable
                            is IOException -> GeminiResult.Failure(GeminiNetworkError.Network(throwable.message))
                            is SerializationException -> GeminiResult.Failure(GeminiNetworkError.Serialization(throwable.message))
                            else -> GeminiResult.Failure(GeminiNetworkError.Network(throwable.message))
                        }
                    }
            }
        }

        private suspend fun executeRequest(prompt: String): GeminiResult<String> {
            val requestJson = json.encodeToString(GenerateContentRequest.fromPrompt(prompt))
            val request =
                Request
                    .Builder()
                    .url(endpoint)
                    .header("x-goog-api-key", apiKey)
                    .header("Content-Type", JSON_MEDIA_TYPE.toString())
                    .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

            repeat(MAX_ATTEMPTS) { attempt ->
                val result =
                    callFactory.newCall(request).execute().use { response ->
                        val responseBody = response.body.string()
                        if (!response.isSuccessful) {
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

                delay(RETRY_DELAYS_MS[attempt])
            }

            error("Retry loop should always return before exhausting attempts.")
        }

        private fun parseResponse(responseBody: String): GeminiResult<String> {
            if (responseBody.isBlank()) {
                return GeminiResult.Failure(GeminiNetworkError.EmptyResponse)
            }

            val decoded = json.decodeFromString<GenerateContentResponse>(responseBody)
            val text = decoded.firstTextOrNull()
            return if (text == null) {
                GeminiResult.Failure(GeminiNetworkError.EmptyResponse)
            } else {
                GeminiResult.Success(text)
            }
        }

        private fun GeminiNetworkError.shouldRetry(attempt: Int): Boolean {
            val hasAttemptsLeft = attempt < MAX_ATTEMPTS - 1
            return hasAttemptsLeft && this is GeminiNetworkError.Http && statusCode == HTTP_UNAVAILABLE
        }

        private companion object {
            const val HTTP_UNAVAILABLE = 503
            const val MAX_ATTEMPTS = 3
            val RETRY_DELAYS_MS = longArrayOf(300L, 900L)
            val JSON_MEDIA_TYPE = "application/json".toMediaType()
        }
    }
