package com.akhavanskii.aichallenge.feature.agentchat

import com.akhavanskii.aichallenge.core.mvvm.UiEvent
import com.akhavanskii.aichallenge.core.mvvm.UiState
import com.akhavanskii.aichallenge.core.network.GeminiTokenUsage

data class AgentChatUiState(
    val input: String = "",
    val messages: List<AgentChatMessage> = emptyList(),
    val memory: AgentChatMemorySnapshot = AgentChatMemorySnapshot(),
    val taskContextInput: String = AgentChatTaskContext().toEditableText(),
    val isLongTermMemoryDirty: Boolean = false,
    val selectedAgent: AgentChatAgentOption = AgentChatAgentOption.GEMINI_3_5_FLASH,
    val customTotalTokenLimit: Int? = null,
) : UiState {
    val latestTokenUsage: GeminiTokenUsage?
        get() =
            messages
                .asReversed()
                .firstOrNull { message ->
                    message.role == AgentChatRole.MODEL &&
                        !message.isLoading &&
                        !message.isError &&
                        message.tokenUsage?.hasAnyCount == true
                }?.tokenUsage

    val effectiveTotalTokenLimit: Int
        get() = customTotalTokenLimit ?: selectedAgent.totalTokenLimit

    val remainingTokenBudget: Int?
        get() = latestTokenUsage?.totalTokens?.let { (effectiveTotalTokenLimit - it).coerceAtLeast(0) }

    val isTokenLimitReached: Boolean
        get() = latestTokenUsage?.totalTokens?.let { it >= effectiveTotalTokenLimit } ?: false

    val isLoading: Boolean
        get() = messages.any { it.isLoading }

    val isAgentLocked: Boolean
        get() = messages.any { it.role == AgentChatRole.USER }

    val canChangeAgent: Boolean
        get() = !isLoading && !isAgentLocked

    val canSend: Boolean
        get() = input.isNotBlank() && !isLoading

    val canClear: Boolean
        get() = messages.isNotEmpty() && !isLoading

    val canStop: Boolean
        get() = isLoading

    val canSaveLongTermMemory: Boolean
        get() = isLongTermMemoryDirty && !isLoading

    val canClearTaskContext: Boolean
        get() = memory.taskContext.itemCount > 0 && !isLoading
}

data class AgentChatMessage(
    val role: AgentChatRole,
    val text: String,
    val isLoading: Boolean = false,
    val isError: Boolean = false,
    val tokenUsage: GeminiTokenUsage? = null,
)

enum class AgentChatRole {
    USER,
    MODEL,
}

enum class AgentChatScenario(
    val title: String,
) {
    SHORT("Short dialog"),
    LONG("Long dialog"),
    OVER_MODEL_LIMIT("Over model limit"),
}

enum class AgentChatAgentOption(
    val modelName: String,
    val title: String,
    val description: String,
    val inputTokenLimit: Int,
    val outputTokenLimit: Int,
) {
    GEMINI_3_5_FLASH(
        modelName = "gemini-3.5-flash",
        title = "Gemini 3.5 Flash",
        description = "Default chat agent for this app.",
        inputTokenLimit = GEMINI_FLASH_INPUT_TOKEN_LIMIT,
        outputTokenLimit = GEMINI_FLASH_OUTPUT_TOKEN_LIMIT,
    ),
    GEMINI_2_5_FLASH(
        modelName = "gemini-2.5-flash",
        title = "Gemini 2.5 Flash",
        description = "Balanced Gemini agent for everyday reasoning.",
        inputTokenLimit = GEMINI_FLASH_INPUT_TOKEN_LIMIT,
        outputTokenLimit = GEMINI_FLASH_OUTPUT_TOKEN_LIMIT,
    ),
    GEMINI_2_5_FLASH_LITE(
        modelName = "gemini-2.5-flash-lite",
        title = "Gemini 2.5 Flash-Lite",
        description = "Lower-latency Gemini agent for simpler turns.",
        inputTokenLimit = GEMINI_FLASH_INPUT_TOKEN_LIMIT,
        outputTokenLimit = GEMINI_FLASH_OUTPUT_TOKEN_LIMIT,
    ),
    GEMMA_4_26B_A4B_IT(
        modelName = "gemma-4-26b-a4b-it",
        title = "Gemma 4 26B A4B IT",
        description = "Free Gemma 4 MoE agent for faster open-model reasoning.",
        inputTokenLimit = GEMMA_4_CONTEXT_TOKEN_LIMIT,
        outputTokenLimit = GEMMA_4_OUTPUT_TOKEN_LIMIT,
    ),
    GEMMA_4_31B_IT(
        modelName = "gemma-4-31b-it",
        title = "Gemma 4 31B IT",
        description = "Free Gemma 4 dense agent for stronger open-model reasoning.",
        inputTokenLimit = GEMMA_4_CONTEXT_TOKEN_LIMIT,
        outputTokenLimit = GEMMA_4_OUTPUT_TOKEN_LIMIT,
    ),
    ;

    val totalTokenLimit: Int
        get() = inputTokenLimit + outputTokenLimit
}

private const val GEMINI_FLASH_INPUT_TOKEN_LIMIT = 1_048_576
private const val GEMINI_FLASH_OUTPUT_TOKEN_LIMIT = 65_536
private const val GEMMA_4_CONTEXT_TOKEN_LIMIT = 262_144
private const val GEMMA_4_OUTPUT_TOKEN_LIMIT = 128_000

sealed interface AgentChatAction : UiEvent {
    data class InputChanged(
        val input: String,
    ) : AgentChatAction

    data class AgentChanged(
        val agent: AgentChatAgentOption,
    ) : AgentChatAction

    data class TokenLimitChanged(
        val input: String,
    ) : AgentChatAction

    data class TaskContextChanged(
        val input: String,
    ) : AgentChatAction

    data class LongTermMemoryChanged(
        val markdown: String,
    ) : AgentChatAction

    data class ScenarioSelected(
        val scenario: AgentChatScenario,
    ) : AgentChatAction

    data object Submit : AgentChatAction

    data object ClearChat : AgentChatAction

    data object ClearTaskContext : AgentChatAction

    data object SaveLongTermMemory : AgentChatAction

    data object Stop : AgentChatAction
}
