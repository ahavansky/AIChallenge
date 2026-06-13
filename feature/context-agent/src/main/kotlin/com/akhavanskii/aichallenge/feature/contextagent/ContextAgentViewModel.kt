package com.akhavanskii.aichallenge.feature.contextagent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akhavanskii.aichallenge.core.network.AgentMessage
import com.akhavanskii.aichallenge.core.network.AgentResult
import com.akhavanskii.aichallenge.core.network.GeminiGenerationConfig
import com.akhavanskii.aichallenge.core.network.GeminiNetworkError
import com.akhavanskii.aichallenge.core.network.GeminiResult
import com.akhavanskii.aichallenge.core.network.GeminiTokenUsage
import com.akhavanskii.aichallenge.core.network.LlmAgent
import com.akhavanskii.aichallenge.core.utils.normalizedPromptOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ContextAgentViewModel
    @Inject
    constructor(
        private val llmAgent: LlmAgent,
        private val historyStore: ContextAgentHistoryStore,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(ContextAgentUiState())
        val uiState: StateFlow<ContextAgentUiState> = mutableUiState.asStateFlow()
        private var activeRequestJob: Job? = null
        private var activeRequestId = 0L

        init {
            viewModelScope.launch {
                val snapshot = historyStore.load()
                mutableUiState.update { current ->
                    if (current == ContextAgentUiState()) {
                        current.copy(
                            messages = snapshot.messages,
                            selectedModel = snapshot.selectedModel,
                            selectedStrategy = snapshot.selectedStrategy,
                            facts = snapshot.facts,
                            branchingState = snapshot.branchingState,
                            strategyStats = snapshot.strategyStats,
                            comparison = snapshot.comparison,
                        )
                    } else {
                        current
                    }
                }
            }
        }

        fun onAction(action: ContextAgentAction) {
            when (action) {
                is ContextAgentAction.BranchChanged -> onBranchChanged(action.branchId)
                ContextAgentAction.Clear -> clear()
                ContextAgentAction.CreateBranches -> createBranches()
                is ContextAgentAction.InputChanged -> onInputChanged(action.input)
                is ContextAgentAction.ModelChanged -> onModelChanged(action.model)
                ContextAgentAction.RunScenarioComparison -> runScenarioComparison()
                ContextAgentAction.SaveCheckpoint -> saveCheckpoint()
                ContextAgentAction.Stop -> stopActiveRequest()
                is ContextAgentAction.StrategyChanged -> onStrategyChanged(action.strategy)
                ContextAgentAction.Submit -> submit()
            }
        }

        private fun onInputChanged(input: String) {
            mutableUiState.update { current ->
                if (current.isLoading) current else current.copy(input = input)
            }
        }

        private fun onModelChanged(model: ContextAgentModelOption) {
            updateStateAndPersist { current ->
                if (current.canChangeModel) {
                    current.copy(selectedModel = model)
                } else {
                    current
                }
            }
        }

        private fun onStrategyChanged(strategy: ContextManagementStrategy) {
            updateStateAndPersist { current ->
                if (current.canChangeStrategy) {
                    current.copy(selectedStrategy = strategy)
                } else {
                    current
                }
            }
        }

        private fun onBranchChanged(branchId: ContextBranchId) {
            updateStateAndPersist { current ->
                if (current.selectedStrategy == ContextManagementStrategy.BRANCHING && !current.isLoading) {
                    current.copy(branchingState = current.branchingState.copy(activeBranchId = branchId))
                } else {
                    current
                }
            }
        }

        private fun saveCheckpoint() {
            updateStateAndPersist { current ->
                if (!current.canSaveCheckpoint) {
                    current
                } else {
                    current.copy(
                        branchingState =
                            ContextBranchingState(
                                checkpointMessages = current.activeMessages.filterNot { it.isLoading },
                                branches = defaultBranches(),
                                activeBranchId = ContextBranchId.A,
                                hasCheckpoint = true,
                            ),
                        strategyStats = null,
                    )
                }
            }
        }

        private fun createBranches() {
            updateStateAndPersist { current ->
                if (!current.canCreateBranches) {
                    current
                } else {
                    val checkpoint =
                        if (current.branchingState.hasCheckpoint) {
                            current.branchingState.checkpointMessages
                        } else {
                            current.activeMessages.filterNot { it.isLoading }
                        }
                    current.copy(
                        selectedStrategy = ContextManagementStrategy.BRANCHING,
                        branchingState =
                            ContextBranchingState(
                                checkpointMessages = checkpoint,
                                branches = defaultBranches(),
                                activeBranchId = ContextBranchId.A,
                                hasCheckpoint = true,
                            ),
                        strategyStats = null,
                    )
                }
            }
        }

        private fun submit() {
            val currentState = mutableUiState.value
            if (currentState.isLoading) return

            val prompt = currentState.input.normalizedPromptOrNull()
            if (prompt == null) {
                mutableUiState.update {
                    it.withAppendedActiveMessages(
                        listOf(
                            ContextAgentMessage(
                                role = ContextAgentRole.MODEL,
                                text = "Enter a message before sending.",
                                isError = true,
                            ),
                        ),
                    )
                }
                return
            }

            val visibleMessagesBeforeSubmit = currentState.activeMessages
            val fullRequestMessages = visibleMessagesBeforeSubmit.toAgentMessages() + AgentMessage.User(prompt)
            val userMessage = ContextAgentMessage(role = ContextAgentRole.USER, text = prompt)
            val loadingMessage =
                ContextAgentMessage(
                    role = ContextAgentRole.MODEL,
                    text = "Preparing ${currentState.selectedStrategy.title} context for ${currentState.selectedModel.title}",
                    isLoading = true,
                )
            val updatedState =
                mutableUiState.updateAndGet {
                    it
                        .copy(
                            input = "",
                            comparison = null,
                        ).withAppendedActiveMessages(listOf(userMessage, loadingMessage))
                }
            persistHistory(updatedState)

            launchActiveRequest { requestId ->
                val facts =
                    if (currentState.selectedStrategy == ContextManagementStrategy.STICKY_FACTS) {
                        val updatedFacts = updateFactsFromUserMessage(currentState.facts, prompt)
                        updateFactsAndPersist(requestId, updatedFacts)
                        updatedFacts
                    } else {
                        currentState.facts
                    }
                if (!isCurrentRequest(requestId)) return@launchActiveRequest

                val preparedContext =
                    prepareStrategyContext(
                        state = currentState.copy(facts = facts),
                        fullRequestMessages = fullRequestMessages,
                    )
                updateStatsAndPersist(requestId, preparedContext.stats)
                if (!isCurrentRequest(requestId)) return@launchActiveRequest

                when (
                    val result =
                        llmAgent.sendMessage(
                            messages = preparedContext.messages,
                            generationConfig = currentState.selectedModel.chatGenerationConfig(),
                            modelName = currentState.selectedModel.modelName,
                            totalTokenLimit = currentState.selectedModel.inputTokenLimit,
                        )
                ) {
                    is GeminiResult.Success -> {
                        if (isCurrentRequest(requestId)) {
                            replaceLoadingMessage(
                                text = result.value,
                                tokenUsage = result.tokenUsage,
                                trimSlidingWindow = currentState.selectedStrategy == ContextManagementStrategy.SLIDING_WINDOW,
                            )
                        }
                    }
                    is GeminiResult.Failure -> {
                        if (isCurrentRequest(requestId)) {
                            replaceLoadingMessage(
                                text = currentState.selectedModel.contextAgentErrorMessage(result.error),
                                isError = true,
                                trimSlidingWindow = currentState.selectedStrategy == ContextManagementStrategy.SLIDING_WINDOW,
                            )
                        }
                    }
                }
            }
        }

        private fun runScenarioComparison() {
            val currentState = mutableUiState.value
            if (currentState.isLoading) return

            val startedState =
                mutableUiState.updateAndGet {
                    it.copy(
                        comparison = null,
                        strategyStats = null,
                        isScenarioRunning = true,
                    )
                }
            persistHistory(startedState)

            launchActiveRequest { requestId ->
                val reports =
                    buildScenarioReports(
                        requestId = requestId,
                        model = currentState.selectedModel,
                    )
                if (!isCurrentRequest(requestId)) return@launchActiveRequest

                val comparison =
                    ContextScenarioComparison(
                        reports = reports,
                        evaluation = buildScenarioEvaluation(reports),
                    )
                val finishedState =
                    mutableUiState.updateAndGet { current ->
                        current.copy(
                            comparison = comparison,
                            isScenarioRunning = false,
                        )
                    }
                persistHistory(finishedState)
            }
        }

        private suspend fun buildScenarioReports(
            requestId: Long,
            model: ContextAgentModelOption,
        ): List<ContextScenarioStrategyReport> {
            val linearMessages = CONTEXT_REQUIREMENTS_SCENARIO.toMessages()
            val linearAgentMessages = linearMessages.toAgentMessages() + AgentMessage.User(CONTEXT_SCENARIO_FINAL_PROMPT)
            val scenarioFacts =
                CONTEXT_REQUIREMENTS_SCENARIO.fold(emptyList<ContextFact>()) { facts, turn ->
                    updateFactsFromUserMessage(facts, turn.user)
                }

            val checkpointMessages = CONTEXT_REQUIREMENTS_SCENARIO.take(8).toMessages()
            val branchAAgentMessages =
                checkpointMessages.toAgentMessages() +
                    CONTEXT_REQUIREMENTS_BRANCH_A.toMessages().toAgentMessages() +
                    AgentMessage.User(CONTEXT_SCENARIO_FINAL_PROMPT)
            val branchBAgentMessages =
                checkpointMessages.toAgentMessages() +
                    CONTEXT_REQUIREMENTS_BRANCH_B.toMessages().toAgentMessages() +
                    AgentMessage.User(CONTEXT_SCENARIO_FINAL_PROMPT)

            val requests =
                listOf(
                    ScenarioRequest(
                        strategy = ContextManagementStrategy.SLIDING_WINDOW,
                        messages = linearAgentMessages.takeRecentContextMessages(),
                    ),
                    ScenarioRequest(
                        strategy = ContextManagementStrategy.STICKY_FACTS,
                        messages = scenarioFacts.toFactsAgentMessages() + linearAgentMessages.takeRecentContextMessages(),
                    ),
                    ScenarioRequest(
                        strategy = ContextManagementStrategy.BRANCHING,
                        branchTitle = ContextBranchId.A.title,
                        messages = branchAAgentMessages,
                    ),
                    ScenarioRequest(
                        strategy = ContextManagementStrategy.BRANCHING,
                        branchTitle = ContextBranchId.B.title,
                        messages = branchBAgentMessages,
                    ),
                )

            val reports = mutableListOf<ContextScenarioStrategyReport>()
            for (request in requests) {
                if (!isCurrentRequest(requestId)) break
                reports += runScenarioRequest(request = request, model = model)
            }
            return reports
        }

        private suspend fun runScenarioRequest(
            request: ScenarioRequest,
            model: ContextAgentModelOption,
        ): ContextScenarioStrategyReport {
            val promptTokens =
                llmAgent
                    .countTokens(
                        messages = request.messages,
                        modelName = model.modelName,
                    ).successValueOrNull()
            val answer =
                when (
                    val result =
                        llmAgent.sendMessage(
                            messages = request.messages,
                            generationConfig = model.scenarioGenerationConfig(),
                            modelName = model.modelName,
                            totalTokenLimit = model.inputTokenLimit,
                        )
                ) {
                    is GeminiResult.Success -> result.value
                    is GeminiResult.Failure -> model.contextAgentErrorMessage(result.error)
                }
            return ContextScenarioStrategyReport(
                strategy = request.strategy,
                branchTitle = request.branchTitle,
                answer = answer,
                promptTokens = promptTokens,
                requestMessageCount = request.messages.size,
                quality = request.strategy.scenarioQuality(request.branchTitle),
                stability = request.strategy.scenarioStability(request.branchTitle),
                tokenUse = request.strategy.scenarioTokenUse(promptTokens),
                userConvenience = request.strategy.scenarioConvenience(),
            )
        }

        private suspend fun prepareStrategyContext(
            state: ContextAgentUiState,
            fullRequestMessages: List<AgentMessage>,
        ): PreparedContext {
            val strategyMessages =
                when (state.selectedStrategy) {
                    ContextManagementStrategy.SLIDING_WINDOW -> fullRequestMessages.takeRecentContextMessages()
                    ContextManagementStrategy.STICKY_FACTS ->
                        state.facts.toFactsAgentMessages() + fullRequestMessages.takeRecentContextMessages()
                    ContextManagementStrategy.BRANCHING -> fullRequestMessages
                }
            val fullPromptTokens =
                llmAgent
                    .countTokens(
                        messages = fullRequestMessages,
                        modelName = state.selectedModel.modelName,
                    ).successValueOrNull()
            val strategyPromptTokens =
                llmAgent
                    .countTokens(
                        messages = strategyMessages,
                        modelName = state.selectedModel.modelName,
                    ).successValueOrNull()
            val stats =
                ContextStrategyStats(
                    strategy = state.selectedStrategy,
                    fullPromptTokens = fullPromptTokens,
                    strategyPromptTokens = strategyPromptTokens,
                    savedPromptTokens =
                        fullPromptTokens?.let { full ->
                            strategyPromptTokens?.let { strategy -> (full - strategy).coerceAtLeast(0) }
                        },
                    savedPromptPercent =
                        fullPromptTokens?.takeIf { it > 0 }?.let { full ->
                            strategyPromptTokens?.let { strategy ->
                                (((full - strategy).coerceAtLeast(0) * 100L) / full).toInt()
                            }
                        },
                    storedMessageCount = state.activeMessages.size,
                    requestMessageCount = strategyMessages.size,
                    droppedMessageCount = (fullRequestMessages.size - strategyMessages.size).coerceAtLeast(0),
                    factsCount = state.facts.size,
                    activeBranchTitle =
                        if (state.selectedStrategy == ContextManagementStrategy.BRANCHING) {
                            state.activeBranch.title
                        } else {
                            null
                        },
                )
            return PreparedContext(messages = strategyMessages, stats = stats)
        }

        private fun clear() {
            updateStateAndPersist { current ->
                if (current.isLoading) {
                    current
                } else {
                    ContextAgentUiState(
                        selectedModel = current.selectedModel,
                        selectedStrategy = current.selectedStrategy,
                    )
                }
            }
        }

        private fun stopActiveRequest() {
            if (!mutableUiState.value.isLoading) return

            activeRequestId += 1
            activeRequestJob?.cancel()
            activeRequestJob = null
            val updatedState =
                mutableUiState.updateAndGet { current ->
                    if (current.isScenarioRunning) {
                        current.copy(isScenarioRunning = false)
                    } else {
                        current.withReplacedActiveLoading(
                            ContextAgentMessage(
                                role = ContextAgentRole.MODEL,
                                text = "Stopped by user.",
                                isError = true,
                            ),
                        )
                    }
                }
            persistHistory(updatedState)
        }

        private fun replaceLoadingMessage(
            text: String,
            isError: Boolean = false,
            tokenUsage: GeminiTokenUsage? = null,
            trimSlidingWindow: Boolean,
        ) {
            val updatedState =
                mutableUiState.updateAndGet { current ->
                    val replaced =
                        current.withReplacedActiveLoading(
                            ContextAgentMessage(
                                role = ContextAgentRole.MODEL,
                                text = text,
                                isError = isError,
                                tokenUsage = tokenUsage,
                            ),
                        )
                    if (trimSlidingWindow) {
                        replaced.copy(messages = replaced.messages.takeLast(CONTEXT_AGENT_RECENT_MESSAGE_COUNT))
                    } else {
                        replaced
                    }
                }
            persistHistory(updatedState)
        }

        private fun updateFactsAndPersist(
            requestId: Long,
            facts: List<ContextFact>,
        ) {
            if (!isCurrentRequest(requestId)) return
            val updatedState =
                mutableUiState.updateAndGet { current ->
                    current.copy(facts = facts)
                }
            persistHistory(updatedState)
        }

        private fun updateStatsAndPersist(
            requestId: Long,
            stats: ContextStrategyStats,
        ) {
            if (!isCurrentRequest(requestId)) return
            val updatedState =
                mutableUiState.updateAndGet { current ->
                    current.copy(strategyStats = stats)
                }
            persistHistory(updatedState)
        }

        private fun updateStateAndPersist(transform: (ContextAgentUiState) -> ContextAgentUiState) {
            val updatedState = mutableUiState.updateAndGet(transform)
            persistHistory(updatedState)
        }

        private fun persistHistory(state: ContextAgentUiState) {
            viewModelScope.launch {
                historyStore.save(state.toHistorySnapshot())
            }
        }

        private fun launchActiveRequest(block: suspend (Long) -> Unit) {
            val requestId = activeRequestId + 1
            activeRequestId = requestId
            val job =
                viewModelScope.launch(start = CoroutineStart.LAZY) {
                    block(requestId)
                }
            activeRequestJob = job
            job.invokeOnCompletion {
                if (activeRequestId == requestId && activeRequestJob == job) {
                    activeRequestJob = null
                    mutableUiState.update { current ->
                        if (current.isScenarioRunning) current.copy(isScenarioRunning = false) else current
                    }
                }
            }
            job.start()
        }

        private fun isCurrentRequest(requestId: Long): Boolean = activeRequestId == requestId

        private fun ContextAgentUiState.toHistorySnapshot(): ContextAgentHistorySnapshot =
            ContextAgentHistorySnapshot(
                messages = messages.filterNot { it.isLoading },
                selectedModel = selectedModel,
                selectedStrategy = selectedStrategy,
                facts = facts,
                branchingState =
                    branchingState.copy(
                        checkpointMessages = branchingState.checkpointMessages.filterNot { it.isLoading },
                        branches =
                            branchingState.branches.map { branch ->
                                branch.copy(messages = branch.messages.filterNot { it.isLoading })
                            },
                    ),
                strategyStats = strategyStats,
                comparison = comparison,
            )

        private fun ContextAgentUiState.withAppendedActiveMessages(newMessages: List<ContextAgentMessage>): ContextAgentUiState =
            if (selectedStrategy == ContextManagementStrategy.BRANCHING) {
                copy(branchingState = branchingState.withUpdatedActiveBranch { it + newMessages })
            } else {
                copy(messages = messages + newMessages)
            }

        private fun ContextAgentUiState.withReplacedActiveLoading(message: ContextAgentMessage): ContextAgentUiState =
            if (selectedStrategy == ContextManagementStrategy.BRANCHING) {
                copy(branchingState = branchingState.withUpdatedActiveBranch { it.replaceLastLoading(message) })
            } else {
                copy(messages = messages.replaceLastLoading(message))
            }

        private fun ContextBranchingState.withUpdatedActiveBranch(
            transform: (List<ContextAgentMessage>) -> List<ContextAgentMessage>,
        ): ContextBranchingState =
            copy(
                branches =
                    branches.map { branch ->
                        if (branch.id == activeBranchId) {
                            branch.copy(messages = transform(branch.messages))
                        } else {
                            branch
                        }
                    },
            )

        private fun List<ContextAgentMessage>.replaceLastLoading(message: ContextAgentMessage): List<ContextAgentMessage> {
            val loadingIndex = indexOfLast { it.isLoading }
            if (loadingIndex == -1) return this
            return toMutableList().apply {
                set(loadingIndex, message)
            }
        }
    }

