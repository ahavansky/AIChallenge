package com.akhavanskii.aichallenge.feature.home

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.akhavanskii.aichallenge.core.designsystem.AIChallengeTheme
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(showBackground = true, widthDp = 390, heightDp = 760)
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
    heightDp = 760,
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
@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
fun HomeCompactLoadingScreenshot() {
    AIChallengeTheme(dynamicColor = false) {
        HomeScreen(
            state =
                HomeUiState(
                    prompt = "Write a concise Android testing plan.",
                    contentState = HomeContentState.Loading,
                ),
            onAction = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 390, heightDp = 760)
@Composable
fun HomeResponseScreenshot() {
    AIChallengeTheme(dynamicColor = false) {
        HomeScreen(
            state =
                HomeUiState(
                    prompt = "Explain UDF.",
                    contentState = HomeContentState.Success("State flows down to Compose. User events flow back to the ViewModel."),
                ),
            onAction = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 390, heightDp = 760)
@Composable
fun HomeErrorScreenshot() {
    AIChallengeTheme(dynamicColor = false) {
        HomeScreen(
            state =
                HomeUiState(
                    prompt = "Hello",
                    contentState = HomeContentState.Error("Gemini API key is missing."),
                ),
            onAction = {},
        )
    }
}
