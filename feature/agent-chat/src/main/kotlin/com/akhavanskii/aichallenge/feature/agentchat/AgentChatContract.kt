package com.akhavanskii.aichallenge.feature.agentchat

import com.akhavanskii.aichallenge.core.mvvm.UiEvent
import com.akhavanskii.aichallenge.core.mvvm.UiState

data class AgentChatUiState(
    val input: String = "",
    val selectedModel: AgentChatModelOption = AgentChatModelOption.DEFAULT,
    val messages: List<AgentChatMessage> = emptyList(),
    val memory: AgentChatMemorySnapshot = AgentChatMemorySnapshot(),
    val taskContextInput: String = AgentChatTaskContext().toEditableText(),
    val profiles: List<AgentChatUserProfile> = AgentChatProfileCatalog.defaults,
    val activeProfileId: String = SENIOR_KOTLIN_PROFILE_ID,
    val profileInput: String = AgentChatProfileCatalog.defaults.first { it.id == SENIOR_KOTLIN_PROFILE_ID }.toEditableText(),
    val invariants: AgentChatInvariantSet = AgentChatInvariantSet(),
    val invariantsInput: String = AgentChatInvariantSet().markdown,
    val lastInvariantCheck: AgentChatInvariantCheckSnapshot = AgentChatInvariantCheckSnapshot(),
    val compareResults: List<AgentChatProfileCompareResult> = emptyList(),
    val liveBriefing: AgentChatLiveBriefingUiState = AgentChatLiveBriefingUiState(),
    val isLongTermMemoryDirty: Boolean = false,
    val isInvariantsDirty: Boolean = false,
) : UiState {
    val isLoading: Boolean
        get() =
            messages.any { it.isLoading } ||
                compareResults.any { it.isLoading } ||
                liveBriefing.isLoading ||
                liveBriefing.isWatching ||
                memory.taskState.status == AgentTaskStatus.RUNNING

    val canRunTask: Boolean
        get() = input.isNotBlank() && !isLoading && memory.taskState.canStartNewTask

    val canCompareProfiles: Boolean
        get() = input.isNotBlank() && !isLoading && profiles.size >= 2

    val canClear: Boolean
        get() = messages.isNotEmpty() && !isLoading

    val canStop: Boolean
        get() = isLoading && memory.taskState.status != AgentTaskStatus.RUNNING

    val canPauseTask: Boolean
        get() = memory.taskState.canPause

    val canResumeTask: Boolean
        get() = !isLoading && memory.taskState.canResume

    val canRetryTask: Boolean
        get() = !isLoading && memory.taskState.canRetry

    val canApprovePlan: Boolean
        get() = !isLoading && memory.taskState.canApprovePlan

    val canRequestPlanRevision: Boolean
        get() = !isLoading && memory.taskState.canRequestPlanRevision

    val canAcceptValidation: Boolean
        get() = !isLoading && memory.taskState.canAcceptValidation

    val canRequestExecutionRevision: Boolean
        get() = !isLoading && memory.taskState.canRequestExecutionRevision

    val canResetTask: Boolean
        get() = !isLoading && memory.taskState.canReset

    val canSaveLongTermMemory: Boolean
        get() = isLongTermMemoryDirty && !isLoading

    val canSaveInvariants: Boolean
        get() = isInvariantsDirty && !isLoading

    val canListFetchTools: Boolean
        get() = !isLoading

    val canUseGitHubMcp: Boolean
        get() = input.isNotBlank() && !isLoading

    val canWatchLiveBriefingMcp: Boolean
        get() = !isLoading

    val canRefreshLiveBriefing: Boolean
        get() = liveBriefing.isVisible && !isLoading

    val canAddLiveBriefingReminder: Boolean
        get() = liveBriefing.isVisible && !isLoading

    val canClearTaskContext: Boolean
        get() = memory.taskContext.itemCount > 0 && !isLoading

    val activeProfile: AgentChatUserProfile
        get() =
            profiles.firstOrNull { it.id == activeProfileId }
                ?: AgentChatProfileCatalog.defaults.first { it.id == SENIOR_KOTLIN_PROFILE_ID }
}

data class AgentChatLiveBriefingUiState(
    val isVisible: Boolean = false,
    val isLoading: Boolean = false,
    val isWatching: Boolean = false,
    val status: String = "",
    val city: String = "",
    val weather: String = "",
    val headline: String = "",
    val bullets: List<String> = emptyList(),
    val nextAction: String = "",
    val newsItems: List<AgentChatLiveBriefingNewsItem> = emptyList(),
    val dueReminders: List<AgentChatLiveBriefingReminder> = emptyList(),
    val upcomingReminderCount: Int = 0,
    val errors: List<String> = emptyList(),
    val updatedAt: String = "",
    val statusMessage: String = "",
)

data class AgentChatLiveBriefingNewsItem(
    val title: String,
    val source: String,
)