private data class PreparedContext(
    val messages: List<AgentMessage>,
    val stats: ContextStrategyStats,
)

private data class ScenarioRequest(
    val strategy: ContextManagementStrategy,
    val messages: List<AgentMessage>,
    val branchTitle: String? = null,
)

private data class ContextScenarioTurn(
    val user: String,
    val assistant: String,
)

private fun defaultBranches(): List<ContextAgentBranch> = ContextBranchId.entries.map { branchId -> ContextAgentBranch(id = branchId) }

private fun List<ContextAgentMessage>.toAgentMessages(): List<AgentMessage> {
    val agentMessages = mutableListOf<AgentMessage>()
    var pendingUserMessage: ContextAgentMessage? = null

    for (message in this) {
        if (message.isLoading || message.isError) {
            pendingUserMessage = null
            continue
        }

        when (message.role) {
            ContextAgentRole.USER -> pendingUserMessage = message
            ContextAgentRole.MODEL -> {
                val userMessage = pendingUserMessage ?: continue
                agentMessages += AgentMessage.User(userMessage.text)
                agentMessages += AgentMessage.Model(message.text)
                pendingUserMessage = null
            }
        }
    }

    return agentMessages
}

private fun List<ContextScenarioTurn>.toMessages(): List<ContextAgentMessage> =
    flatMap { turn ->
        listOf(
            ContextAgentMessage(role = ContextAgentRole.USER, text = turn.user),
            ContextAgentMessage(role = ContextAgentRole.MODEL, text = turn.assistant),
        )
    }

