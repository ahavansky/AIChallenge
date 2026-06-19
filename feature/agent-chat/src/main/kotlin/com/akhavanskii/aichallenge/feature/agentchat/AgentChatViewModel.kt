package com.akhavanskii.aichallenge.feature.agentchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akhavanskii.aichallenge.core.network.GeminiResult
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
                        val memory = snapshot.memory.withLongTermMarkdown(longTermMemory)
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
                AgentChatAction.SaveLongTermMemory -> saveLongTermMemory()
                AgentChatAction.Stop -> stopActiveRequest()
                AgentChatAction.Submit -> submit()
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
                    text = "Waiting for Gemini",
                    isLoading = true,
                )
            val preparedPrompt =
                AgentChatMemoryPromptBuilder.build(
                    latestUserMessage = prompt,
                    chatMessages = currentState.messages,
                    memory = currentState.memory,
                    userProfile = currentState.activeProfile,
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
                        compareResults = emptyList(),
                    )
                }
            persistHistory(updatedState)

            launchActiveRequest { requestId ->
                when (
                    val result =
                        llmAgent.sendMessage(
                            messages = preparedPrompt.messages,
                            systemInstruction = preparedPrompt.systemInstruction,
                        )
                ) {
                    is GeminiResult.Success -> {
                        if (isCurrentRequest(requestId)) {
                            replaceLoadingMessage(result.value)
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

        private companion object {
            const val PROFILE_COMPARE_LIMIT = 3
        }
    }
