package com.akhavanskii.aichallenge.feature.contextagent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ContextAgentRoute(
    onBack: () -> Unit,
    viewModel: ContextAgentViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ContextAgentScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = onBack,
    )
}