private fun List<AgentMessage>.takeRecentContextMessages(): List<AgentMessage> {
    val tail = takeLast(CONTEXT_AGENT_RECENT_MESSAGE_COUNT)
    return if (tail.firstOrNull() is AgentMessage.Model) {
        tail.drop(1)
    } else {
        tail
    }
}

private fun List<ContextFact>.toFactsAgentMessages(): List<AgentMessage> =
    if (isEmpty()) {
        emptyList()
    } else {
        listOf(
            AgentMessage.User(
                buildString {
                    appendLine("Sticky facts memory. Use these key-value facts as durable context; this is not a summary.")
                    this@toFactsAgentMessages.forEach { fact ->
                        appendLine("- ${fact.key}: ${fact.value}")
                    }
                },
            ),
        )
    }

private fun updateFactsFromUserMessage(
    existingFacts: List<ContextFact>,
    userMessage: String,
): List<ContextFact> {
    val discoveredFacts = extractFacts(userMessage)
    if (discoveredFacts.isEmpty()) return existingFacts

    val factMap = linkedMapOf<String, String>()
    existingFacts.forEach { fact ->
        factMap[fact.key] = fact.value
    }
    discoveredFacts.forEach { fact ->
        val currentValue = factMap[fact.key]
        factMap[fact.key] =
            if (currentValue == null) {
                fact.value
            } else {
                mergeFactValue(currentValue, fact.value)
            }
    }
    return factMap
        .entries
        .take(CONTEXT_AGENT_MAX_FACTS)
        .map { (key, value) -> ContextFact(key = key, value = value) }
}