data class AgentChatLiveBriefingReminder(
    val id: String,
    val title: String,
    val nextDueAt: String,
)

enum class AgentChatModelOption(
    val modelName: String,
    val title: String,
    val compactTitle: String,
    val description: String,
) {
    GEMINI_3_5_FLASH(
        modelName = "gemini-3.5-flash",
        title = "Gemini 3.5 Flash",
        compactTitle = "3.5 Flash",
        description = "Default project model; keeps Agent Chat behavior aligned with the app endpoint.",
    ),
    GEMINI_2_5_FLASH(
        modelName = "gemini-2.5-flash",
        title = "Gemini 2.5 Flash",
        compactTitle = "2.5 Flash",
        description = "Balanced Gemini model for everyday reasoning and implementation tasks.",
    ),
    GEMINI_2_5_FLASH_LITE(
        modelName = "gemini-2.5-flash-lite",
        title = "Gemini 2.5 Flash-Lite",
        compactTitle = "2.5 Lite",
        description = "Lower-latency Gemini model for simpler or more iterative tasks.",
    ),
    DEEPSEEK_V4_FLASH(
        modelName = "deepseek-v4-flash",
        title = "DeepSeek V4 Flash",
        compactTitle = "V4 Flash",
        description = "Lower-latency DeepSeek model for iterative agent chat tasks.",
    ),
    DEEPSEEK_V4_PRO(
        modelName = "deepseek-v4-pro",
        title = "DeepSeek V4 Pro",
        compactTitle = "V4 Pro",
        description = "Higher-capability DeepSeek model for more demanding agent chat tasks.",
    ),
    GEMMA_4_31B_IT(
        modelName = "gemma-4-31b-it",
        title = "Gemma 4 31B IT",
        compactTitle = "4 31B",
        description = "Free Gemma 4 dense model for stronger open-model reasoning.",
    ),
    ;

    companion object {
        val DEFAULT: AgentChatModelOption = GEMINI_3_5_FLASH

        fun fromModelName(modelName: String?): AgentChatModelOption = entries.firstOrNull { it.modelName == modelName } ?: DEFAULT
    }
}

data class AgentChatInvariantCheckSnapshot(
    val status: AgentChatInvariantCheckStatus = AgentChatInvariantCheckStatus.NOT_RUN,
    val stage: AgentChatInvariantCheckStage = AgentChatInvariantCheckStage.NONE,
    val invariantTitle: String = "",
    val conflict: String = "",
    val reason: String = "",
    val alternative: String = "",
    val repairAttempted: Boolean = false,
    val artifactStored: Boolean = false,
    val promptLayerIncluded: Boolean = false,
) {
    val safeAlternativePrompt: String
        get() =
            alternative
                .takeIf { it.isNotBlank() }
                ?.let { "Please solve the request within this invariant-safe alternative: $it" }
                .orEmpty()
}

enum class AgentChatInvariantCheckStatus(
    val title: String,
) {
    NOT_RUN("Not run"),
    PASSED("Passed"),
    BLOCKED("Blocked before agent"),
    REPAIRED("Repaired once"),
    FAILED("Failed after repair"),
}

enum class AgentChatInvariantCheckStage(
    val title: String,
) {
    NONE("No check yet"),
    PRE_FLIGHT("Pre-flight"),
    MODEL_OUTPUT("Model output"),
    REPAIR("Repair"),
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

    data class ModelChanged(
        val model: AgentChatModelOption,
    ) : AgentChatAction

    data class TaskContextChanged(
        val input: String,
    ) : AgentChatAction

    data class LongTermMemoryChanged(
        val markdown: String,
    ) : AgentChatAction

    data class InvariantsChanged(
        val markdown: String,
    ) : AgentChatAction

    data class ProfileChanged(
        val profileId: String,
    ) : AgentChatAction

    data class ProfileInputChanged(
        val input: String,
    ) : AgentChatAction

    data object StartTask : AgentChatAction

    data object PauseTask : AgentChatAction

    data object ResumeTask : AgentChatAction

    data object RetryTask : AgentChatAction

    data object ApprovePlan : AgentChatAction

    data object RequestPlanRevision : AgentChatAction

    data object AcceptValidation : AgentChatAction

    data object RequestExecutionRevision : AgentChatAction

    data object ResetTask : AgentChatAction

    data object CompareProfiles : AgentChatAction

    data object ClearChat : AgentChatAction

    data object ClearTaskContext : AgentChatAction

    data object ListFetchTools : AgentChatAction

    data object CallGitHubRepositoryTool : AgentChatAction

    data object WatchLiveBriefingMcp : AgentChatAction

    data object RefreshLiveBriefingMcp : AgentChatAction

    data object AddLiveBriefingDemoReminder : AgentChatAction

    data object SaveLongTermMemory : AgentChatAction

    data object SaveInvariants : AgentChatAction

    data object Stop : AgentChatAction
}
