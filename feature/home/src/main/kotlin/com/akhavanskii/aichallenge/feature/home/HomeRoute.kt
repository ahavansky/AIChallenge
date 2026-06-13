package com.akhavanskii.aichallenge.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun HomeRoute(
    onOpenAgentChat: () -> Unit,
    onOpenContextAgent: () -> Unit,
    onOpenPromptLab: () -> Unit,
    onOpenTemperatureLab: () -> Unit,
    onOpenHuggingFaceLab: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        onAction = viewModel::onAction,
        onOpenAgentChat = onOpenAgentChat,
        onOpenContextAgent = onOpenContextAgent,
        onOpenPromptLab = onOpenPromptLab,
        onOpenTemperatureLab = onOpenTemperatureLab,
        onOpenHuggingFaceLab = onOpenHuggingFaceLab,
    )
}
