package com.akhavanskii.aichallenge.feature.agentchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akhavanskii.aichallenge.core.network.AgentMessage
import com.akhavanskii.aichallenge.core.network.GeminiGenerationConfig
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
import javax.inject.Inject

@HiltViewModel
class AgentChatViewModel
    @Inject
    constructor(
        private val llmAgent: LlmAgent,
        private val historyStore: AgentChatHistoryStore,
        private val longTermMemoryStore: AgentChatLongTermMemoryStore,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(AgentChatUiState())
        val uiState: StateFlow<AgentChatUiState> = mutableUiState.asStateFlow()
        private var activeRequestJob: Job? = null
        private var activeRequestId = 0L

        init {
            viewModelScope.launch {
                val snapshot = historyStore.load()
                val longTermMemory = longTermMemoryStore.load()
                mutableUiState.update { current ->
                    if (current == AgentChatUiState()) {
                        val memory = snapshot.memory.withLongTermMarkdown(longTermMemory)
                        current.copy(
                            messages = snapshot.messages,
                            memory = memory,
                            taskContextInput = memory.taskContext.toEditableText(),
                            isLongTermMemoryDirty = false,
                            selectedAgent = snapshot.selectedAgent,
                            customTotalTokenLimit =
                                snapshot.customTotalTokenLimit?.coerceAtMost(snapshot.selectedAgent.totalTokenLimit),
                        )
                    } else {
                        current
                    }
                }
            }
        }

        fun onAction(action: AgentChatAction) {
            when (action) {
                is AgentChatAction.AgentChanged -> onAgentChanged(action.agent)
                is AgentChatAction.InputChanged -> onInputChanged(action.input)
                is AgentChatAction.LongTermMemoryChanged -> onLongTermMemoryChanged(action.markdown)
                is AgentChatAction.ScenarioSelected -> onScenarioSelected(action.scenario)
                is AgentChatAction.TaskContextChanged -> onTaskContextChanged(action.input)
                is AgentChatAction.TokenLimitChanged -> onTokenLimitChanged(action.input)
                AgentChatAction.ClearChat -> clearChat()
                AgentChatAction.ClearTaskContext -> clearTaskContext()
                AgentChatAction.SaveLongTermMemory -> saveLongTermMemory()
                AgentChatAction.Stop -> stopActiveRequest()
                AgentChatAction.Submit -> submit()
            }
        }

        private fun onScenarioSelected(scenario: AgentChatScenario) {
            val current = mutableUiState.value
            if (current.isLoading) return

            val selectedAgent = current.selectedAgent
            val totalTokenLimit = current.scenarioTotalTokenLimit(scenario)
            val startedState =
                mutableUiState.updateAndGet {
                    it.copy(
                        input = "",
                        messages = emptyList(),
                        memory = it.memory.clearTaskContext(),
                        taskContextInput = AgentChatTaskContext().toEditableText(),
                        customTotalTokenLimit =
                            if (scenario.usesDemoBudget) {
                                totalTokenLimit
                            } else {
                                it.customTotalTokenLimit
                            },
                    )
                }
            persistHistory(startedState)

            launchActiveRequest { requestId ->
                runAutoScenario(
                    requestId = requestId,
                    scenario = scenario,
                    selectedAgent = selectedAgent,
                    totalTokenLimit = totalTokenLimit,
                )
            }
        }

        private fun onAgentChanged(agent: AgentChatAgentOption) {
            updateStateAndPersist { current ->
                if (current.canChangeAgent) {
                    current.copy(
                        selectedAgent = agent,
                        customTotalTokenLimit = current.customTotalTokenLimit?.coerceAtMost(agent.totalTokenLimit),
                    )
                } else {
                    current
                }
            }
        }

        private fun onInputChanged(input: String) {
            mutableUiState.update { current ->
                if (current.isLoading) current else current.copy(input = input)
            }
        }

        private fun onTokenLimitChanged(input: String) {
            updateStateAndPersist { current ->
                if (current.isLoading) {
                    current
                } else {
                    current.copy(
                        customTotalTokenLimit = input.toTokenLimitOrNull(current.selectedAgent.totalTokenLimit),
                    )
                }
            }
        }

        private fun onTaskContextChanged(input: String) {
            updateStateAndPersist { current ->
                if (current.isLoading) {
                    current
                } else {
                    current.copy(
                        taskContextInput = input,
                        memory = current.memory.withTaskContext(AgentChatTaskContext.fromEditableText(input)),
                    )
                }
            }
        }

        private fun onLongTermMemoryChanged(markdown: String) {
            mutableUiState.update { current ->
                if (current.isLoading) {
                    current
                } else {
                    current.copy(
                        memory =
                            current.memory.withLongTermMarkdown(
                                current.memory.longTermMarkdown.copy(markdown = markdown),
                            ),
                        isLongTermMemoryDirty = true,
                    )
                }
            }
        }

        private fun saveLongTermMemory() {
            val memoryToSave = mutableUiState.value.memory.longTermMarkdown
            viewModelScope.launch {
                longTermMemoryStore.save(memoryToSave)
                mutableUiState.update { current ->
                    if (current.memory.longTermMarkdown == memoryToSave) {
                        current.copy(isLongTermMemoryDirty = false)
                    } else {
                        current
                    }
                }
            }
        }

        private fun clearChat() {
            updateStateAndPersist { current ->
                if (current.isLoading) {
                    current
                } else {
                    current.copy(
                        input = "",
                        messages = emptyList(),
                        memory = current.memory.copy(lastRequest = null),
                    )
                }
            }
        }

        private fun clearTaskContext() {
            updateStateAndPersist { current ->
                if (current.isLoading) {
                    current
                } else {
                    val emptyTaskContext = AgentChatTaskContext()
                    current.copy(
                        memory = current.memory.withTaskContext(emptyTaskContext),
                        taskContextInput = emptyTaskContext.toEditableText(),
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
                    it.copy(
                        messages =
                            it.messages +
                                AgentChatMessage(
                                    role = AgentChatRole.MODEL,
                                    text = "Enter a message before sending.",
                                    isError = true,
                                ),
                    )
                }
                return
            }

            val userMessage = AgentChatMessage(role = AgentChatRole.USER, text = prompt)
            val loadingMessage =
                AgentChatMessage(
                    role = AgentChatRole.MODEL,
                    text = "Waiting for ${currentState.selectedAgent.title}",
                    isLoading = true,
                )
            val preparedPrompt =
                AgentChatMemoryPromptBuilder.build(
                    latestUserMessage = prompt,
                    chatMessages = currentState.messages,
                    memory = currentState.memory,
                    selection =
                        AgentChatMemorySelection(
                            includeChatHistory = true,
                            includeTaskContext = true,
                            includeLongTermMarkdown = true,
                        ),
                )
            val updatedState =
                mutableUiState.updateAndGet {
                    it.copy(
                        input = "",
                        messages = currentState.messages + userMessage + loadingMessage,
                        memory = currentState.memory.withLastRequest(preparedPrompt.requestContext),
                    )
                }
            persistHistory(updatedState)

            launchActiveRequest { requestId ->
                when (
                    val result =
                        llmAgent.sendMessage(
                            messages = preparedPrompt.messages,
                            modelName = currentState.selectedAgent.modelName,
                            totalTokenLimit = currentState.effectiveTotalTokenLimit,
                        )
                ) {
                    is GeminiResult.Success -> {
                        if (isCurrentRequest(requestId)) {
                            replaceLoadingMessage(result.value, tokenUsage = result.tokenUsage)
                        }
                    }
                    is GeminiResult.Failure -> {
                        if (isCurrentRequest(requestId)) {
                            replaceLoadingMessage(result.error.userMessage, isError = true)
                        }
                    }
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
                    val stoppedState =
                        current.copy(
                            messages =
                                current.messages.replaceLastLoading(
                                    AgentChatMessage(
                                        role = AgentChatRole.MODEL,
                                        text = "Stopped by user.",
                                        isError = true,
                                    ),
                                ),
                        )
                    stoppedState
                }
            persistHistory(updatedState)
        }

        private fun replaceLoadingMessage(
            text: String,
            isError: Boolean = false,
            tokenUsage: GeminiTokenUsage? = null,
        ) {
            val updatedState =
                mutableUiState.updateAndGet { current ->
                    current
                        .copy(
                            messages =
                                current.messages.replaceLastLoading(
                                    AgentChatMessage(
                                        role = AgentChatRole.MODEL,
                                        text = text,
                                        isError = isError,
                                        tokenUsage = tokenUsage,
                                    ),
                                ),
                        )
                }
            persistHistory(updatedState)
        }

        private fun updateStateAndPersist(transform: (AgentChatUiState) -> AgentChatUiState) {
            val updatedState = mutableUiState.updateAndGet(transform)
            persistHistory(updatedState)
        }

        private fun persistHistory(state: AgentChatUiState) {
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
                }
            }
            job.start()
        }

        private fun isCurrentRequest(requestId: Long): Boolean = activeRequestId == requestId

        private fun AgentChatUiState.toHistorySnapshot(): AgentChatHistorySnapshot =
            AgentChatHistorySnapshot(
                messages = messages.filterNot { it.isLoading },
                memory = memory,
                selectedAgent = selectedAgent,
                customTotalTokenLimit = customTotalTokenLimit,
            )

        private fun List<AgentChatMessage>.toAgentMessages(): List<AgentMessage> {
            val agentMessages = mutableListOf<AgentMessage>()
            var pendingUserMessage: AgentChatMessage? = null

            for (message in this) {
                if (message.isLoading || message.isError) {
                    pendingUserMessage = null
                    continue
                }

                when (message.role) {
                    AgentChatRole.USER -> pendingUserMessage = message
                    AgentChatRole.MODEL -> {
                        val userMessage = pendingUserMessage ?: continue
                        agentMessages += AgentMessage.User(userMessage.text)
                        agentMessages += AgentMessage.Model(message.text)
                        pendingUserMessage = null
                    }
                }
            }

            return agentMessages
        }

        private fun List<AgentChatMessage>.replaceLastLoading(message: AgentChatMessage): List<AgentChatMessage> {
            val loadingIndex = indexOfLast { it.isLoading }
            if (loadingIndex == -1) return this
            return toMutableList().apply {
                set(loadingIndex, message)
            }
        }

        private fun String.toTokenLimitOrNull(modelTotalTokenLimit: Int): Int? {
            val digits = filter { it.isDigit() }
            if (digits.isBlank()) return null
            val parsed = digits.toLongOrNull() ?: return modelTotalTokenLimit
            return parsed
                .coerceIn(MIN_CUSTOM_TOKEN_LIMIT.toLong(), modelTotalTokenLimit.toLong())
                .toInt()
        }

        private suspend fun runAutoScenario(
            requestId: Long,
            scenario: AgentChatScenario,
            selectedAgent: AgentChatAgentOption,
            totalTokenLimit: Int,
        ) {
            var overflowNoticeShown = false
            scenario.prompts(totalTokenLimit).forEachIndexed { index, prompt ->
                if (!isCurrentRequest(requestId)) return

                val userPrompt = "Scenario ${scenario.title}, turn ${index + 1}\n$prompt"
                val userMessage = AgentChatMessage(role = AgentChatRole.USER, text = userPrompt)
                val loadingMessage =
                    AgentChatMessage(
                        role = AgentChatRole.MODEL,
                        text = "Waiting for ${selectedAgent.title}",
                        isLoading = true,
                    )
                val requestMessages = mutableUiState.value.messages.toAgentMessages() + AgentMessage.User(userPrompt)
                val updatedState =
                    mutableUiState.updateAndGet { current ->
                        current
                            .copy(messages = current.messages + userMessage + loadingMessage)
                    }
                persistHistory(updatedState)

                when (
                    val result =
                        llmAgent.sendMessage(
                            messages = requestMessages,
                            generationConfig = SCENARIO_GENERATION_CONFIG,
                            modelName = selectedAgent.modelName,
                            totalTokenLimit = scenario.agentTokenLimit(totalTokenLimit),
                        )
                ) {
                    is GeminiResult.Success -> {
                        if (!isCurrentRequest(requestId)) return
                        replaceLoadingMessage(result.value, tokenUsage = result.tokenUsage)
                        if (
                            scenario.usesSlidingWindowBudget &&
                            !overflowNoticeShown &&
                            (
                                result.tokenUsage?.totalTokens?.let { it >= totalTokenLimit } == true ||
                                    result.tokenUsage?.slidingWindowApplied == true
                            )
                        ) {
                            overflowNoticeShown = true
                            appendScenarioNotice(
                                "Full-history budget reached.\n" +
                                    "Without sliding window the next request would fail or lose context. " +
                                    "The following turns continue through the sliding-window strategy, so older facts can disappear.",
                            )
                        }
                    }
                    is GeminiResult.Failure -> {
                        if (!isCurrentRequest(requestId)) return
                        replaceLoadingMessage(result.error.userMessage, isError = true)
                        return
                    }
                }
            }
        }

        private fun appendScenarioNotice(text: String) {
            val updatedState =
                mutableUiState.updateAndGet { current ->
                    current
                        .copy(
                            messages =
                                current.messages +
                                    AgentChatMessage(
                                        role = AgentChatRole.MODEL,
                                        text = text,
                                        isError = true,
                                    ),
                        )
                }
            persistHistory(updatedState)
        }

        private fun AgentChatUiState.scenarioTotalTokenLimit(scenario: AgentChatScenario): Int =
            if (scenario.usesDemoBudget) {
                OVERFLOW_SCENARIO_TOTAL_LIMIT
            } else {
                effectiveTotalTokenLimit
            }

        private companion object {
            const val MIN_CUSTOM_TOKEN_LIMIT = 1
            const val OVERFLOW_SCENARIO_TOTAL_LIMIT = 1_500
            const val SCENARIO_MAX_OUTPUT_TOKENS = 160
            val SCENARIO_GENERATION_CONFIG = GeminiGenerationConfig(maxOutputTokens = SCENARIO_MAX_OUTPUT_TOKENS)
        }
    }

