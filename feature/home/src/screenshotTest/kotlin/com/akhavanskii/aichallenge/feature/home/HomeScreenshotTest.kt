package com.akhavanskii.aichallenge.feature.home

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.akhavanskii.aichallenge.core.designsystem.AIChallengeTheme
import com.akhavanskii.aichallenge.feature.common.ResponsePaneState
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(showBackground = true, widthDp = 390, heightDp = 900)
@Composable
fun HomeIdleLightScreenshot() {
    AIChallengeTheme(dynamicColor = false) {
        HomeScreen(
            state = HomeUiState(),
            onAction = {},
        )
    }
}

@PreviewTest
@Preview(
    showBackground = true,
    widthDp = 390,
    heightDp = 900,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun HomeIdleDarkScreenshot() {
    AIChallengeTheme(dynamicColor = false) {
        HomeScreen(
            state = HomeUiState(),
            onAction = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 360, heightDp = 900)
@Composable
fun HomeCompactLoadingScreenshot() {
    AIChallengeTheme(dynamicColor = false) {
        HomeScreen(
            state =
                HomeUiState(
                    prompt = "Write a concise Android testing plan.",
                    comparisonState = HomeComparisonState.loading(),
                ),
            onAction = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 390, heightDp = 900)
@Composable
fun HomeResponseScreenshot() {
    AIChallengeTheme(dynamicColor = false) {
        HomeScreen(
            state =
                HomeUiState(
                    prompt = "Explain UDF.",
                    comparisonState =
                        HomeComparisonState(
                            configured = ResponsePaneState.Success("Candidate 1\nState moves down as JSON-friendly bullets."),
                            baseline = ResponsePaneState.Success("State flows down to Compose. User events flow back to the ViewModel."),
                        ),
                ),
            onAction = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 390, heightDp = 900)
@Composable
fun HomeErrorScreenshot() {
    AIChallengeTheme(dynamicColor = false) {
        HomeScreen(
            state =
                HomeUiState(
                    prompt = "Hello",
                    comparisonState =
                        HomeComparisonState(
                            configured = ResponsePaneState.Error("Gemini API key is missing."),
                            baseline = ResponsePaneState.Success("Baseline answer without generationConfig."),
                        ),
                ),
            onAction = {},
        )
    }
}
