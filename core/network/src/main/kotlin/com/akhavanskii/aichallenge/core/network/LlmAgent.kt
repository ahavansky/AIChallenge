package com.akhavanskii.aichallenge.core.network

typealias AgentResult<T> = GeminiResult<T>

sealed interface AgentMessage {
    val text: String

    data class User(
        override val text: String,
    ) : AgentMessage

    data class Model(
        override val text: String,
    ) : AgentMessage
}

interface LlmAgent {
    suspend fun countTokens(
        messages: List<AgentMessage>,
        modelName: String? = null,
    ): AgentResult<Int> = GeminiResult.Failure(GeminiNetworkError.EmptyResponse)

    suspend fun sendMessage(
        prompt: String,
        generationConfig: GeminiGenerationConfig? = null,
        modelName: String? = null,
        totalTokenLimit: Int? = null,
    ): AgentResult<String> =
        sendMessage(
            messages = listOf(AgentMessage.User(prompt)),
            generationConfig = generationConfig,
            modelName = modelName,
            totalTokenLimit = totalTokenLimit,
        )

    suspend fun sendMessage(
        messages: List<AgentMessage>,
        generationConfig: GeminiGenerationConfig? = null,
        modelName: String? = null,
        totalTokenLimit: Int? = null,
    ): AgentResult<String>
}