private fun extractFacts(userMessage: String): List<ContextFact> {
    val explicitFacts =
        userMessage
            .lineSequence()
            .mapNotNull { line -> line.toExplicitFactOrNull() }
            .toList()
    val sentenceFacts =
        userMessage
            .split('.', '\n', ';')
            .mapNotNull { sentence -> sentence.toKeywordFactOrNull() }
    return (explicitFacts + sentenceFacts).distinctBy { it.key to it.value }
}

private fun String.toExplicitFactOrNull(): ContextFact? {
    val separatorIndex = indexOfFirst { it == ':' || it == '=' }
    if (separatorIndex !in 1..40) return null
    val rawKey = take(separatorIndex).trim()
    val rawValue = drop(separatorIndex + 1).trim()
    if (rawValue.isBlank()) return null
    val key = rawKey.normalizedFactKey()
    return ContextFact(key = key, value = rawValue.cleanFactValue())
}

private fun String.toKeywordFactOrNull(): ContextFact? {
    val value = trim().cleanFactValue()
    if (value.length < 8) return null
    val lower = value.lowercase(Locale.ROOT)
    val key =
        when {
            lower.containsAny("цель", "goal", "задача", "task") -> "goal"
            lower.containsAny("огранич", "нельзя", "без ", "constraint", "must not", " no ") -> "constraints"
            lower.containsAny("предпоч", "важно", "хочу", "желательно", "preference") -> "preferences"
            lower.containsAny("решили", "решение", "договор", "выбрали", "decision") -> "decisions"
            lower.containsAny("срок", "deadline", "недел", "date") -> "deadline"
            lower.containsAny("бюджет", "budget") -> "budget"
            lower.containsAny("роль", "roles", "пользователь", "курьер", "manager", "админ") -> "roles"
            else -> null
        }
    return key?.let { ContextFact(key = it, value = value) }
}

