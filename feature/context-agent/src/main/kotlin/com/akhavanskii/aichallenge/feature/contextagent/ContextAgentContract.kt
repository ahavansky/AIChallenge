package com.akhavanskii.aichallenge.feature.contextagent

import com.akhavanskii.aichallenge.core.mvvm.UiEvent
import com.akhavanskii.aichallenge.core.mvvm.UiState
import com.akhavanskii.aichallenge.core.network.GeminiTokenUsage

data class ContextAgentUiState(
    val input: String = "",
    val messages: List<ContextAgentMessage> = emptyList(),
    val selectedModel: ContextAgentModelOption = ContextAgentModelOption.GEMINI_3_5_FLASH,
    val selectedStrategy: ContextManagementStrategy = ContextManagementStrategy.SLIDING_WINDOW,
    val facts: List<ContextFact> = emptyList(),
    val branchingState: ContextBranchingState = ContextBranchingState(),
    val strategyStats: ContextStrategyStats? = null,
    val comparison: ContextScenarioComparison? = null,
    val isScenarioRunning: Boolean = false,
) : UiState {
    val activeMessages: List<ContextAgentMessage>
        get() =
            if (selectedStrategy == ContextManagementStrategy.BRANCHING) {
                branchingState.activeMessages
            } else {
                messages
            }

    val isLoading: Boolean
        get() = isScenarioRunning || activeMessages.any { it.isLoading }

    val canSend: Boolean
        get() = input.isNotBlank() && !isLoading

    val canClear: Boolean
        get() =
            (
                messages.isNotEmpty() ||
                    facts.isNotEmpty() ||
                    branchingState.hasContent ||
                    strategyStats != null ||
                    comparison != null
            ) &&
                !isLoading

    val canStop: Boolean
        get() = isLoading

    val canChangeModel: Boolean
        get() = !hasAnyUserMessage && !isLoading

    val canChangeStrategy: Boolean
        get() = !isLoading

    val canSaveCheckpoint: Boolean
        get() = selectedStrategy == ContextManagementStrategy.BRANCHING && activeMessages.isNotEmpty() && !isLoading

    val canCreateBranches: Boolean
        get() = selectedStrategy == ContextManagementStrategy.BRANCHING && !isLoading

    val activeBranch: ContextAgentBranch
        get() = branchingState.activeBranch

    val latestTokenUsage: GeminiTokenUsage?
        get() =
            activeMessages
                .asReversed()
                .firstOrNull { message ->
                    message.role == ContextAgentRole.MODEL &&
                        !message.isLoading &&
                        !message.isError &&
                        message.tokenUsage?.hasAnyCount == true
                }?.tokenUsage

    private val hasAnyUserMessage: Boolean
        get() =
            messages.any { it.role == ContextAgentRole.USER } ||
                branchingState.checkpointMessages.any { it.role == ContextAgentRole.USER } ||
                branchingState.branches.any { branch -> branch.messages.any { it.role == ContextAgentRole.USER } }
}

data class ContextAgentMessage(
    val role: ContextAgentRole,
    val text: String,
    val isLoading: Boolean = false,
    val isError: Boolean = false,
    val tokenUsage: GeminiTokenUsage? = null,
)

enum class ContextAgentRole {
    USER,
    MODEL,
}

enum class ContextManagementStrategy(
    val title: String,
    val shortTitle: String,
    val description: String,
) {
    SLIDING_WINDOW(
        title = "Sliding Window",
        shortTitle = "Window",
        description = "Stores and sends only the latest messages.",
    ),
    STICKY_FACTS(
        title = "Sticky Facts",
        shortTitle = "Facts",
        description = "Keeps key-value facts plus the latest messages.",
    ),
    BRANCHING(
        title = "Branching",
        shortTitle = "Branches",
        description = "Keeps a checkpoint and two independent branches.",
    ),
}

data class ContextFact(
    val key: String,
    val value: String,
)

data class ContextBranchingState(
    val checkpointMessages: List<ContextAgentMessage> = emptyList(),
    val branches: List<ContextAgentBranch> = defaultContextBranches(),
    val activeBranchId: ContextBranchId = ContextBranchId.A,
    val hasCheckpoint: Boolean = false,
) {
    val activeBranch: ContextAgentBranch
        get() = branches.firstOrNull { it.id == activeBranchId } ?: defaultContextBranches().first()

    val activeMessages: List<ContextAgentMessage>
        get() = checkpointMessages + activeBranch.messages

    val hasContent: Boolean
        get() = checkpointMessages.isNotEmpty() || branches.any { it.messages.isNotEmpty() }
}

data class ContextAgentBranch(
    val id: ContextBranchId,
    val title: String = id.title,
    val messages: List<ContextAgentMessage> = emptyList(),
)

enum class ContextBranchId(
    val title: String,
) {
    A("Branch A"),
    B("Branch B"),
}

data class ContextStrategyStats(
    val strategy: ContextManagementStrategy,
    val fullPromptTokens: Int? = null,
    val strategyPromptTokens: Int? = null,
    val savedPromptTokens: Int? = null,
    val savedPromptPercent: Int? = null,
    val storedMessageCount: Int = 0,
    val requestMessageCount: Int = 0,
    val droppedMessageCount: Int = 0,
    val factsCount: Int = 0,
    val activeBranchTitle: String? = null,
)

data class ContextScenarioComparison(
    val reports: List<ContextScenarioStrategyReport>,
    val evaluation: String,
)

data class ContextScenarioStrategyReport(
    val strategy: ContextManagementStrategy,
    val branchTitle: String? = null,
    val answer: String,
    val promptTokens: Int? = null,
    val requestMessageCount: Int = 0,
    val quality: String,
    val stability: String,
    val tokenUse: String,
    val userConvenience: String,
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
        description = "Default Gemini model for context strategy testing.",
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

    data class StrategyChanged(
        val strategy: ContextManagementStrategy,
    ) : ContextAgentAction

    data class BranchChanged(
        val branchId: ContextBranchId,
    ) : ContextAgentAction

    data object Submit : ContextAgentAction

    data object RunScenarioComparison : ContextAgentAction

    data object SaveCheckpoint : ContextAgentAction

    data object CreateBranches : ContextAgentAction

    data object Clear : ContextAgentAction

    data object Stop : ContextAgentAction
}

const val CONTEXT_AGENT_RECENT_MESSAGE_COUNT = 8
const val CONTEXT_AGENT_MAX_FACTS = 12

private const val GEMINI_FLASH_INPUT_TOKEN_LIMIT = 1_048_576
private const val GEMINI_FLASH_OUTPUT_TOKEN_LIMIT = 65_536
private const val GEMMA_4_CONTEXT_TOKEN_LIMIT = 262_144
private const val GEMMA_4_OUTPUT_TOKEN_LIMIT = 128_000

private fun defaultContextBranches(): List<ContextAgentBranch> =
    ContextBranchId.entries.map { branchId -> ContextAgentBranch(id = branchId) }
