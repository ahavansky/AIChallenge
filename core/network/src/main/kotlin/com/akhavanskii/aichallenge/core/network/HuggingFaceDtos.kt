package com.akhavanskii.aichallenge.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HuggingFaceChatCompletionRequest(
    val model: String,
    val messages: List<HuggingFaceMessageDto>,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    @SerialName("reasoning_effort")
    val reasoningEffort: String? = null,
    val stream: Boolean = false,
) {
    companion object {
        fun fromPrompt(
            modelName: String,
            prompt: String,
            maxTokens: Int,
            reasoningEffort: String,
        ): HuggingFaceChatCompletionRequest =
            HuggingFaceChatCompletionRequest(
                model = modelName,
                messages =
                    listOf(
                        HuggingFaceMessageDto(
                            role = "system",
                            content = "Return the visible final answer in message.content. Keep the answer concise.",
                        ),
                        HuggingFaceMessageDto(
                            role = "user",
                            content = prompt,
                        ),
                    ),
                maxTokens = maxTokens,
                reasoningEffort = reasoningEffort,
            )
    }
}

@Serializable
data class HuggingFaceMessageDto(
    val role: String,
    val content: String? = null,
    val reasoning: String? = null,
)

@Serializable
data class HuggingFaceChatCompletionResponse(
    val choices: List<HuggingFaceChoiceDto> = emptyList(),
    val usage: HuggingFaceUsageDto? = null,
)

@Serializable
data class HuggingFaceChoiceDto(
    val message: HuggingFaceMessageDto? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
data class HuggingFaceUsageDto(
    @SerialName("prompt_tokens")
    val promptTokens: Int? = null,
    @SerialName("completion_tokens")
    val completionTokens: Int? = null,
    @SerialName("total_tokens")
    val totalTokens: Int? = null,
    @SerialName("completion_tokens_details")
    val completionTokensDetails: HuggingFaceCompletionTokensDetailsDto? = null,
)

@Serializable
data class HuggingFaceCompletionTokensDetailsDto(
    @SerialName("reasoning_tokens")
    val reasoningTokens: Int? = null,
)

fun HuggingFaceChatCompletionResponse.toTextResponseOrNull(attemptCount: Int): HuggingFaceTextResponse? {
    val text =
        choices
            .asSequence()
            .mapNotNull { choice -> choice.message?.content?.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(separator = "\n\n")
            .takeIf { it.isNotEmpty() }

    return text?.let {
        HuggingFaceTextResponse(
            text = it,
            tokenUsage = usage?.toTokenUsage(),
            metadata =
                HuggingFaceResponseMetadata(
                    attemptCount = attemptCount,
                    finishReasons = choices.mapNotNull { choice -> choice.finishReason?.takeIf { reason -> reason.isNotBlank() } },
                ),
        )
    }
}

fun HuggingFaceChatCompletionResponse.hasReasoningOnlyLengthResponse(): Boolean =
    choices.any { choice ->
        choice.finishReason == "length" &&
            choice.message?.content?.isBlank() != false &&
            choice.message?.reasoning?.isNotBlank() == true
    }

fun HuggingFaceChatCompletionResponse.logSummary(logLine: (String) -> Unit) {
    choices.forEachIndexed { index, choice ->
        val message = choice.message
        logLine(
            "choice=$index finishReason=${choice.finishReason ?: "unknown"} " +
                "contentChars=${message?.content?.length ?: 0} reasoningChars=${message?.reasoning?.length ?: 0}",
        )
    }
}

private fun HuggingFaceUsageDto.toTokenUsage(): HuggingFaceTokenUsage =
    HuggingFaceTokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        reasoningTokens = completionTokensDetails?.reasoningTokens,
    )
