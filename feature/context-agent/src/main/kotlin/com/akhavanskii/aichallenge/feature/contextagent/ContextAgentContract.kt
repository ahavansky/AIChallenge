package com.akhavanskii.aichallenge.feature.contextagent

import com.akhavanskii.aichallenge.core.mvvm.UiEvent
import com.akhavanskii.aichallenge.core.mvvm.UiState
import com.akhavanskii.aichallenge.core.network.GeminiTokenUsage

data class ContextAgentUiState(
    val input: String = "",
    val messages: List<ContextAgentMessage> = emptyList(),
    val selectedModel: ContextAgentModelOption = ContextAgentModelOption.GEMINI_3_5_FLASH,
    val contextState: ContextCompressionState = ContextCompressionState(),
    val comparison: ContextQualityComparison? = null,
) : UiState {
    val isLoading: Boolean
        get() = messages.any { it.isLoading }

    val canSend: Boolean
        get() = input.isNotBlank() && !isLoading

    val canClear: Boolean
        get() = (messages.isNotEmpty() || contextState.summary.isNotBlank() || comparison != null) && !isLoading

    val canStop: Boolean
        get() = isLoading

    val canChangeModel: Boolean
        get() = messages.none { it.role == ContextAgentRole.USER } && !isLoading

    val latestTokenUsage: GeminiTokenUsage?
        get() =
            messages
                .asReversed()
                .firstOrNull { message ->
                    message.role == ContextAgentRole.MODEL &&
                        !message.isLoading &&
                        !message.isError &&
                        message.tokenUsage?.hasAnyCount == true
                }?.tokenUsage
}

data class ContextAgentMessage(
    val role: ContextAgentRole,
    val text: String,
    val isLoading: Boolean = false,
    val isError: Boolean = false,
    val tokenUsage: GeminiTokenUsage? = null,
    val includeInContext: Boolean = true,
)

enum class ContextAgentRole {
    USER,
    MODEL,
}

data class ContextCompressionState(
    val summary: String = "",
    val summarizedMessageCount: Int = 0,
    val latestStats: ContextCompressionStats? = null,
)

data class ContextCompressionStats(
    val fullPromptTokens: Int? = null,
    val compressedPromptTokens: Int? = null,
    val savedPromptTokens: Int? = null,
    val savedPromptPercent: Int? = null,
    val summarizedMessageCount: Int = 0,
    val rawMessageCount: Int = 0,
    val requestMessageCount: Int = 0,
    val recentMessageLimit: Int = CONTEXT_AGENT_RECENT_MESSAGE_COUNT,
    val summaryBatchSize: Int = CONTEXT_AGENT_SUMMARY_BATCH_SIZE,
)

data class ContextQualityComparison(
    val fullHistoryAnswer: String,
    val compressedHistoryAnswer: String,
    val evaluation: String,
)

enum class ContextAgentModelOption(
    val modelName: String,
    val title: String,
    val description: String,
    val inputTokenLimit: Int,
    val outputTokenLimit: Int,
) {
    GEMINI_3_5_FLASH(
        modelName = "gemini-3.5-flash",
        title = "Gemini 3.5 Flash",
        description = "Default Gemini model for compressed chat.",
        inputTokenLimit = GEMINI_FLASH_INPUT_TOKEN_LIMIT,
        outputTokenLimit = GEMINI_FLASH_OUTPUT_TOKEN_LIMIT,
    ),
    GEMINI_2_5_FLASH(
        modelName = "gemini-2.5-flash",
        title = "Gemini 2.5 Flash",
        description = "Balanced Gemini model for everyday reasoning.",
        inputTokenLimit = GEMINI_FLASH_INPUT_TOKEN_LIMIT,
        outputTokenLimit = GEMINI_FLASH_OUTPUT_TOKEN_LIMIT,
    ),
    GEMINI_2_5_FLASH_LITE(
        modelName = "gemini-2.5-flash-lite",
        title = "Gemini 2.5 Flash-Lite",
        description = "Lower-latency Gemini model for simpler turns.",
        inputTokenLimit = GEMINI_FLASH_INPUT_TOKEN_LIMIT,
        outputTokenLimit = GEMINI_FLASH_OUTPUT_TOKEN_LIMIT,
    ),
    GEMMA_4_26B_A4B_IT(
        modelName = "gemma-4-26b-a4b-it",
        title = "Gemma 4 26B A4B IT",
        description = "Free Gemma 4 MoE model for faster open-model reasoning.",
        inputTokenLimit = GEMMA_4_CONTEXT_TOKEN_LIMIT,
        outputTokenLimit = GEMMA_4_OUTPUT_TOKEN_LIMIT,
    ),
    GEMMA_4_31B_IT(
        modelName = "gemma-4-31b-it",
        title = "Gemma 4 31B IT",
        description = "Free Gemma 4 dense model for stronger open-model reasoning.",
        inputTokenLimit = GEMMA_4_CONTEXT_TOKEN_LIMIT,
        outputTokenLimit = GEMMA_4_OUTPUT_TOKEN_LIMIT,
    ),
    ;

    val totalTokenLimit: Int
        get() = inputTokenLimit + outputTokenLimit
}

sealed interface ContextAgentAction : UiEvent {
    data class InputChanged(
        val input: String,
    ) : ContextAgentAction

    data class ModelChanged(
        val model: ContextAgentModelOption,
    ) : ContextAgentAction

    data object Submit : ContextAgentAction

    data object RunComparison : ContextAgentAction

    data object Clear : ContextAgentAction

    data object Stop : ContextAgentAction
}

const val CONTEXT_AGENT_RECENT_MESSAGE_COUNT = 8
const val CONTEXT_AGENT_SUMMARY_BATCH_SIZE = 10

private const val GEMINI_FLASH_INPUT_TOKEN_LIMIT = 1_048_576
private const val GEMINI_FLASH_OUTPUT_TOKEN_LIMIT = 65_536
private const val GEMMA_4_CONTEXT_TOKEN_LIMIT = 262_144
private const val GEMMA_4_OUTPUT_TOKEN_LIMIT = 128_000
