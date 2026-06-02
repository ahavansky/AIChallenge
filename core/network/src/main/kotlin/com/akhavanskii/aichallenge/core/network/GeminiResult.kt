package com.akhavanskii.aichallenge.core.network

sealed interface GeminiResult<out T> {
    data class Success<T>(
        val value: T,
    ) : GeminiResult<T>

    data class Failure(
        val error: GeminiNetworkError,
    ) : GeminiResult<Nothing>
}

sealed interface GeminiNetworkError {
    val userMessage: String

    data object EmptyPrompt : GeminiNetworkError {
        override val userMessage: String = "Enter a prompt before sending."
    }

    data object MissingApiKey : GeminiNetworkError {
        override val userMessage: String = "Gemini API key is missing. Add GEMINI_API_KEY to local.properties or your environment."
    }

    data class Http(
        val statusCode: Int,
        val body: String?,
    ) : GeminiNetworkError {
        override val userMessage: String =
            when (statusCode) {
                503 -> "Gemini is temporarily unavailable. Try again in a moment."
                else -> "Gemini request failed with HTTP $statusCode."
            }
    }

    data class Network(
        val cause: String?,
    ) : GeminiNetworkError {
        override val userMessage: String = "Network error while contacting Gemini."
    }

    data class Serialization(
        val cause: String?,
    ) : GeminiNetworkError {
        override val userMessage: String = "Gemini response could not be parsed."
    }

    data object EmptyResponse : GeminiNetworkError {
        override val userMessage: String = "Gemini returned an empty response."
    }
}
