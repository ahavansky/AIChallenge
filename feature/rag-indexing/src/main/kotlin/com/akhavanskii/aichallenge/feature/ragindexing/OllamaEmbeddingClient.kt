package com.akhavanskii.aichallenge.feature.ragindexing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class OllamaEmbeddingClient(
    private val callFactory: Call.Factory = defaultCallFactory(),
    private val json: Json = defaultJson(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EmbeddingClient {
    override suspend fun embed(
        endpoint: String,
        model: String,
        inputs: List<String>,
    ): EmbeddingResult {
        if (inputs.isEmpty()) {
            return EmbeddingResult.Success(emptyList())
        }

        val normalizedEndpoint = endpoint.trim()
        val normalizedModel = model.trim()
        if (normalizedEndpoint.isBlank() || normalizedModel.isBlank()) {
            return EmbeddingResult.Failure(EmbeddingError.BadResponse)
        }

        return withContext(dispatcher) {
            runCatching {
                executeRequest(
                    endpoint = normalizedEndpoint,
                    model = normalizedModel,
                    inputs = inputs,
                )
            }.getOrElse { throwable ->
                when (throwable) {
                    is CancellationException -> throw throwable
                    is SocketTimeoutException -> EmbeddingResult.Failure(EmbeddingError.Timeout)
                    is InterruptedIOException -> EmbeddingResult.Failure(EmbeddingError.Timeout)
                    is ConnectException -> EmbeddingResult.Failure(EmbeddingError.Unreachable)
                    is UnknownHostException -> EmbeddingResult.Failure(EmbeddingError.Unreachable)
                    is IOException -> EmbeddingResult.Failure(EmbeddingError.Unreachable)
                    is SerializationException -> EmbeddingResult.Failure(EmbeddingError.BadResponse)
                    else -> EmbeddingResult.Failure(EmbeddingError.BadResponse)
                }
            }
        }
    }

    private fun executeRequest(
        endpoint: String,
        model: String,
        inputs: List<String>,
    ): EmbeddingResult {
        val body =
            json.encodeToString(
                OllamaEmbedRequest(
                    model = model,
                    input = inputs,
                ),
            )

        val request =
            Request
                .Builder()
                .url(endpoint)
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

        return callFactory.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
                return EmbeddingResult.Failure(
                    EmbeddingError.Http(
                        statusCode = response.code,
                        bodySnippet = responseBody.take(MAX_BODY_SNIPPET).takeIf { it.isNotBlank() },
                    ),
                )
            }

            parseOllamaEmbeddingResponse(
                responseBody = responseBody,
                expectedInputCount = inputs.size,
            )
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "http://10.0.2.2:11434/api/embed"
        const val DEFAULT_MODEL = "nomic-embed-text"

        private const val MAX_BODY_SNIPPET = 500
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private fun defaultJson(): Json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }

        private fun defaultCallFactory(): Call.Factory =
            OkHttpClient
                .Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build()
    }
}

internal fun parseOllamaEmbeddingResponse(
    responseBody: String,
    expectedInputCount: Int,
    json: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
): EmbeddingResult {
    if (responseBody.isBlank()) {
        return EmbeddingResult.Failure(EmbeddingError.BadResponse)
    }

    val decoded = json.decodeFromString<OllamaEmbedResponse>(responseBody)
    val validationError = validateEmbeddingVectors(decoded.embeddings, expectedInputCount)
    return if (validationError == null) {
        EmbeddingResult.Success(decoded.embeddings)
    } else {
        EmbeddingResult.Failure(validationError)
    }
}

@Serializable
private data class OllamaEmbedRequest(
    val model: String,
    val input: List<String>,
)

@Serializable
private data class OllamaEmbedResponse(
    @SerialName("embeddings")
    val embeddings: List<List<Double>> = emptyList(),
)
