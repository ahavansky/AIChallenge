package com.akhavanskii.aichallenge.feature.home

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.akhavanskii.aichallenge.core.designsystem.AIChallengeTheme
import com.akhavanskii.aichallenge.core.designsystem.ChallengeButton
import com.akhavanskii.aichallenge.core.designsystem.ChallengeTextField
import com.akhavanskii.aichallenge.core.designsystem.ResponsePanel

@Composable
fun HomeScreen(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .padding(WindowInsets.safeDrawing.asPaddingValues()),
    ) {
        val isWide = maxWidth >= 720.dp
        val contentModifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (isWide) 40.dp else 20.dp, vertical = 24.dp)

        if (isWide) {
            Row(
                modifier = contentModifier,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                PromptSection(
                    state = state,
                    onAction = onAction,
                    modifier = Modifier.weight(0.9f),
                )
                ResultSection(
                    contentState = state.contentState,
                    modifier = Modifier.weight(1.1f),
                )
            }
        } else {
            Column(
                modifier = contentModifier,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                PromptSection(
                    state = state,
                    onAction = onAction,
                    modifier = Modifier.fillMaxWidth(),
                )
                ResultSection(
                    contentState = state.contentState,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PromptSection(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "AIChallenge",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Send a prompt to Gemini and review the response.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChallengeTextField(
            value = state.prompt,
            onValueChange = { onAction(HomeAction.PromptChanged(it)) },
            enabled = state.inputEnabled,
            modifier = Modifier.testTag(HomeTags.PROMPT_INPUT),
        )
        ChallengeButton(
            onClick = { onAction(HomeAction.SubmitPrompt) },
            enabled = state.canSend,
            modifier = Modifier.testTag(HomeTags.SEND_BUTTON),
        ) {
            Text("Send")
        }
    }
}

@Composable
private fun ResultSection(
    contentState: HomeContentState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(HomeTags.RESULT_AREA),
        contentAlignment = Alignment.TopStart,
    ) {
        when (contentState) {
            HomeContentState.Idle -> EmptyState("Your Gemini response will appear here.")
            HomeContentState.Input -> EmptyState("Ready to send.")
            HomeContentState.Loading -> LoadingState()
            is HomeContentState.Success ->
                ResponsePanel(
                    title = "Gemini response",
                    body = contentState.response,
                )
            is HomeContentState.Error ->
                ResponsePanel(
                    title = "Request error",
                    body = contentState.message,
                    isError = true,
                )
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    ResponsePanel(
        title = "Response",
        body = text,
    )
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.testTag(HomeTags.LOADING_INDICATOR))
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Waiting for Gemini",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

object HomeTags {
    const val PROMPT_INPUT = "home_prompt_input"
    const val SEND_BUTTON = "home_send_button"
    const val RESULT_AREA = "home_result_area"
    const val LOADING_INDICATOR = "home_loading_indicator"
}

@Preview(showBackground = true, widthDp = 390, heightDp = 760)
@Composable
fun HomeScreenIdlePreview() {
    AIChallengeTheme(dynamicColor = false) {
        HomeScreen(
            state = HomeUiState(),
            onAction = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 760)
@Composable
fun HomeScreenLoadingPreview() {
    AIChallengeTheme(dynamicColor = false) {
        HomeScreen(
            state =
                HomeUiState(
                    prompt = "Summarize Android edge-to-edge layout.",
                    contentState = HomeContentState.Loading,
                ),
            onAction = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 900, heightDp = 520)
@Composable
fun HomeScreenSuccessWidePreview() {
    AIChallengeTheme(dynamicColor = false) {
        HomeScreen(
            state =
                HomeUiState(
                    prompt = "Explain unidirectional data flow.",
                    contentState = HomeContentState.Success("State flows down, events flow up, and the ViewModel owns state transitions."),
                ),
            onAction = {},
        )
    }
}

@Preview(
    showBackground = true,
    widthDp = 390,
    heightDp = 760,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun HomeScreenErrorDarkPreview() {
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
