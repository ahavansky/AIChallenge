package com.akhavanskii.aichallenge.feature.agentchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akhavanskii.aichallenge.core.network.GeminiResult
import com.akhavanskii.aichallenge.core.network.LlmAgent
import com.akhavanskii.aichallenge.core.utils.normalizedPromptOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        private val userProfileStore: AgentChatUserProfileStore,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(AgentChatUiState())
        val uiState: StateFlow<AgentChatUiState> = mutableUiState.asStateFlow()
        private var activeRequestJob: Job? = null
        private var activeRequestId = 0L

        init {
            viewModelScope.launch {
                val snapshot = historyStore.load()
                val longTermMemory = longTermMemoryStore.load()
                val profileSnapshot = userProfileStore.load()
                mutableUiState.update { current ->
                    if (current == AgentChatUiState()) {
                        val memory =
                            snapshot.memory
                                .restoreInterruptedTask()
                                .withLongTermMarkdown(longTermMemory)
                        val profiles = profileSnapshot.normalized
                        current.copy(
                            messages = snapshot.messages,
                            memory = memory,
                            taskContextInput = memory.taskContext.toEditableText(),
                            profiles = profiles.profiles,
                            activeProfileId = profiles.activeProfileId,
                            profileInput = profiles.activeProfile.toEditableText(),
                            isLongTermMemoryDirty = false,
                        )
                    } else {
                        current
                    }
                }
            }
        }

        fun onAction(action: AgentChatAction) {
            when (action) {
                is AgentChatAction.InputChanged -> onInputChanged(action.input)
                is AgentChatAction.LongTermMemoryChanged -> onLongTermMemoryChanged(action.markdown)
                is AgentChatAction.ProfileChanged -> onProfileChanged(action.profileId)
                is AgentChatAction.ProfileInputChanged -> onProfileInputChanged(action.input)
                is AgentChatAction.TaskContextChanged -> onTaskContextChanged(action.input)
                AgentChatAction.ClearChat -> clearChat()
                AgentChatAction.ClearTaskContext -> clearTaskContext()
                AgentChatAction.CompareProfiles -> compareProfiles()
                AgentChatAction.PauseTask -> pauseTask()
                AgentChatAction.ResetTask -> resetTask()
                AgentChatAction.ResumeTask -> resumeTask()
                AgentChatAction.RetryTask -> retryTask()
                AgentChatAction.SaveLongTermMemory -> saveLongTermMemory()
                AgentChatAction.StartTask -> startTask()
                AgentChatAction.Stop -> stopActiveRequest()
            }
        }

        private fun onInputChanged(input: String) {
            mutableUiState.update { current ->
                if (current.isLoading) current else current.copy(input = input)
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

        private fun onProfileChanged(profileId: String) {
            val updatedState =
                mutableUiState.updateAndGet { current ->
                    if (current.isLoading) {
                        current
                    } else {
                        val snapshot = current.toProfileSnapshot().withActiveProfile(profileId)
                        current.copy(
                            profiles = snapshot.profiles,
                            activeProfileId = snapshot.activeProfileId,
                            profileInput = snapshot.activeProfile.toEditableText(),
                            compareResults = emptyList(),
                        )
                    }
                }
            persistProfiles(updatedState)
        }

        private fun onProfileInputChanged(input: String) {
            val updatedState =
                mutableUiState.updateAndGet { current ->
                    if (current.isLoading) {
                        current
                    } else {
                        val activeProfile = current.activeProfile
                        val updatedProfile =
                            AgentChatUserProfile.fromEditableText(
                                id = activeProfile.id,
                                fallbackTitle = activeProfile.title,
                                text = input,
                            )
                        val snapshot = current.toProfileSnapshot().withUpdatedActiveProfile(updatedProfile)
                        current.copy(
                            profiles = snapshot.profiles,
                            activeProfileId = snapshot.activeProfileId,
                            profileInput = input,
                            compareResults = emptyList(),
                        )
                    }
                }
            persistProfiles(updatedState)
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
                        compareResults = emptyList(),
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

        private fun startTask() {
            val currentState = mutableUiState.value
            if (!currentState.canRunTask) return

            val prompt = currentState.input.normalizedPromptOrNull()
            if (prompt == null) {
                appendLocalError("Enter a task before running the pipeline.")
                return
            }

            val transition =
                AgentTaskStateMachine.reduce(
                    state = currentState.memory.taskState,
                    event =
                        AgentTaskEvent.Start(
                            taskId = nextTaskId(),
                            prompt = prompt,
                        ),
                )
            if (!transition.isAccepted) {
                appendLocalError(transition.errorMessage.orEmpty())
                return
            }

            val updatedState =
                mutableUiState.updateAndGet {
                    it.copy(
                        input = "",
                        messages =
                            currentState.messages +
                                AgentChatMessage(role = AgentChatRole.USER, text = prompt) +
                                AgentChatMessage(
                                    role = AgentChatRole.MODEL,
                                    text = "Running task pipeline",
                                    isLoading = true,
                                ),
                        memory = currentState.memory.withTaskState(transition.state),
                        compareResults = emptyList(),
                    )
                }
            persistHistory(updatedState)
            launchTaskPipeline()
        }

        private fun pauseTask() {
            val currentState = mutableUiState.value
            val transition =
                AgentTaskStateMachine.reduce(
                    state = currentState.memory.taskState,
                    event = AgentTaskEvent.Pause,
                )
            if (!transition.isAccepted) return

            activeRequestId += 1
            activeRequestJob?.cancel()
            activeRequestJob = null

            val updatedState =
                mutableUiState.updateAndGet { state ->
                    state.copy(
                        messages =
                            state.messages.replaceLastLoading(
                                AgentChatMessage(
                                    role = AgentChatRole.MODEL,
                                    text = "Task paused at ${transition.state.step.title}.",
                                ),
                            ),
                        memory = state.memory.withTaskState(transition.state),
                    )
                }
            persistHistory(updatedState)
        }

        private fun resumeTask() {
            continuePausedTask(
                event = AgentTaskEvent.Resume,
                loadingText = "Continuing task pipeline",
            )
        }

        private fun retryTask() {
            continuePausedTask(
                event = AgentTaskEvent.Retry,
                loadingText = "Retrying failed task step",
            )
        }

        private fun resetTask() {
            val currentState = mutableUiState.value
            val transition =
                AgentTaskStateMachine.reduce(
                    state = currentState.memory.taskState,
                    event = AgentTaskEvent.Reset,
                )
            val updatedState =
                mutableUiState.updateAndGet { state ->
                    state.copy(memory = state.memory.withTaskState(transition.state))
                }
            persistHistory(updatedState)
        }

        private fun continuePausedTask(
            event: AgentTaskEvent,
            loadingText: String,
        ) {
            val currentState = mutableUiState.value
            if (currentState.isLoading) return

            val transition =
                AgentTaskStateMachine.reduce(
                    state = currentState.memory.taskState,
                    event = event,
                )
            if (!transition.isAccepted) {
                appendLocalError(transition.errorMessage.orEmpty())
                return
            }

            val updatedState =
                mutableUiState.updateAndGet { state ->
                    state.copy(
                        messages =
                            state.messages +
                                AgentChatMessage(
                                    role = AgentChatRole.MODEL,
                                    text = loadingText,
                                    isLoading = true,
                                ),
                        memory = state.memory.withTaskState(transition.state),
                        compareResults = emptyList(),
                    )
                }
            persistHistory(updatedState)
            launchTaskPipeline()
        }

        private fun launchTaskPipeline() {
            launchActiveRequest { requestId ->
                runTaskPipeline(requestId)
            }
        }

        private suspend fun runTaskPipeline(requestId: Long) {
            while (isCurrentRequest(requestId)) {
                val currentState = mutableUiState.value
                val taskState = currentState.memory.taskState
                if (taskState.status != AgentTaskStatus.RUNNING) return

                if (taskState.step == AgentTaskStep.PARALLEL_ANALYSIS) {
                    if (!runParallelPlanningStep(requestId)) return
                    continue
                }

                val artifactType = taskState.expectedArtifactType ?: return
                val prompt = taskState.buildCurrentStepPrompt()
                if (prompt.isBlank()) return

                val preparedPrompt =
                    AgentChatMemoryPromptBuilder.build(
                        latestUserMessage = prompt,
                        chatMessages = emptyList(),
                        memory = currentState.memory,
                        userProfile = currentState.activeProfile,
                        selection =
                            AgentChatMemorySelection(
                                includeChatHistory = false,
                                includeTaskState = true,
                                includeTaskContext = true,
                                includeLongTermMarkdown = true,
                            ),
                        taskStage = taskState.stage,
                    )
                val promptState =
                    mutableUiState.updateAndGet { state ->
                        state.copy(memory = state.memory.withLastRequest(preparedPrompt.requestContext))
                    }
                persistHistory(promptState)

                when (
                    val result =
                        llmAgent.sendMessage(
                            messages = preparedPrompt.messages,
                            systemInstruction = preparedPrompt.systemInstruction,
                        )
                ) {
                    is GeminiResult.Success -> {
                        if (!isCurrentRequest(requestId)) return
                        val transition =
                            AgentTaskStateMachine.reduce(
                                state = mutableUiState.value.memory.taskState,
                                event =
                                    AgentTaskEvent.StepSucceeded(
                                        AgentTaskArtifact(
                                            type = artifactType,
                                            text = result.value,
                                        ),
                                    ),
                            )
                        if (!transition.isAccepted) {
                            failTaskStep(transition.errorMessage.orEmpty())
                            return
                        }
                        val updatedState =
                            mutableUiState.updateAndGet { state ->
                                state.copy(memory = state.memory.withTaskState(transition.state))
                            }
                        persistHistory(updatedState)

                        if (transition.state.status == AgentTaskStatus.DONE) {
                            replaceLoadingMessage(
                                transition.state.finalAnswer
                                    .orEmpty()
                                    .ifBlank { "Task completed." },
                            )
                            return
                        }
                    }
                    is GeminiResult.Failure -> {
                        if (!isCurrentRequest(requestId)) return
                        failTaskStep(result.error.userMessage)
                        return
                    }
                }
            }
        }

        private suspend fun runParallelPlanningStep(requestId: Long): Boolean {
            val currentState = mutableUiState.value
            val taskState = currentState.memory.taskState
            val branchesToRun =
                taskState.branches
                    .filter { it.status == AgentTaskBranchStatus.RUNNING }
            if (branchesToRun.isEmpty()) {
                if (taskState.branches.isNotEmpty() && taskState.branches.all { it.status == AgentTaskBranchStatus.DONE }) {
                    val transition =
                        AgentTaskStateMachine.reduce(
                            state = taskState,
                            event = AgentTaskEvent.ParallelBranchesFinished(successfulArtifacts = emptyList()),
                        )
                    if (transition.isAccepted) {
                        val updatedState =
                            mutableUiState.updateAndGet { state ->
                                state.copy(memory = state.memory.withTaskState(transition.state))
                            }
                        persistHistory(updatedState)
                        return true
                    }
                }
                replaceTaskWithFailure("No planning branches available.")
                return false
            }

            val branchRequests =
                branchesToRun.map { branch ->
                    val preparedPrompt =
                        AgentChatMemoryPromptBuilder.build(
                            latestUserMessage = branch.buildPrompt(),
                            chatMessages = emptyList(),
                            memory = currentState.memory,
                            userProfile = currentState.activeProfile,
                            selection =
                                AgentChatMemorySelection(
                                    includeChatHistory = false,
                                    includeTaskState = true,
                                    includeTaskContext = true,
                                    includeLongTermMarkdown = true,
                                ),
                            taskStage = taskState.stage,
                        )
                    PlanningBranchRequest(branch = branch, preparedPrompt = preparedPrompt)
                }
            val promptState =
                mutableUiState.updateAndGet { state ->
                    state.copy(memory = state.memory.withLastRequest(branchRequests.last().preparedPrompt.requestContext))
                }
            persistHistory(promptState)

            val branchResults =
                coroutineScope {
                    branchRequests
                        .map { request ->
                            async {
                                val result =
                                    llmAgent.sendMessage(
                                        messages = request.preparedPrompt.messages,
                                        systemInstruction = request.preparedPrompt.systemInstruction,
                                    )
                                when (result) {
                                    is GeminiResult.Success ->
                                        PlanningBranchResult(
                                            branchId = request.branch.id,
                                            artifact =
                                                AgentTaskArtifact(
                                                    type = request.branch.expectedArtifactType,
                                                    text = result.value,
                                                ),
                                        )
                                    is GeminiResult.Failure ->
                                        PlanningBranchResult(
                                            branchId = request.branch.id,
                                            errorMessage = result.error.userMessage,
                                        )
                                }
                            }
                        }.awaitAll()
                }
            if (!isCurrentRequest(requestId)) return false

            val transition =
                AgentTaskStateMachine.reduce(
                    state = mutableUiState.value.memory.taskState,
                    event =
                        AgentTaskEvent.ParallelBranchesFinished(
                            successfulArtifacts =
                                branchResults.mapNotNull { result ->
                                    result.artifact?.let { artifact ->
                                        AgentTaskBranchArtifact(
                                            branchId = result.branchId,
                                            artifact = artifact,
                                        )
                                    }
                                },
                            failures =
                                branchResults.mapNotNull { result ->
                                    result.errorMessage?.let { message ->
                                        AgentTaskBranchFailure(
                                            branchId = result.branchId,
                                            message = message,
                                        )
                                    }
                                },
                        ),
                )
            if (!transition.isAccepted) {
                replaceTaskWithFailure(transition.errorMessage.orEmpty())
                return false
            }

            val updatedState =
                mutableUiState.updateAndGet { state ->
                    state.copy(memory = state.memory.withTaskState(transition.state))
                }
            persistHistory(updatedState)

            if (transition.state.status == AgentTaskStatus.FAILED) {
                replaceLoadingMessage(
                    "Task failed at ${transition.state.step.title}: ${transition.state.errorMessage}",
                    isError = true,
                )
                return false
            }

            return true
        }

        private fun replaceTaskWithFailure(message: String) {
            val failedState =
                mutableUiState.value.memory.taskState.copy(
                    status = AgentTaskStatus.FAILED,
                    errorMessage = message,
                )
            val updatedState =
                mutableUiState.updateAndGet { state ->
                    state.copy(
                        messages =
                            state.messages.replaceLastLoading(
                                AgentChatMessage(
                                    role = AgentChatRole.MODEL,
                                    text = "Task failed at ${failedState.step.title}: $message",
                                    isError = true,
                                ),
                            ),
                        memory = state.memory.withTaskState(failedState),
                    )
                }
            persistHistory(updatedState)
        }

        private fun failTaskStep(message: String) {
            val currentState = mutableUiState.value
            val transition =
                AgentTaskStateMachine.reduce(
                    state = currentState.memory.taskState,
                    event = AgentTaskEvent.StepFailed(message),
                )
            val failedState = transition.state
            val updatedState =
                mutableUiState.updateAndGet { state ->
                    state.copy(
                        messages =
                            state.messages.replaceLastLoading(
                                AgentChatMessage(
                                    role = AgentChatRole.MODEL,
                                    text = "Task failed at ${failedState.step.title}: $message",
                                    isError = true,
                                ),
                            ),
                        memory = state.memory.withTaskState(failedState),
                    )
                }
            persistHistory(updatedState)
        }

        private fun compareProfiles() {
            val currentState = mutableUiState.value
            if (!currentState.canCompareProfiles) return

            val prompt = currentState.input.normalizedPromptOrNull() ?: return
            val profiles = currentState.profiles.take(PROFILE_COMPARE_LIMIT)
            val updatedState =
                mutableUiState.updateAndGet { current ->
                    current.copy(
                        input = "",
                        compareResults =
                            profiles.map { profile ->
                                AgentChatProfileCompareResult(
                                    profileId = profile.id,
                                    profileTitle = profile.title,
                                    text = "Waiting for Gemini",
                                    isLoading = true,
                                )
                            },
                    )
                }

            launchActiveRequest { requestId ->
                profiles.forEach { profile ->
                    if (!isCurrentRequest(requestId)) return@launchActiveRequest

                    val preparedPrompt =
                        AgentChatMemoryPromptBuilder.build(
                            latestUserMessage = prompt,
                            chatMessages = updatedState.messages,
                            memory = updatedState.memory,
                            userProfile = profile,
                            selection =
                                AgentChatMemorySelection(
                                    includeChatHistory = true,
                                    includeTaskContext = true,
                                    includeLongTermMarkdown = true,
                                ),
                        )
                    val result =
                        llmAgent.sendMessage(
                            messages = preparedPrompt.messages,
                            systemInstruction = preparedPrompt.systemInstruction,
                        )
                    if (!isCurrentRequest(requestId)) return@launchActiveRequest

                    when (result) {
                        is GeminiResult.Success ->
                            replaceCompareResult(
                                profileId = profile.id,
                                text = result.value,
                            )
                        is GeminiResult.Failure ->
                            replaceCompareResult(
                                profileId = profile.id,
                                text = result.error.userMessage,
                                isError = true,
                            )
                    }
                }
            }
        }

        private fun stopActiveRequest() {
            val currentState = mutableUiState.value
            if (!currentState.isLoading) return
            if (currentState.memory.taskState.status == AgentTaskStatus.RUNNING) {
                pauseTask()
                return
            }

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
                            compareResults =
                                current.compareResults.map { result ->
                                    if (result.isLoading) {
                                        result.copy(text = "Stopped by user.", isLoading = false, isError = true)
                                    } else {
                                        result
                                    }
                                },
                        )
                    stoppedState
                }
            persistHistory(updatedState)
        }

        private fun replaceCompareResult(
            profileId: String,
            text: String,
            isError: Boolean = false,
        ) {
            mutableUiState.update { current ->
                current.copy(
                    compareResults =
                        current.compareResults.map { result ->
                            if (result.profileId == profileId) {
                                result.copy(text = text, isLoading = false, isError = isError)
                            } else {
                                result
                            }
                        },
                )
            }
        }

        private fun replaceLoadingMessage(
            text: String,
            isError: Boolean = false,
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

        private fun appendLocalError(message: String) {
            mutableUiState.update { state ->
                state.copy(
                    messages =
                        state.messages +
                            AgentChatMessage(
                                role = AgentChatRole.MODEL,
                                text = message.ifBlank { "Task action is not available." },
                                isError = true,
                            ),
                )
            }
        }

        private fun persistHistory(state: AgentChatUiState) {
            viewModelScope.launch {
                historyStore.save(state.toHistorySnapshot())
            }
        }

        private fun persistProfiles(state: AgentChatUiState) {
            viewModelScope.launch {
                userProfileStore.save(state.toProfileSnapshot())
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

        private fun nextTaskId(): String = "task-${activeRequestId + 1}"

        private fun AgentChatUiState.toHistorySnapshot(): AgentChatHistorySnapshot =
            AgentChatHistorySnapshot(
                messages = messages.filterNot { it.isLoading },
                memory = memory,
            )

        private fun AgentChatUiState.toProfileSnapshot(): AgentChatProfileSnapshot =
            AgentChatProfileSnapshot(
                activeProfileId = activeProfileId,
                profiles = profiles,
            ).normalized

        private fun List<AgentChatMessage>.replaceLastLoading(message: AgentChatMessage): List<AgentChatMessage> {
            val loadingIndex = indexOfLast { it.isLoading }
            if (loadingIndex == -1) return this
            return toMutableList().apply {
                set(loadingIndex, message)
            }
        }

        private data class PlanningBranchRequest(
            val branch: AgentTaskBranch,
            val preparedPrompt: AgentChatPreparedPrompt,
        )

        private data class PlanningBranchResult(
            val branchId: AgentTaskBranchId,
            val artifact: AgentTaskArtifact? = null,
            val errorMessage: String? = null,
        )

        private companion object {
            const val PROFILE_COMPARE_LIMIT = 3
        }
    }
