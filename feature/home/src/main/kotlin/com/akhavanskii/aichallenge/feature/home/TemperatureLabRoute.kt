package com.akhavanskii.aichallenge.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun TemperatureLabRoute(
    onBack: () -> Unit,
    viewModel: TemperatureLabViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TemperatureLabScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = onBack,
    )
}
