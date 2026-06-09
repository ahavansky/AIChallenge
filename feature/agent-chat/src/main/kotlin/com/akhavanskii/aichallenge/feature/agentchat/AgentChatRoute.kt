package com.akhavanskii.aichallenge.feature.agentchat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AgentChatRoute(
    onBack: () -> Unit,
    viewModel: AgentChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AgentChatScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = onBack,
    )
}
