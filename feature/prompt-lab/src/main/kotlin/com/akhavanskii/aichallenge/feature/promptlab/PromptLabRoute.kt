package com.akhavanskii.aichallenge.feature.promptlab

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PromptLabRoute(
    onBack: () -> Unit,
    viewModel: PromptLabViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PromptLabScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = onBack,
    )
}