private fun String.normalizedFactKey(): String =
    trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-zа-я0-9]+"), "_")
        .trim('_')
        .ifBlank { "fact" }

private fun String.cleanFactValue(): String = trim().replace(Regex("\\s+"), " ").take(MAX_FACT_VALUE_LENGTH)

private fun mergeFactValue(
    currentValue: String,
    newValue: String,
): String =
    if (currentValue.contains(newValue, ignoreCase = true)) {
        currentValue
    } else {
        "$currentValue; $newValue".take(MAX_FACT_VALUE_LENGTH)
    }

private fun String.containsAny(vararg needles: String): Boolean = needles.any { needle -> contains(needle) }

private fun ContextManagementStrategy.scenarioQuality(branchTitle: String?): String =
    when (this) {
        ContextManagementStrategy.SLIDING_WINDOW ->
            "Good for the last turns, weaker when the final answer needs early requirements."
        ContextManagementStrategy.STICKY_FACTS ->
            "Usually strongest for a requirements brief because durable facts survive window trimming."
        ContextManagementStrategy.BRANCHING ->
            "Strong for the selected ${branchTitle ?: "branch"} because alternatives stay isolated."
    }

private fun ContextManagementStrategy.scenarioStability(branchTitle: String?): String =
    when (this) {
        ContextManagementStrategy.SLIDING_WINDOW -> "Lower: old goals and constraints can disappear."
        ContextManagementStrategy.STICKY_FACTS -> "Higher: goal, constraints, decisions, and preferences remain pinned."
        ContextManagementStrategy.BRANCHING -> "Higher inside ${branchTitle ?: "each branch"}; cross-branch leakage is avoided."
    }

