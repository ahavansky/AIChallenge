package com.akhavanskii.aichallenge.feature.common

sealed interface ResponsePaneState {
    data class Empty(
        val message: String,
    ) : ResponsePaneState

    data object Loading : ResponsePaneState

    data class Success(
        val response: String,
    ) : ResponsePaneState

    data class Error(
        val message: String,
    ) : ResponsePaneState
}
