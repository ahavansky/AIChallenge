package com.akhavanskii.aichallenge.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import com.akhavanskii.aichallenge.core.designsystem.AIChallengeTheme
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sendButtonIsDisabledUntilPromptHasText() {
        var state by mutableStateOf(HomeUiState())
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                HomeScreen(
                    state = state,
                    onAction = { action ->
                        if (action is HomeAction.PromptChanged) {
                            state =
                                state.copy(
                                    prompt = action.prompt,
                                    contentState = if (action.prompt.isBlank()) HomeContentState.Idle else HomeContentState.Input,
                                )
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithTag(HomeTags.SEND_BUTTON).assertIsNotEnabled()
        composeRule.onNodeWithTag(HomeTags.PROMPT_INPUT).performTextInput("Hello")
        composeRule.onNodeWithTag(HomeTags.SEND_BUTTON).assertIsEnabled()
    }

    @Test
    fun loadingStateShowsProgressAndDisablesSend() {
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                HomeScreen(
                    state =
                        HomeUiState(
                            prompt = "Hello",
                            contentState = HomeContentState.Loading,
                        ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(HomeTags.LOADING_INDICATOR).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.SEND_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun successStateShowsResponse() {
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                HomeScreen(
                    state =
                        HomeUiState(
                            prompt = "Hello",
                            contentState = HomeContentState.Success("A useful answer"),
                        ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("Gemini response").assertIsDisplayed()
        composeRule.onNodeWithText("A useful answer").assertIsDisplayed()
    }

    @Test
    fun errorStateShowsError() {
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                HomeScreen(
                    state =
                        HomeUiState(
                            prompt = "Hello",
                            contentState = HomeContentState.Error("Missing API key"),
                        ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("Request error").assertIsDisplayed()
        composeRule.onNodeWithText("Missing API key").assertIsDisplayed()
    }
}
