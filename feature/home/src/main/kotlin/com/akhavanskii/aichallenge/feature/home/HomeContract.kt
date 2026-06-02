package com.akhavanskii.aichallenge.feature.home

import com.akhavanskii.aichallenge.core.mvvm.UiEvent
import com.akhavanskii.aichallenge.core.mvvm.UiState

data class HomeUiState(
    val prompt: String = "",
    val contentState: HomeContentState = HomeContentState.Idle,
) : UiState {
    val canSend: Boolean
        get() = prompt.isNotBlank() && contentState !is HomeContentState.Loading

    val inputEnabled: Boolean
        get() = contentState !is HomeContentState.Loading
}

sealed interface HomeContentState {
    data object Idle : HomeContentState

    data object Input : HomeContentState

    data object Loading : HomeContentState

    data class Success(
        val response: String,
    ) : HomeContentState

    data class Error(
        val message: String,
    ) : HomeContentState
}

sealed interface HomeAction : UiEvent {
    data class PromptChanged(
        val prompt: String,
    ) : HomeAction

    data object SubmitPrompt : HomeAction
}
