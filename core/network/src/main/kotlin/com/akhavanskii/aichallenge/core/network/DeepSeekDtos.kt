package com.akhavanskii.aichallenge.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeepSeekChatCompletionRequest(
    val model: String,
    val messages: List<DeepSeekMessageDto>,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p")
    val topP: Double? = null,
    val stop: List<String>? = null,
    val stream: Boolean = false,
) {
    companion object {
        fun fromMessages(
            modelName: String,
            messages: List<AgentMessage>,
            systemInstruction: String?,
            generationConfig: GeminiGenerationConfig?,
        ): DeepSeekChatCompletionRequest =
            DeepSeekChatCompletionRequest(
                model = modelName,
                messages =
                    buildList {
                        systemInstruction
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { instruction ->
                                add(DeepSeekMessageDto(role = "system", content = instruction))
                            }
                        messages.forEach { message ->
                            add(
                                DeepSeekMessageDto(
                                    role =
                                        when (message) {
                                            is AgentMessage.User -> "user"
                                            is AgentMessage.Model -> "assistant"
                                        },
                                    content = message.text,
                                ),
                            )
                        }
                    },
                maxTokens = generationConfig?.maxOutputTokens,
                temperature = generationConfig?.temperature,
                topP = generationConfig?.topP,
                stop =
                    generationConfig
                        ?.stopSequences
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?.takeIf { it.isNotEmpty() },
            )
    }
}

@Serializable
data class DeepSeekMessageDto(
    val role: String,
    val content: String,
)

@Serializable
data class DeepSeekChatCompletionResponse(
    val choices: List<DeepSeekChoiceDto> = emptyList(),
    val usage: DeepSeekUsageDto? = null,
)

@Serializable
data class DeepSeekChoiceDto(
    val message: DeepSeekMessageDto? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
data class DeepSeekUsageDto(
    @SerialName("prompt_tokens")
    val promptTokens: Int? = null,
    @SerialName("completion_tokens")
    val completionTokens: Int? = null,
    @SerialName("total_tokens")
    val totalTokens: Int? = null,
)

fun DeepSeekChatCompletionResponse.textOrNull(): String? =
    choices
        .asSequence()
        .mapNotNull { choice -> choice.message?.content?.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(separator = "\n\n")
        .takeIf { it.isNotEmpty() }

fun DeepSeekUsageDto.toGeminiTokenUsage(): GeminiTokenUsage =
    GeminiTokenUsage(
        conversationHistoryTokens = promptTokens,
        modelResponseTokens = completionTokens,
        totalTokens = totalTokens,
    )
