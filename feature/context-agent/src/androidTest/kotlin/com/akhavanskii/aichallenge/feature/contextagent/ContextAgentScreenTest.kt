package com.akhavanskii.aichallenge.feature.contextagent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.akhavanskii.aichallenge.core.designsystem.AIChallengeTheme
import org.junit.Rule
import org.junit.Test

class ContextAgentScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sendButtonIsDisabledUntilInputHasText() {
        var state by mutableStateOf(ContextAgentUiState())
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                ContextAgentScreen(
                    state = state,
                    onAction = { action ->
                        if (action is ContextAgentAction.InputChanged) {
                            state = state.copy(input = action.input)
                        }
                    },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(ContextAgentTags.SEND_BUTTON).assertIsNotEnabled()
        composeRule.onNodeWithTag(ContextAgentTags.INPUT).performTextInput("Hello")
        composeRule.onNodeWithTag(ContextAgentTags.SEND_BUTTON).assertIsEnabled()
    }

    @Test
    fun compressionStatsAndComparisonMessageAreDisplayed() {
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                ContextAgentScreen(
                    state =
                        ContextAgentUiState(
                            contextState =
                                ContextCompressionState(
                                    summary = "Older turns are summarized.",
                                    summarizedMessageCount = 10,
                                    latestStats =
                                        ContextCompressionStats(
                                            fullPromptTokens = 1_000,
                                            compressedPromptTokens = 360,
                                            savedPromptTokens = 640,
                                            savedPromptPercent = 64,
                                            summarizedMessageCount = 10,
                                            rawMessageCount = 8,
                                            requestMessageCount = 9,
                                        ),
                                ),
                            messages =
                                listOf(
                                    ContextAgentMessage(
                                        role = ContextAgentRole.MODEL,
                                        text = "Quality comparison\n\nCompressed answer kept the important facts.",
                                        includeInContext = false,
                                    ),
                                ),
                        ),
                    onAction = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(ContextAgentTags.TOKEN_SAVINGS).assertIsDisplayed()
        composeRule.onNodeWithText("Prompt tokens: 1,000 -> 360, saved 640 (64%).", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(ContextAgentTags.TOKEN_SAVINGS).performClick()
        composeRule.onNodeWithTag(ContextAgentTags.EXPANDED_PANEL).assertIsDisplayed()
        composeRule.onNodeWithText("Stored summary: Older turns are summarized.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(ContextAgentTags.EXPANDED_PANEL_CLOSE).performClick()

        composeRule.onNodeWithText("Quality comparison").assertIsDisplayed()
        composeRule.onNodeWithTag("${ContextAgentTags.MESSAGE}_0").performClick()
        composeRule.onNodeWithTag(ContextAgentTags.EXPANDED_PANEL_BODY).assertIsDisplayed()
        composeRule.onNodeWithText("Compressed answer kept the important facts.", substring = true).assertIsDisplayed()
    }
}
