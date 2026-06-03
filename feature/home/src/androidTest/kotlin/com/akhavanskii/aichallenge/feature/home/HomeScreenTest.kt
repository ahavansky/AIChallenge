package com.akhavanskii.aichallenge.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
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
                                    comparisonState =
                                        if (action.prompt.isBlank()) {
                                            HomeComparisonState.idle()
                                        } else {
                                            HomeComparisonState
                                                .input()
                                        },
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
                            comparisonState = HomeComparisonState.loading(),
                        ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(HomeTags.CONFIGURED_RESULT).performScrollTo()
        composeRule.onNodeWithTag(HomeTags.CONFIGURED_LOADING_INDICATOR).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.BASELINE_RESULT).performScrollTo()
        composeRule.onNodeWithTag(HomeTags.BASELINE_LOADING_INDICATOR).assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.SEND_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun successStateShowsComparisonResponses() {
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                HomeScreen(
                    state =
                        HomeUiState(
                            prompt = "Hello",
                            comparisonState =
                                HomeComparisonState(
                                    configured = ResponsePaneState.Success("A configured answer"),
                                    baseline = ResponsePaneState.Success("A baseline answer"),
                                ),
                        ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(HomeTags.CONFIGURED_RESULT).performScrollTo()
        composeRule.onNodeWithText("With user parameters").assertIsDisplayed()
        composeRule.onNodeWithText("A configured answer").assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.BASELINE_RESULT).performScrollTo()
        composeRule.onNodeWithText("Without parameters").assertIsDisplayed()
        composeRule.onNodeWithText("A baseline answer").assertIsDisplayed()
    }

    @Test
    fun errorStateShowsError() {
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                HomeScreen(
                    state =
                        HomeUiState(
                            prompt = "Hello",
                            comparisonState =
                                HomeComparisonState(
                                    configured = ResponsePaneState.Error("Missing API key"),
                                    baseline = ResponsePaneState.Success("Baseline answer"),
                                ),
                        ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(HomeTags.CONFIGURED_RESULT).performScrollTo()
        composeRule.onNodeWithText("With user parameters").assertIsDisplayed()
        composeRule.onNodeWithText("Missing API key").assertIsDisplayed()
        composeRule.onNodeWithTag(HomeTags.BASELINE_RESULT).performScrollTo()
        composeRule.onNodeWithText("Baseline answer").assertIsDisplayed()
    }

    @Test
    fun parameterExplanationsAreShown() {
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                HomeScreen(
                    state = HomeUiState(),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("generationConfig").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText("Default here: application/json", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Slider range: 0.0-2.0", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("model maximum is model-specific", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onAllNodesWithText("Penalty is not enabled for this model", substring = true)
            .assertCountEquals(2)
    }
}