private fun ContextManagementStrategy.scenarioTokenUse(promptTokens: Int?): String =
    when (this) {
        ContextManagementStrategy.SLIDING_WINDOW -> "Lowest request size: ${promptTokens.formatTokenCount()} prompt tokens."
        ContextManagementStrategy.STICKY_FACTS -> "Low to medium request size: ${promptTokens.formatTokenCount()} prompt tokens."
        ContextManagementStrategy.BRANCHING -> "Medium request size per branch: ${promptTokens.formatTokenCount()} prompt tokens."
    }

private fun ContextManagementStrategy.scenarioConvenience(): String =
    when (this) {
        ContextManagementStrategy.SLIDING_WINDOW -> "Simplest for users, but they may need to repeat important details."
        ContextManagementStrategy.STICKY_FACTS -> "Convenient when facts are visible and editable as a durable memory block."
        ContextManagementStrategy.BRANCHING -> "More controls, but useful for exploring two independent solution paths."
    }

private fun buildScenarioEvaluation(reports: List<ContextScenarioStrategyReport>): String =
    buildString {
        appendLine("Scenario: collecting a 10-15 turn requirements brief without summaries.")
        appendLine("Sliding Window is cheapest but can lose early constraints.")
        appendLine("Sticky Facts keeps the most stable requirement memory for a single linear dialog.")
        appendLine("Branching is best when the user wants to compare alternatives from one checkpoint.")
        val tokenLine =
            reports.joinToString(separator = "; ") { report ->
                val label = report.branchTitle?.let { "${report.strategy.shortTitle} $it" } ?: report.strategy.shortTitle
                "$label=${report.promptTokens.formatTokenCount()}"
            }
        append("Prompt tokens by run: $tokenLine.")
    }

