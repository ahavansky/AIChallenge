package com.akhavanskii.aichallenge.feature.ragindexing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun RagIndexingRoute(
    onBack: () -> Unit,
    viewModel: RagIndexingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RagIndexingScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = onBack,
    )
}