private val AgentChatScenario.usesDemoBudget: Boolean
    get() = this == AgentChatScenario.LONG || this == AgentChatScenario.OVER_MODEL_LIMIT

private val AgentChatScenario.usesSlidingWindowBudget: Boolean
    get() = this == AgentChatScenario.OVER_MODEL_LIMIT

private fun AgentChatScenario.agentTokenLimit(totalTokenLimit: Int): Int? =
    if (this == AgentChatScenario.LONG) {
        null
    } else {
        totalTokenLimit
    }

private fun AgentChatScenario.prompts(totalTokenLimit: Int): List<String> =
    when (this) {
        AgentChatScenario.SHORT ->
            listOf(
                "Start a short two-person conversation about why token budgets matter. Answer in two concise sentences.",
                "Continue that same conversation and mention that every follow-up includes prior context.",
                "Finish the short conversation with one practical rule for keeping token cost low.",
            )
        AgentChatScenario.LONG ->
            listOf(
                longDialogPrompt(
                    turn = 1,
                    totalTokenLimit = totalTokenLimit,
                    instruction = "Start a detailed two-person conversation about designing a token budget dashboard.",
                ),
                longDialogPrompt(
                    turn = 2,
                    totalTokenLimit = totalTokenLimit,
                    instruction = "Continue the conversation and recap all previous decisions before adding one new decision.",
                ),
                longDialogPrompt(
                    turn = 3,
                    totalTokenLimit = totalTokenLimit,
                    instruction = "Continue again, include the running recap, and add risks from long chat history.",
                ),
                longDialogPrompt(
                    turn = 4,
                    totalTokenLimit = totalTokenLimit,
                    instruction = "Continue again, include the running recap, and add how cost grows with each turn.",
                ),
                longDialogPrompt(
                    turn = 5,
                    totalTokenLimit = totalTokenLimit,
                    instruction = "Continue again, include the running recap, and add how sliding window changes memory.",
                ),
                longDialogPrompt(
                    turn = 6,
                    totalTokenLimit = totalTokenLimit,
                    instruction = "Finish the long conversation with a compact checklist and mention the final accumulated context.",
                ),
            )
        AgentChatScenario.OVER_MODEL_LIMIT ->
            listOf(
                overLimitPrompt(
                    turn = 1,
                    totalTokenLimit = totalTokenLimit,
                    instruction =
                        "Start a two-person conversation about model context limits. Mention the active limit and keep the answer concise.",
                ),
                overLimitPrompt(
                    turn = 2,
                    totalTokenLimit = totalTokenLimit,
                    instruction =
                        "Continue the same conversation, explicitly recalling the previous memory anchors before adding one new risk.",
                ),
                overLimitPrompt(
                    turn = 3,
                    totalTokenLimit = totalTokenLimit,
                    instruction =
                        "Continue again and explain what breaks when the full history no longer fits the active model limit.",
                ),
                overLimitPrompt(
                    turn = 4,
                    totalTokenLimit = totalTokenLimit,
                    instruction =
                        "Continue after sliding window trimming and point out which older facts may no longer be visible.",
                ),
            )
    }

private fun longDialogPrompt(
    turn: Int,
    totalTokenLimit: Int,
    instruction: String,
): String =
    "$instruction\n" +
        "Active token limit for this scenario: $totalTokenLimit tokens.\n" +
        "Long-dialog ledger $turn. Carry these anchors forward in the running recap:\n" +
        scenarioMemoryBlock(prefix = "long$turn", wordCount = LONG_DIALOG_MEMORY_WORDS)

private fun overLimitPrompt(
    turn: Int,
    totalTokenLimit: Int,
    instruction: String,
): String =
    "$instruction\n" +
        "Active model limit for this scenario: $totalTokenLimit tokens.\n" +
        "Memory block $turn. Repeat these anchors in your reasoning summary:\n" +
        scenarioMemoryBlock(prefix = "anchor$turn", wordCount = OVER_LIMIT_MEMORY_WORDS)

private fun scenarioMemoryBlock(
    prefix: String,
    wordCount: Int,
): String =
    buildString {
        repeat(wordCount) { index ->
            append(prefix)
            append('_')
            append(index)
            append(' ')
        }
    }

private const val LONG_DIALOG_MEMORY_WORDS = 180
private const val OVER_LIMIT_MEMORY_WORDS = 220
