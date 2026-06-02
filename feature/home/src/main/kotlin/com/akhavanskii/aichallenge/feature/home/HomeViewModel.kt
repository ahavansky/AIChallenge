package com.akhavanskii.aichallenge.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akhavanskii.aichallenge.core.network.GeminiResult
import com.akhavanskii.aichallenge.core.network.GeminiTextClient
import com.akhavanskii.aichallenge.core.utils.normalizedPromptOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val geminiTextClient: GeminiTextClient,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(HomeUiState())
        val uiState: StateFlow<HomeUiState> = mutableUiState.asStateFlow()

        fun onAction(action: HomeAction) {
            when (action) {
                is HomeAction.PromptChanged -> onPromptChanged(action.prompt)
                HomeAction.SubmitPrompt -> submitPrompt()
            }
        }

        private fun onPromptChanged(prompt: String) {
            mutableUiState.update { current ->
                current.copy(
                    prompt = prompt,
                    contentState = if (prompt.isBlank()) HomeContentState.Idle else HomeContentState.Input,
                )
            }
        }

        private fun submitPrompt() {
            val prompt = mutableUiState.value.prompt.normalizedPromptOrNull()
            if (prompt == null) {
                mutableUiState.update {
                    it.copy(contentState = HomeContentState.Error("Enter a prompt before sending."))
                }
                return
            }

            viewModelScope.launch {
                mutableUiState.update { it.copy(prompt = prompt, contentState = HomeContentState.Loading) }
                when (val result = geminiTextClient.generate(prompt)) {
                    is GeminiResult.Success -> {
                        mutableUiState.update {
                            it.copy(contentState = HomeContentState.Success(result.value))
                        }
                    }
                    is GeminiResult.Failure -> {
                        mutableUiState.update {
                            it.copy(contentState = HomeContentState.Error(result.error.userMessage))
                        }
                    }
                }
            }
        }
    }