private fun ContextAgentModelOption.chatGenerationConfig(): GeminiGenerationConfig =
    GeminiGenerationConfig(
        maxOutputTokens =
            if (isGemma) {
                GEMMA_CHAT_MAX_OUTPUT_TOKENS
            } else {
                CHAT_MAX_OUTPUT_TOKENS
            },
    )

private fun ContextAgentModelOption.scenarioGenerationConfig(): GeminiGenerationConfig =
    GeminiGenerationConfig(
        maxOutputTokens =
            if (isGemma) {
                GEMMA_SCENARIO_MAX_OUTPUT_TOKENS
            } else {
                SCENARIO_MAX_OUTPUT_TOKENS
            },
        temperature = 0.2,
    )

private val ContextAgentModelOption.isGemma: Boolean
    get() = modelName.startsWith("gemma-")

private fun ContextAgentModelOption.contextAgentErrorMessage(error: GeminiNetworkError): String =
    if (this == ContextAgentModelOption.GEMMA_4_31B_IT && error is GeminiNetworkError.Http && error.statusCode == 500) {
        "Gemma 4 31B returned provider HTTP 500 on a small request. " +
            "This looks like model availability or provider instability, not context management. " +
            "Try Gemini 2.5 Flash, Gemini 2.5 Flash-Lite, or Gemma 4 26B A4B IT."
    } else {
        error.userMessage
    }

