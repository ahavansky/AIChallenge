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
                            contextState = snapshot.contextState,
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
                ContextAgentAction.Clear -> clear()
                is ContextAgentAction.InputChanged -> onInputChanged(action.input)
                is ContextAgentAction.ModelChanged -> onModelChanged(action.model)
                ContextAgentAction.RunComparison -> runComparison()
                ContextAgentAction.Stop -> stopActiveRequest()
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

        private fun submit() {
            val currentState = mutableUiState.value
            if (currentState.isLoading) return

            val prompt = currentState.input.normalizedPromptOrNull()
            if (prompt == null) {
                mutableUiState.update {
                    it.copy(
                        messages =
                            it.messages +
                                ContextAgentMessage(
                                    role = ContextAgentRole.MODEL,
                                    text = "Enter a message before sending.",
                                    isError = true,
                                ),
                    )
                }
                return
            }

            val fullRequestMessages = currentState.messages.toAgentMessages() + AgentMessage.User(prompt)
            val updatedState =
                mutableUiState.updateAndGet {
                    it.copy(
                        input = "",
                        comparison = null,
                        messages =
                            currentState.messages +
                                ContextAgentMessage(role = ContextAgentRole.USER, text = prompt) +
                                ContextAgentMessage(
                                    role = ContextAgentRole.MODEL,
                                    text = "Compressing context for ${currentState.selectedModel.title}",
                                    isLoading = true,
                                ),
                    )
                }
            persistHistory(updatedState)

            launchActiveRequest { requestId ->
                val preparedContext =
                    prepareCompressedContext(
                        requestId = requestId,
                        state = currentState,
                        fullRequestMessages = fullRequestMessages,
                    )
                val readyContext =
                    when (preparedContext) {
                        is PreparedContext.Failure -> {
                            replaceLoadingMessage(
                                text =
                                    "Context compression failed. " +
                                        currentState.selectedModel.contextAgentErrorMessage(preparedContext.error),
                                isError = true,
                            )
                            return@launchActiveRequest
                        }
                        is PreparedContext.Ready -> preparedContext
                    }
                if (!isCurrentRequest(requestId)) return@launchActiveRequest

                when (
                    val result =
                        llmAgent.sendMessage(
                            messages = readyContext.messages,
                            generationConfig = currentState.selectedModel.chatGenerationConfig(),
                            modelName = currentState.selectedModel.modelName,
                            totalTokenLimit = currentState.selectedModel.inputTokenLimit,
                        )
                ) {
                    is GeminiResult.Success -> {
                        if (isCurrentRequest(requestId)) {
                            replaceLoadingMessage(result.value, tokenUsage = result.tokenUsage)
                        }
                    }
                    is GeminiResult.Failure -> {
                        if (isCurrentRequest(requestId)) {
                            replaceLoadingMessage(
                                text = currentState.selectedModel.contextAgentErrorMessage(result.error),
                                isError = true,
                            )
                        }
                    }
                }
            }
        }

        private fun runComparison() {
            val currentState = mutableUiState.value
            if (currentState.isLoading) return

            val comparisonPrompt = currentState.input.normalizedPromptOrNull()
            if (comparisonPrompt == null) {
                mutableUiState.update {
                    it.copy(
                        messages =
                            it.messages +
                                ContextAgentMessage(
                                    role = ContextAgentRole.MODEL,
                                    text = "Enter a message before comparing modes.",
                                    isError = true,
                                    includeInContext = false,
                                ),
                    )
                }
                return
            }
            val fullRequestMessages = currentState.messages.toAgentMessages() + AgentMessage.User(comparisonPrompt)
            val updatedState =
                mutableUiState.updateAndGet {
                    it.copy(
                        input = "",
                        comparison = null,
                        messages =
                            currentState.messages +
                                ContextAgentMessage(
                                    role = ContextAgentRole.USER,
                                    text = comparisonPrompt,
                                ) +
                                ContextAgentMessage(
                                    role = ContextAgentRole.MODEL,
                                    text =
                                        "Comparing full-history and compressed-history answers with " +
                                            currentState.selectedModel.title,
                                    isLoading = true,
                                    includeInContext = false,
                                ),
                    )
                }
            persistHistory(updatedState)

            launchActiveRequest { requestId ->
                val fullAnswer =
                    when (
                        val result =
                            llmAgent.sendMessage(
                                messages = fullRequestMessages,
                                generationConfig = currentState.selectedModel.comparisonGenerationConfig(),
                                modelName = currentState.selectedModel.modelName,
                                totalTokenLimit = currentState.selectedModel.inputTokenLimit,
                            )
                    ) {
                        is GeminiResult.Success -> {
                            if (!isCurrentRequest(requestId)) return@launchActiveRequest
                            replaceLoadingMessage(
                                text = "Answer without compression\n\n${result.value}",
                                tokenUsage = result.tokenUsage,
                                includeInContext = false,
                            )
                            result.value
                        }
                        is GeminiResult.Failure -> {
                            replaceLoadingMessage(
                                text = currentState.selectedModel.contextAgentErrorMessage(result.error),
                                isError = true,
                            )
                            return@launchActiveRequest
                        }
                    }

                val preparedContext =
                    prepareCompressedContext(
                        requestId = requestId,
                        state =
                            mutableUiState.value.copy(
                                selectedModel = currentState.selectedModel,
                            ),
                        fullRequestMessages = fullRequestMessages,
                    )
                val readyContext =
                    when (preparedContext) {
                        is PreparedContext.Failure -> {
                            appendMessage(
                                text =
                                    "Context compression failed. " +
                                        currentState.selectedModel.contextAgentErrorMessage(preparedContext.error),
                                isError = true,
                            )
                            return@launchActiveRequest
                        }
                        is PreparedContext.Ready -> preparedContext
                    }
                val compressedAnswer =
                    when (
                        val result =
                            llmAgent.sendMessage(
                                messages = readyContext.messages,
                                generationConfig = currentState.selectedModel.comparisonGenerationConfig(),
                                modelName = currentState.selectedModel.modelName,
                                totalTokenLimit = currentState.selectedModel.inputTokenLimit,
                            )
                    ) {
                        is GeminiResult.Success -> {
                            if (!isCurrentRequest(requestId)) return@launchActiveRequest
                            appendMessage(
                                text = "Answer with compression\n\n${result.value}",
                                tokenUsage = result.tokenUsage,
                                includeInContext = true,
                            )
                            result.value
                        }
                        is GeminiResult.Failure -> {
                            appendMessage(
                                text = currentState.selectedModel.contextAgentErrorMessage(result.error),
                                isError = true,
                            )
                            return@launchActiveRequest
                        }
                    }

                val stats = mutableUiState.value.contextState.latestStats
                val evaluation =
                    when (
                        val result =
                            llmAgent.sendMessage(
                                prompt =
                                    buildQualityComparisonPrompt(
                                        fullAnswer = fullAnswer,
                                        compressedAnswer = compressedAnswer,
                                        stats = stats,
                                    ),
                                generationConfig = currentState.selectedModel.qualityGenerationConfig(),
                                modelName = currentState.selectedModel.modelName,
                                totalTokenLimit = currentState.selectedModel.inputTokenLimit,
                            )
                    ) {
                        is GeminiResult.Success -> result.value
                        is GeminiResult.Failure -> currentState.selectedModel.contextAgentErrorMessage(result.error)
                    }

                val comparison =
                    ContextQualityComparison(
                        fullHistoryAnswer = fullAnswer,
                        compressedHistoryAnswer = compressedAnswer,
                        evaluation = evaluation,
                    )
                val finishedState =
                    mutableUiState.updateAndGet { current ->
                        current.copy(
                            comparison = comparison,
                            messages =
                                current.messages +
                                    ContextAgentMessage(
                                        role = ContextAgentRole.MODEL,
                                        text = "Quality comparison\n\n$evaluation",
                                        includeInContext = false,
                                    ),
                        )
                    }
                persistHistory(finishedState)
            }
        }

        private suspend fun prepareCompressedContext(
            requestId: Long,
            state: ContextAgentUiState,
            fullRequestMessages: List<AgentMessage>,
        ): PreparedContext {
            var contextState = state.contextState
            val rawTailStartIndex = fullRequestMessages.rawTailStartIndex()
            if (contextState.summarizedMessageCount > rawTailStartIndex) {
                contextState = ContextCompressionState()
            }
            val targetSummarizedCount =
                rawTailStartIndex.coerceAtLeast(contextState.summarizedMessageCount)

            while (contextState.summarizedMessageCount < targetSummarizedCount) {
                if (!isCurrentRequest(requestId)) {
                    return PreparedContext.Ready(fullRequestMessages)
                }

                val batchEnd =
                    (contextState.summarizedMessageCount + CONTEXT_AGENT_SUMMARY_BATCH_SIZE)
                        .coerceAtMost(targetSummarizedCount)
                val batch = fullRequestMessages.subList(contextState.summarizedMessageCount, batchEnd)
                when (
                    val summaryResult =
                        llmAgent.sendMessage(
                            prompt =
                                buildSummaryPrompt(
                                    existingSummary = contextState.summary,
                                    messages = batch,
                                ),
                            generationConfig = state.selectedModel.summaryGenerationConfig(),
                            modelName = state.selectedModel.modelName,
                            totalTokenLimit = state.selectedModel.inputTokenLimit,
                        )
                ) {
                    is GeminiResult.Success -> {
                        contextState =
                            contextState.copy(
                                summary = summaryResult.value.trim().ifBlank { contextState.summary },
                                summarizedMessageCount = batchEnd,
                                latestStats = null,
                            )
                        updateContextStateAndPersist(requestId, contextState)
                    }
                    is GeminiResult.Failure -> return PreparedContext.Failure(summaryResult.error)
                }
            }

            val rawMessages = fullRequestMessages.drop(contextState.summarizedMessageCount)
            val compressedMessages =
                if (contextState.summary.isBlank()) {
                    fullRequestMessages
                } else {
                    listOf(AgentMessage.User(contextState.summary.toCompressedPrompt())) + rawMessages
                }
            val fullPromptTokens =
                llmAgent
                    .countTokens(
                        messages = fullRequestMessages,
                        modelName = state.selectedModel.modelName,
                    ).successValueOrNull()
            val compressedPromptTokens =
                llmAgent
                    .countTokens(
                        messages = compressedMessages,
                        modelName = state.selectedModel.modelName,
                    ).successValueOrNull()
            val stats =
                ContextCompressionStats(
                    fullPromptTokens = fullPromptTokens,
                    compressedPromptTokens = compressedPromptTokens,
                    savedPromptTokens =
                        fullPromptTokens?.let { full ->
                            compressedPromptTokens?.let { compressed -> (full - compressed).coerceAtLeast(0) }
                        },
                    savedPromptPercent =
                        fullPromptTokens?.takeIf { it > 0 }?.let { full ->
                            compressedPromptTokens?.let { compressed ->
                                (((full - compressed).coerceAtLeast(0) * 100L) / full).toInt()
                            }
                        },
                    summarizedMessageCount = contextState.summarizedMessageCount,
                    rawMessageCount = rawMessages.size,
                    requestMessageCount = compressedMessages.size,
                )
            updateContextStateAndPersist(requestId, contextState.copy(latestStats = stats))
            return PreparedContext.Ready(compressedMessages)
        }

        private fun clear() {
            updateStateAndPersist { current ->
                if (current.isLoading) {
                    current
                } else {
                    ContextAgentUiState(selectedModel = current.selectedModel)
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
                    current.copy(
                        messages =
                            current.messages.replaceLastLoading(
                                ContextAgentMessage(
                                    role = ContextAgentRole.MODEL,
                                    text = "Stopped by user.",
                                    isError = true,
                                ),
                            ),
                    )
                }
            persistHistory(updatedState)
        }

        private fun replaceLoadingMessage(
            text: String,
            isError: Boolean = false,
            tokenUsage: GeminiTokenUsage? = null,
            includeInContext: Boolean = true,
        ) {
            val updatedState =
                mutableUiState.updateAndGet { current ->
                    current.copy(
                        messages =
                            current.messages.replaceLastLoading(
                                ContextAgentMessage(
                                    role = ContextAgentRole.MODEL,
                                    text = text,
                                    isError = isError,
                                    tokenUsage = tokenUsage,
                                    includeInContext = includeInContext,
                                ),
                            ),
                    )
                }
            persistHistory(updatedState)
        }

        private fun appendMessage(
            text: String,
            tokenUsage: GeminiTokenUsage? = null,
            isError: Boolean = false,
            includeInContext: Boolean = true,
        ) {
            val updatedState =
                mutableUiState.updateAndGet { current ->
                    current.copy(
                        messages =
                            current.messages +
                                ContextAgentMessage(
                                    role = ContextAgentRole.MODEL,
                                    text = text,
                                    tokenUsage = tokenUsage,
                                    isError = isError,
                                    includeInContext = includeInContext,
                                ),
                    )
                }
            persistHistory(updatedState)
        }

        private fun updateContextStateAndPersist(
            requestId: Long,
            contextState: ContextCompressionState,
        ) {
            if (!isCurrentRequest(requestId)) return
            val updatedState =
                mutableUiState.updateAndGet { current ->
                    current.copy(contextState = contextState)
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
                }
            }
            job.start()
        }

        private fun isCurrentRequest(requestId: Long): Boolean = activeRequestId == requestId

        private fun ContextAgentUiState.toHistorySnapshot(): ContextAgentHistorySnapshot =
            ContextAgentHistorySnapshot(
                messages = messages.filterNot { it.isLoading },
                selectedModel = selectedModel,
                contextState = contextState,
                comparison = comparison,
            )

        private fun List<ContextAgentMessage>.toAgentMessages(): List<AgentMessage> {
            val agentMessages = mutableListOf<AgentMessage>()
            var pendingUserMessage: ContextAgentMessage? = null

            for (message in this) {
                if (message.isLoading || message.isError) {
                    pendingUserMessage = null
                    continue
                }
                if (!message.includeInContext) continue

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

        private fun List<ContextAgentMessage>.replaceLastLoading(message: ContextAgentMessage): List<ContextAgentMessage> {
            val loadingIndex = indexOfLast { it.isLoading }
            if (loadingIndex == -1) return this
            return toMutableList().apply {
                set(loadingIndex, message)
            }
        }
    }

private fun ContextAgentModelOption.summaryGenerationConfig(): GeminiGenerationConfig =
    GeminiGenerationConfig(maxOutputTokens = SUMMARY_MAX_OUTPUT_TOKENS, temperature = 0.1)

private fun ContextAgentModelOption.chatGenerationConfig(): GeminiGenerationConfig =
    GeminiGenerationConfig(
        maxOutputTokens =
            if (isGemma) {
                GEMMA_CHAT_MAX_OUTPUT_TOKENS
            } else {
                CHAT_MAX_OUTPUT_TOKENS
            },
    )

private fun ContextAgentModelOption.comparisonGenerationConfig(): GeminiGenerationConfig =
    GeminiGenerationConfig(
        maxOutputTokens =
            if (isGemma) {
                GEMMA_COMPARISON_MAX_OUTPUT_TOKENS
            } else {
                COMPARISON_MAX_OUTPUT_TOKENS
            },
    )

private fun ContextAgentModelOption.qualityGenerationConfig(): GeminiGenerationConfig =
    GeminiGenerationConfig(
        maxOutputTokens =
            if (isGemma) {
                GEMMA_QUALITY_MAX_OUTPUT_TOKENS
            } else {
                QUALITY_MAX_OUTPUT_TOKENS
            },
        temperature = 0.1,
    )

private val ContextAgentModelOption.isGemma: Boolean
    get() = modelName.startsWith("gemma-")

private fun ContextAgentModelOption.contextAgentErrorMessage(error: GeminiNetworkError): String =
    if (this == ContextAgentModelOption.GEMMA_4_31B_IT && error is GeminiNetworkError.Http && error.statusCode == 500) {
        "Gemma 4 31B returned provider HTTP 500 on a small request. " +
            "This looks like model availability or provider instability, not context compression. " +
            "Try Gemini 2.5 Flash, Gemini 2.5 Flash-Lite, or Gemma 4 26B A4B IT."
    } else {
        error.userMessage
    }

private const val SUMMARY_MAX_OUTPUT_TOKENS = 1_024
private const val CHAT_MAX_OUTPUT_TOKENS = 4_096
private const val COMPARISON_MAX_OUTPUT_TOKENS = 4_096
private const val QUALITY_MAX_OUTPUT_TOKENS = 2_048
private const val GEMMA_CHAT_MAX_OUTPUT_TOKENS = 1_024
private const val GEMMA_COMPARISON_MAX_OUTPUT_TOKENS = 1_024
private const val GEMMA_QUALITY_MAX_OUTPUT_TOKENS = 1_024

private sealed interface PreparedContext {
    data class Ready(
        val messages: List<AgentMessage>,
    ) : PreparedContext

    data class Failure(
        val error: GeminiNetworkError,
    ) : PreparedContext
}

private fun List<AgentMessage>.rawTailStartIndex(): Int {
    if (size <= CONTEXT_AGENT_RECENT_MESSAGE_COUNT) return 0
    val defaultStart = size - CONTEXT_AGENT_RECENT_MESSAGE_COUNT
    return if (getOrNull(defaultStart) is AgentMessage.Model) {
        (defaultStart - 1).coerceAtLeast(0)
    } else {
        defaultStart
    }
}

private fun buildSummaryPrompt(
    existingSummary: String,
    messages: List<AgentMessage>,
): String =
    buildString {
        appendLine("Update the durable conversation summary for a context-compressed Android AI agent.")
        appendLine("Keep facts, goals, constraints, decisions, unresolved questions, and user preferences.")
        appendLine("Remove filler and wording details. Do not invent anything.")
        appendLine("Return only concise bullet points.")
        appendLine()
        appendLine("Existing summary:")
        appendLine(existingSummary.ifBlank { "- No previous summary." })
        appendLine()
        appendLine("New messages to fold into the summary:")
        messages.forEachIndexed { index, message ->
            appendLine("${index + 1}. ${message.summaryRoleLabel()}: ${message.text}")
        }
    }

private fun String.toCompressedPrompt(): String =
    "Summary of older conversation turns. Use it as prior context; the latest messages follow verbatim.\n$this"

private fun AgentMessage.summaryRoleLabel(): String =
    when (this) {
        is AgentMessage.User -> "User"
        is AgentMessage.Model -> "Assistant"
    }

private fun AgentResult<Int>.successValueOrNull(): Int? = (this as? GeminiResult.Success<Int>)?.value

private fun buildQualityComparisonPrompt(
    fullAnswer: String,
    compressedAnswer: String,
    stats: ContextCompressionStats?,
): String =
    buildString {
        appendLine("Compare two answers to the same task.")
        appendLine("Check whether the compressed-history answer preserved important facts from the full-history answer.")
        appendLine("Return concise sections: quality without compression, quality with compression, token use before/after, verdict.")
        appendLine()
        appendLine(
            "Token use: full=${stats?.fullPromptTokens?.toString() ?: "unknown"}, " +
                "compressed=${stats?.compressedPromptTokens?.toString() ?: "unknown"}, " +
                "saved=${stats?.savedPromptTokens?.toString() ?: "unknown"} " +
                "(${stats?.savedPromptPercent?.toString() ?: "unknown"}%).",
        )
        appendLine()
        appendLine("Full-history answer:")
        appendLine(fullAnswer)
        appendLine()
        appendLine("Compressed-history answer:")
        appendLine(compressedAnswer)
    }
