package com.akhavanskii.aichallenge.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun HuggingFaceLabRoute(
    onBack: () -> Unit,
    viewModel: HuggingFaceLabViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HuggingFaceLabScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = onBack,
    )
}