private fun AgentResult<Int>.successValueOrNull(): Int? = (this as? GeminiResult.Success<Int>)?.value

private fun Int?.formatTokenCount(): String = this?.let { String.format(Locale.US, "%,d", it) } ?: "unknown"

private const val CHAT_MAX_OUTPUT_TOKENS = 4_096
private const val SCENARIO_MAX_OUTPUT_TOKENS = 4_096
private const val GEMMA_CHAT_MAX_OUTPUT_TOKENS = 1_024
private const val GEMMA_SCENARIO_MAX_OUTPUT_TOKENS = 1_024
private const val MAX_FACT_VALUE_LENGTH = 220

private val CONTEXT_REQUIREMENTS_SCENARIO =
    listOf(
        ContextScenarioTurn(
            user = "Цель: собрать ТЗ для Android-приложения управления доставками.",
            assistant = "Зафиксировал цель: Android-приложение для доставки.",
        ),
        ContextScenarioTurn(
            user = "Роли: менеджер склада, курьер и администратор.",
            assistant = "Роли добавлены в требования.",
        ),
        ContextScenarioTurn(
            user = "Ограничение: в MVP не делаем оплату и веб-кабинет.",
            assistant = "Исключил оплату и веб-кабинет из MVP.",
        ),
        ContextScenarioTurn(
            user = "Важно: приложение должно работать с плохим интернетом и кешировать активный маршрут.",
            assistant = "Отмечено: офлайн-кеш активного маршрута.",
        ),
        ContextScenarioTurn(
            user = "Решение: авторизация через телефон и одноразовый код.",
            assistant = "Авторизация через OTP по телефону зафиксирована.",
        ),
        ContextScenarioTurn(
            user = "Курьер должен видеть карту, список точек и менять статусы: в пути, доставлено, проблема.",
            assistant = "Функции курьера добавлены.",
        ),
        ContextScenarioTurn(
            user = "Менеджер должен назначать заказы курьерам и видеть задержки.",
            assistant = "Функции менеджера добавлены.",
        ),
        ContextScenarioTurn(
            user = "Предпочтение: интерфейс на русском, Material 3, темная тема обязательна.",
            assistant = "UI-предпочтения сохранены.",
        ),
        ContextScenarioTurn(
            user = "Срок: MVP нужно показать через 6 недель.",
            assistant = "Срок MVP зафиксирован.",
        ),
        ContextScenarioTurn(
            user = "Метрики успеха: время доставки, доля проблемных заказов, crash-free sessions.",
            assistant = "Метрики успеха добавлены.",
        ),
        ContextScenarioTurn(
            user = "Нельзя отправлять реальные персональные данные в LLM.",
            assistant = "Ограничение приватности зафиксировано.",
        ),
        ContextScenarioTurn(
            user = "Договоренность: сначала делаем только Android, сервер считаем существующим REST API.",
            assistant = "Договоренность по платформе и API сохранена.",
        ),
    )

private val CONTEXT_REQUIREMENTS_BRANCH_A =
    listOf(
        ContextScenarioTurn(
            user = "Ветка A: делаем максимально узкий MVP только для курьера.",
            assistant = "Ветка A ограничена курьерским MVP.",
        ),
        ContextScenarioTurn(
            user = "Решение ветки A: менеджерские функции заменяем демо-данными.",
            assistant = "Для ветки A менеджерские функции заменены демо-данными.",
        ),
    )

private val CONTEXT_REQUIREMENTS_BRANCH_B =
    listOf(
        ContextScenarioTurn(
            user = "Ветка B: делаем пилот для менеджера и курьера одновременно.",
            assistant = "Ветка B расширена до менеджера и курьера.",
        ),
        ContextScenarioTurn(
            user = "Решение ветки B: добавляем экран задержек и фильтр по складу.",
            assistant = "Для ветки B добавлены задержки и фильтр по складу.",
        ),
    )

private const val CONTEXT_SCENARIO_FINAL_PROMPT =
    "Собери итоговое ТЗ в разделах: цель, роли, MVP, ограничения, решения, метрики, риски. " +
        "Не придумывай отсутствующие детали."
