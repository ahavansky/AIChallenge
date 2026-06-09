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
    suspend fun sendMessage(
        prompt: String,
        generationConfig: GeminiGenerationConfig? = null,
        modelName: String? = null,
    ): AgentResult<String> =
        sendMessage(
            messages = listOf(AgentMessage.User(prompt)),
            generationConfig = generationConfig,
            modelName = modelName,
        )

    suspend fun sendMessage(
        messages: List<AgentMessage>,
        generationConfig: GeminiGenerationConfig? = null,
        modelName: String? = null,
    ): AgentResult<String>
}
