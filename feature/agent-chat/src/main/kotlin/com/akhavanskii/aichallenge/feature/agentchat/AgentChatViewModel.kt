package com.akhavanskii.aichallenge.feature.agentchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akhavanskii.aichallenge.core.network.AgentMessage
import com.akhavanskii.aichallenge.core.network.GeminiResult
import com.akhavanskii.aichallenge.core.network.LlmAgent
import com.akhavanskii.aichallenge.core.utils.normalizedPromptOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
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
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(AgentChatUiState())
        val uiState: StateFlow<AgentChatUiState> = mutableUiState.asStateFlow()

        init {
            viewModelScope.launch {
                val snapshot = historyStore.load()
                mutableUiState.update { current ->
                    if (current == AgentChatUiState()) {
                        current.copy(
                            messages = snapshot.messages,
                            selectedAgent = snapshot.selectedAgent,
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
                AgentChatAction.ClearChat -> clearChat()
                AgentChatAction.Submit -> submit()
            }
        }

        private fun onAgentChanged(agent: AgentChatAgentOption) {
            updateStateAndPersist { current ->
                if (current.canChangeAgent) current.copy(selectedAgent = agent) else current
            }
        }

        private fun onInputChanged(input: String) {
            mutableUiState.update { current ->
                if (current.isLoading) current else current.copy(input = input)
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
            val requestMessages = currentState.messages.toAgentMessages() + AgentMessage.User(prompt)
            val updatedState =
                mutableUiState.updateAndGet {
                    it.copy(
                        input = "",
                        messages = currentState.messages + userMessage + loadingMessage,
                    )
                }
            persistHistory(updatedState)

            viewModelScope.launch {
                when (
                    val result =
                        llmAgent.sendMessage(
                            messages = requestMessages,
                            modelName = currentState.selectedAgent.modelName,
                        )
                ) {
                    is GeminiResult.Success -> replaceLoadingMessage(result.value)
                    is GeminiResult.Failure -> replaceLoadingMessage(result.error.userMessage, isError = true)
                }
            }
        }

        private fun replaceLoadingMessage(
            text: String,
            isError: Boolean = false,
        ) {
            val updatedState =
                mutableUiState.updateAndGet { current ->
                    current.copy(
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

        private fun AgentChatUiState.toHistorySnapshot(): AgentChatHistorySnapshot =
            AgentChatHistorySnapshot(
                messages = messages.filterNot { it.isLoading },
                selectedAgent = selectedAgent,
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
    }
