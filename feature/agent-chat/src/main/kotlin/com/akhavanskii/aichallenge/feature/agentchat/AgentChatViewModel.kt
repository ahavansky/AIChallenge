package com.akhavanskii.aichallenge.feature.agentchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akhavanskii.aichallenge.core.network.AgentMessage
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
        private val invariantStore: AgentChatInvariantStore,
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
                val invariants = invariantStore.load()
                mutableUiState.update { current ->
                    if (current == AgentChatUiState()) {
                        val memory =
                            snapshot.memory
                                .restoreInterruptedTask()
                                .withLongTermMarkdown(longTermMemory)
                        val profiles = profileSnapshot.normalized
                        current.copy(
                            selectedModel = snapshot.selectedModel,
                            messages = snapshot.messages,
                            memory = memory,
                            taskContextInput = memory.taskContext.toEditableText(),
                            profiles = profiles.profiles,
                            activeProfileId = profiles.activeProfileId,
                            profileInput = profiles.activeProfile.toEditableText(),
                            invariants = invariants,
                            invariantsInput = invariants.markdown,
                            isLongTermMemoryDirty = false,
                            isInvariantsDirty = false,
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
                is AgentChatAction.ModelChanged -> onModelChanged(action.model)
                is AgentChatAction.LongTermMemoryChanged -> onLongTermMemoryChanged(action.markdown)
                is AgentChatAction.InvariantsChanged -> onInvariantsChanged(action.markdown)
                is AgentChatAction.ProfileChanged -> onProfileChanged(action.profileId)
                is AgentChatAction.ProfileInputChanged -> onProfileInputChanged(action.input)
                is AgentChatAction.TaskContextChanged -> onTaskContextChanged(action.input)
                AgentChatAction.ClearChat -> clearChat()
                AgentChatAction.ClearTaskContext -> clearTaskContext()
                AgentChatAction.CompareProfiles -> compareProfiles()
                AgentChatAction.AcceptValidation -> acceptValidation()
                AgentChatAction.ApprovePlan -> approvePlan()
                AgentChatAction.PauseTask -> pauseTask()
                AgentChatAction.RequestExecutionRevision -> requestExecutionRevision()
                AgentChatAction.RequestPlanRevision -> requestPlanRevision()
                AgentChatAction.ResetTask -> resetTask()
                AgentChatAction.ResumeTask -> resumeTask()
                AgentChatAction.RetryTask -> retryTask()
                AgentChatAction.SaveInvariants -> saveInvariants()
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

        private fun onModelChanged(model: AgentChatModelOption) {
            val updatedState =
                mutableUiState.updateAndGet { current ->
                    if (current.isLoading) current else current.copy(selectedModel = model)
                }
            persistHistory(updatedState)
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

        private fun onInvariantsChanged(markdown: String) {
            mutableUiState.update { current ->
                if (current.isLoading) {
                    current
                } else {
                    current.copy(
                        invariants = current.invariants.copy(markdown = markdown),
                        invariantsInput = markdown,
                        isInvariantsDirty = true,
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

        private fun saveInvariants() {
            val invariantsToSave = mutableUiState.value.invariants
            viewModelScope.launch {
                invariantStore.save(invariantsToSave)
                mutableUiState.update { current ->
                    if (current.invariants == invariantsToSave) {
                        current.copy(isInvariantsDirty = false)
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
            val invariantCheck = AgentChatInvariantChecker.check(prompt, currentState.invariants)
            if (invariantCheck.hasHardViolations) {
                appendInvariantRefusal(
                    prompt = prompt,
                    violations = invariantCheck.hardViolations,
                )
                return
            }
            recordInvariantCheck(
                invariantCheckSnapshot(
                    status = AgentChatInvariantCheckStatus.PASSED,
                    stage = AgentChatInvariantCheckStage.PRE_FLIGHT,
                    artifactStored = false,
                ),
            )

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
            if (!transition.isAccepted) {
                appendLocalError(transition.errorMessage.orEmpty())
                return
            }

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

        private fun approvePlan() {
            continueTaskAfterUserDecision(
                event = AgentTaskEvent.ApprovePlan,
                loadingText = "Plan approved. Continuing execution.",
            )
        }

        private fun requestPlanRevision() {
            continueTaskAfterUserDecision(
                event = AgentTaskEvent.RequestPlanRevision,
                loadingText = "Revising task plan.",
            )
        }

        private fun acceptValidation() {
            continueTaskAfterUserDecision(
                event = AgentTaskEvent.AcceptValidation,
                loadingText = "Validation accepted. Producing final answer.",
            )
        }

        private fun requestExecutionRevision() {
            continueTaskAfterUserDecision(
                event = AgentTaskEvent.RequestExecutionRevision,
                loadingText = "Revising execution draft.",
            )
        }

        private fun resetTask() {
            val currentState = mutableUiState.value
            val transition =
                AgentTaskStateMachine.reduce(
                    state = currentState.memory.taskState,
                    event = AgentTaskEvent.Reset,
                )
            if (!transition.isAccepted) {
                appendLocalError(transition.errorMessage.orEmpty())
                return
            }
            activeRequestId += 1
            activeRequestJob?.cancel()
            activeRequestJob = null
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
            continueTaskAfterUserDecision(
                event = event,
                loadingText = loadingText,
            )
        }

        private fun continueTaskAfterUserDecision(
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
                        invariants = currentState.invariants,
                        userProfile = currentState.activeProfile,
                        selection =
                            AgentChatMemorySelection(
                                includeChatHistory = false,
                                includeInvariants = true,
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
                        sendInvariantCheckedMessage(
                            preparedPrompt = preparedPrompt,
                            invariants = currentState.invariants,
                            model = currentState.selectedModel,
                            outputLabel = artifactType.title,
                        )
                ) {
                    is InvariantCheckedLlmResult.Success -> {
                        if (!isCurrentRequest(requestId)) return
                        val transition =
                            AgentTaskStateMachine.reduce(
                                state = mutableUiState.value.memory.taskState,
                                event =
                                    AgentTaskEvent.StepSucceeded(
                                        AgentTaskArtifact(
                                            type = artifactType,
                                            text = result.text,
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

                        if (handlePipelineCheckpoint(transition.state)) {
                            return
                        }
                    }
                    is InvariantCheckedLlmResult.Failure -> {
                        if (!isCurrentRequest(requestId)) return
                        failTaskStep(result.message)
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
                            latestUserMessage = branch.buildPrompt(taskState.stage),
                            chatMessages = emptyList(),
                            memory = currentState.memory,
                            invariants = currentState.invariants,
                            userProfile = currentState.activeProfile,
                            selection =
                                AgentChatMemorySelection(
                                    includeChatHistory = false,
                                    includeInvariants = true,
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
                                    sendInvariantCheckedMessage(
                                        preparedPrompt = request.preparedPrompt,
                                        invariants = currentState.invariants,
                                        model = currentState.selectedModel,
                                        outputLabel = request.branch.expectedArtifactType.title,
                                    )
                                when (result) {
                                    is InvariantCheckedLlmResult.Success ->
                                        PlanningBranchResult(
                                            branchId = request.branch.id,
                                            artifact =
                                                AgentTaskArtifact(
                                                    type = request.branch.expectedArtifactType,
                                                    text = result.text,
                                                ),
                                        )
                                    is InvariantCheckedLlmResult.Failure ->
                                        PlanningBranchResult(
                                            branchId = request.branch.id,
                                            errorMessage = result.message,
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
            val invariantCheck = AgentChatInvariantChecker.check(prompt, currentState.invariants)
            if (invariantCheck.hasHardViolations) {
                recordInvariantCheck(
                    invariantCheckSnapshot(
                        status = AgentChatInvariantCheckStatus.BLOCKED,
                        stage = AgentChatInvariantCheckStage.PRE_FLIGHT,
                        violations = invariantCheck.hardViolations,
                    ),
                )
                appendLocalError(AgentChatInvariantChecker.formatRefusal(invariantCheck.hardViolations))
                return
            }
            recordInvariantCheck(
                invariantCheckSnapshot(
                    status = AgentChatInvariantCheckStatus.PASSED,
                    stage = AgentChatInvariantCheckStage.PRE_FLIGHT,
                    artifactStored = false,
                ),
            )
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
                            invariants = updatedState.invariants,
                            userProfile = profile,
                            selection =
                                AgentChatMemorySelection(
                                    includeChatHistory = true,
                                    includeInvariants = true,
                                    includeTaskContext = true,
                                    includeLongTermMarkdown = true,
                                ),
                        )
                    val result =
                        sendInvariantCheckedMessage(
                            preparedPrompt = preparedPrompt,
                            invariants = updatedState.invariants,
                            model = updatedState.selectedModel,
                            outputLabel = "profile comparison result",
                        )
                    if (!isCurrentRequest(requestId)) return@launchActiveRequest

                    when (result) {
                        is InvariantCheckedLlmResult.Success ->
                            replaceCompareResult(
                                profileId = profile.id,
                                text = result.text,
                            )
                        is InvariantCheckedLlmResult.Failure ->
                            replaceCompareResult(
                                profileId = profile.id,
                                text = result.message,
                                isError = true,
                            )
                    }
                }
            }
        }

        private suspend fun sendInvariantCheckedMessage(
            preparedPrompt: AgentChatPreparedPrompt,
            invariants: AgentChatInvariantSet,
            model: AgentChatModelOption,
            outputLabel: String,
        ): InvariantCheckedLlmResult {
            val promptLayerIncluded = preparedPrompt.requestContext.includedLayers.contains(AgentChatMemoryLayer.INVARIANTS)
            val firstResult =
                llmAgent.sendMessage(
                    messages = preparedPrompt.messages,
                    systemInstruction = preparedPrompt.systemInstruction,
                    modelName = model.modelName,
                )
            val firstText =
                when (firstResult) {
                    is GeminiResult.Success -> firstResult.value
                    is GeminiResult.Failure -> return InvariantCheckedLlmResult.Failure(firstResult.error.userMessage)
                }
            val firstCheck = AgentChatInvariantChecker.check(firstText, invariants)
            if (!firstCheck.hasHardViolations) {
                recordInvariantCheck(
                    invariantCheckSnapshot(
                        status = AgentChatInvariantCheckStatus.PASSED,
                        stage = AgentChatInvariantCheckStage.MODEL_OUTPUT,
                        artifactStored = true,
                        promptLayerIncluded = promptLayerIncluded,
                    ),
                )
                return InvariantCheckedLlmResult.Success(firstText)
            }

            val repairResult =
                llmAgent.sendMessage(
                    messages =
                        preparedPrompt.messages +
                            AgentMessage.Model(firstText) +
                            AgentMessage.User(
                                AgentChatInvariantChecker.buildRepairPrompt(
                                    violations = firstCheck.hardViolations,
                                    outputLabel = outputLabel,
                                ),
                            ),
                    systemInstruction = preparedPrompt.systemInstruction,
                    modelName = model.modelName,
                )
            val repairedText =
                when (repairResult) {
                    is GeminiResult.Success -> repairResult.value
                    is GeminiResult.Failure -> {
                        recordInvariantCheck(
                            invariantCheckSnapshot(
                                status = AgentChatInvariantCheckStatus.FAILED,
                                stage = AgentChatInvariantCheckStage.REPAIR,
                                violations = firstCheck.hardViolations,
                                repairAttempted = true,
                                artifactStored = false,
                                promptLayerIncluded = promptLayerIncluded,
                            ),
                        )
                        return InvariantCheckedLlmResult.Failure(repairResult.error.userMessage)
                    }
                }
            val repairedCheck = AgentChatInvariantChecker.check(repairedText, invariants)
            return if (repairedCheck.hasHardViolations) {
                recordInvariantCheck(
                    invariantCheckSnapshot(
                        status = AgentChatInvariantCheckStatus.FAILED,
                        stage = AgentChatInvariantCheckStage.REPAIR,
                        violations = repairedCheck.hardViolations,
                        repairAttempted = true,
                        artifactStored = false,
                        promptLayerIncluded = promptLayerIncluded,
                    ),
                )
                InvariantCheckedLlmResult.Failure(
                    AgentChatInvariantChecker.formatOutputFailure(repairedCheck.hardViolations),
                )
            } else {
                recordInvariantCheck(
                    invariantCheckSnapshot(
                        status = AgentChatInvariantCheckStatus.REPAIRED,
                        stage = AgentChatInvariantCheckStage.REPAIR,
                        violations = firstCheck.hardViolations,
                        repairAttempted = true,
                        artifactStored = true,
                        promptLayerIncluded = promptLayerIncluded,
                    ),
                )
                InvariantCheckedLlmResult.Success(repairedText)
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

        private fun handlePipelineCheckpoint(taskState: AgentTaskState): Boolean =
            when (taskState.status) {
                AgentTaskStatus.DONE -> {
                    replaceLoadingMessage(
                        taskState.finalAnswer
                            .orEmpty()
                            .ifBlank { "Task completed." },
                    )
                    true
                }
                AgentTaskStatus.WAITING_FOR_USER -> {
                    replaceLoadingMessage(taskState.waitingForUserMessage())
                    true
                }
                else -> false
            }

        private fun AgentTaskState.waitingForUserMessage(): String =
            when (waitingReason) {
                AgentTaskWaitingReason.PLAN_APPROVAL ->
                    "Task plan is ready for review. Approve the plan to continue execution, or request a plan revision."
                AgentTaskWaitingReason.VALIDATION_APPROVAL ->
                    when (validationOutcome) {
                        AgentValidationOutcome.PASS ->
                            "Validation passed. Accept validation to produce the final answer, or request an execution revision."
                        AgentValidationOutcome.NEEDS_REVISION ->
                            "Validation found required fixes. Request an execution revision before producing the final answer."
                        AgentValidationOutcome.BLOCKED ->
                            "Validation is blocked. Review the validation report and request an execution revision."
                        AgentValidationOutcome.UNKNOWN ->
                            "Validation needs review, but no structured PASS outcome was found. Request an execution revision."
                    }
                AgentTaskWaitingReason.NONE -> expectedActionTitle
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

        private fun recordInvariantCheck(snapshot: AgentChatInvariantCheckSnapshot) {
            mutableUiState.update { state ->
                if (
                    snapshot.status == AgentChatInvariantCheckStatus.PASSED &&
                    snapshot.stage == AgentChatInvariantCheckStage.MODEL_OUTPUT &&
                    state.lastInvariantCheck.status == AgentChatInvariantCheckStatus.REPAIRED
                ) {
                    state
                } else {
                    state.copy(lastInvariantCheck = snapshot)
                }
            }
        }

        private fun invariantCheckSnapshot(
            status: AgentChatInvariantCheckStatus,
            stage: AgentChatInvariantCheckStage,
            violations: List<AgentChatInvariantViolation> = emptyList(),
            repairAttempted: Boolean = false,
            artifactStored: Boolean = false,
            promptLayerIncluded: Boolean = false,
        ): AgentChatInvariantCheckSnapshot {
            val violation = violations.firstOrNull()
            val invariant = violation?.invariant
            return AgentChatInvariantCheckSnapshot(
                status = status,
                stage = stage,
                invariantTitle = invariant?.title.orEmpty(),
                conflict = violation?.matchedText.orEmpty(),
                reason = invariant?.reason.orEmpty(),
                alternative = invariant?.alternative.orEmpty(),
                repairAttempted = repairAttempted,
                artifactStored = artifactStored,
                promptLayerIncluded = promptLayerIncluded,
            )
        }

        private fun appendInvariantRefusal(
            prompt: String,
            violations: List<AgentChatInvariantViolation>,
        ) {
            val updatedState =
                mutableUiState.updateAndGet { state ->
                    state.copy(
                        input = "",
                        lastInvariantCheck =
                            invariantCheckSnapshot(
                                status = AgentChatInvariantCheckStatus.BLOCKED,
                                stage = AgentChatInvariantCheckStage.PRE_FLIGHT,
                                violations = violations,
                                artifactStored = false,
                            ),
                        messages =
                            state.messages +
                                AgentChatMessage(role = AgentChatRole.USER, text = prompt) +
                                AgentChatMessage(
                                    role = AgentChatRole.MODEL,
                                    text = AgentChatInvariantChecker.formatRefusal(violations),
                                    isError = true,
                                ),
                    )
                }
            persistHistory(updatedState)
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
                selectedModel = selectedModel,
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

        private sealed interface InvariantCheckedLlmResult {
            data class Success(
                val text: String,
            ) : InvariantCheckedLlmResult

            data class Failure(
                val message: String,
            ) : InvariantCheckedLlmResult
        }

        private data class PlanningBranchResult(
            val branchId: AgentTaskBranchId,
            val artifact: AgentTaskArtifact? = null,
            val errorMessage: String? = null,
        )

        private companion object {
            const val PROFILE_COMPARE_LIMIT = 3
        }
    }
