package com.akhavanskii.aichallenge.feature.agentchat

import com.akhavanskii.aichallenge.core.mvvm.UiEvent
import com.akhavanskii.aichallenge.core.mvvm.UiState

data class AgentChatUiState(
    val input: String = "",
    val messages: List<AgentChatMessage> = emptyList(),
    val selectedAgent: AgentChatAgentOption = AgentChatAgentOption.GEMINI_3_5_FLASH,
) : UiState {
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
}

data class AgentChatMessage(
    val role: AgentChatRole,
    val text: String,
    val isLoading: Boolean = false,
    val isError: Boolean = false,
)

enum class AgentChatRole {
    USER,
    MODEL,
}

enum class AgentChatAgentOption(
    val modelName: String,
    val title: String,
    val description: String,
) {
    GEMINI_3_5_FLASH(
        modelName = "gemini-3.5-flash",
        title = "Gemini 3.5 Flash",
        description = "Default chat agent for this app.",
    ),
    GEMINI_2_5_FLASH(
        modelName = "gemini-2.5-flash",
        title = "Gemini 2.5 Flash",
        description = "Balanced Gemini agent for everyday reasoning.",
    ),
    GEMINI_2_5_FLASH_LITE(
        modelName = "gemini-2.5-flash-lite",
        title = "Gemini 2.5 Flash-Lite",
        description = "Lower-latency Gemini agent for simpler turns.",
    ),
}

sealed interface AgentChatAction : UiEvent {
    data class InputChanged(
        val input: String,
    ) : AgentChatAction

    data class AgentChanged(
        val agent: AgentChatAgentOption,
    ) : AgentChatAction

    data object Submit : AgentChatAction

    data object ClearChat : AgentChatAction
}
