package com.akhavanskii.aichallenge.feature.agentchat

import com.akhavanskii.aichallenge.core.mvvm.UiEvent
import com.akhavanskii.aichallenge.core.mvvm.UiState

data class AgentChatUiState(
    val input: String = "",
    val messages: List<AgentChatMessage> = emptyList(),
    val memory: AgentChatMemorySnapshot = AgentChatMemorySnapshot(),
    val taskContextInput: String = AgentChatTaskContext().toEditableText(),
    val profiles: List<AgentChatUserProfile> = AgentChatProfileCatalog.defaults,
    val activeProfileId: String = SENIOR_KOTLIN_PROFILE_ID,
    val profileInput: String = AgentChatProfileCatalog.defaults.first { it.id == SENIOR_KOTLIN_PROFILE_ID }.toEditableText(),
    val compareResults: List<AgentChatProfileCompareResult> = emptyList(),
    val isLongTermMemoryDirty: Boolean = false,
) : UiState {
    val isLoading: Boolean
        get() = messages.any { it.isLoading } || compareResults.any { it.isLoading }

    val canSend: Boolean
        get() = input.isNotBlank() && !isLoading

    val canCompareProfiles: Boolean
        get() = input.isNotBlank() && !isLoading && profiles.size >= 2

    val canClear: Boolean
        get() = messages.isNotEmpty() && !isLoading

    val canStop: Boolean
        get() = isLoading

    val canSaveLongTermMemory: Boolean
        get() = isLongTermMemoryDirty && !isLoading

    val canClearTaskContext: Boolean
        get() = memory.taskContext.itemCount > 0 && !isLoading

    val activeProfile: AgentChatUserProfile
        get() =
            profiles.firstOrNull { it.id == activeProfileId }
                ?: AgentChatProfileCatalog.defaults.first { it.id == SENIOR_KOTLIN_PROFILE_ID }
}

data class AgentChatProfileCompareResult(
    val profileId: String,
    val profileTitle: String,
    val text: String,
    val isLoading: Boolean = false,
    val isError: Boolean = false,
)

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

sealed interface AgentChatAction : UiEvent {
    data class InputChanged(
        val input: String,
    ) : AgentChatAction

    data class TaskContextChanged(
        val input: String,
    ) : AgentChatAction

    data class LongTermMemoryChanged(
        val markdown: String,
    ) : AgentChatAction

    data class ProfileChanged(
        val profileId: String,
    ) : AgentChatAction

    data class ProfileInputChanged(
        val input: String,
    ) : AgentChatAction

    data object Submit : AgentChatAction

    data object CompareProfiles : AgentChatAction

    data object ClearChat : AgentChatAction

    data object ClearTaskContext : AgentChatAction

    data object SaveLongTermMemory : AgentChatAction

    data object Stop : AgentChatAction
}
