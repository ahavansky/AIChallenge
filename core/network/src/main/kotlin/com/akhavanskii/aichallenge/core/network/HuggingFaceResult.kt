package com.akhavanskii.aichallenge.core.network

sealed interface HuggingFaceResult<out T> {
    data class Success<T>(
        val value: T,
    ) : HuggingFaceResult<T>

    data class Failure(
        val error: HuggingFaceNetworkError,
    ) : HuggingFaceResult<Nothing>
}

sealed interface HuggingFaceNetworkError {
    val userMessage: String

    data object EmptyPrompt : HuggingFaceNetworkError {
        override val userMessage: String = "Enter a prompt before sending."
    }

    data object EmptyModel : HuggingFaceNetworkError {
        override val userMessage: String = "HuggingFace model id is empty."
    }

    data object MissingApiKey : HuggingFaceNetworkError {
        override val userMessage: String =
            "HuggingFace token is missing. Add HUGGINGFACE_API_KEY or HF_TOKEN to local.properties or your environment."
    }

    data class Http(
        val statusCode: Int,
        val body: String?,
    ) : HuggingFaceNetworkError {
        override val userMessage: String =
            when (statusCode) {
                400 ->
                    if (body.orEmpty().contains("model_not_supported")) {
                        "HuggingFace model is not supported by any enabled Inference Provider. Enable the provider in HuggingFace settings or use a provider-qualified model id."
                    } else {
                        "HuggingFace request failed with HTTP 400."
                    }
                401, 403 -> "HuggingFace token was rejected with HTTP $statusCode."
                404 -> "HuggingFace model or provider route was not found."
                429 -> "HuggingFace rate limit or free credits were exhausted."
                503 -> "HuggingFace provider is temporarily unavailable. Try again in a moment."
                else -> "HuggingFace request failed with HTTP $statusCode."
            }
    }

    data class Network(
        val cause: String?,
    ) : HuggingFaceNetworkError {
        override val userMessage: String = "Network error while contacting HuggingFace."
    }

    data class Serialization(
        val cause: String?,
    ) : HuggingFaceNetworkError {
        override val userMessage: String = "HuggingFace response could not be parsed."
    }

    data object EmptyResponse : HuggingFaceNetworkError {
        override val userMessage: String = "HuggingFace returned an empty response."
    }

    data object ReasoningOnlyResponse : HuggingFaceNetworkError {
        override val userMessage: String =
            "HuggingFace spent the output token budget on reasoning and returned no visible answer. Try a shorter task or run again."
    }
}

data class HuggingFaceTextResponse(
    val text: String,
    val tokenUsage: HuggingFaceTokenUsage?,
    val metadata: HuggingFaceResponseMetadata = HuggingFaceResponseMetadata(),
)

data class HuggingFaceTokenUsage(
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val reasoningTokens: Int? = null,
) {
    val visibleOutputTokens: Int?
        get() {
            val completion = completionTokens ?: return null
            val reasoning = reasoningTokens ?: return completion
            return (completion - reasoning).coerceAtLeast(0)
        }
}

data class HuggingFaceResponseMetadata(
    val attemptCount: Int = 1,
    val finishReasons: List<String> = emptyList(),
)
