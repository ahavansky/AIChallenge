package com.akhavanskii.aichallenge.feature.agentchat

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

class AgentChatScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sendButtonIsDisabledUntilInputHasText() {
        var state by mutableStateOf(AgentChatUiState())
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                AgentChatScreen(
                    state = state,
                    onAction = { action ->
                        if (action is AgentChatAction.InputChanged) {
                            state = state.copy(input = action.input)
                        }
                    },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(AgentChatTags.SEND_BUTTON).assertIsNotEnabled()
        composeRule.onNodeWithTag(AgentChatTags.INPUT).performTextInput("Hello")
        composeRule.onNodeWithTag(AgentChatTags.SEND_BUTTON).assertIsEnabled()
    }

    @Test
    fun chatHistoryIsDisplayed() {
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                AgentChatScreen(
                    state =
                        AgentChatUiState(
                            messages =
                                listOf(
                                    AgentChatMessage(role = AgentChatRole.USER, text = "First"),
                                    AgentChatMessage(role = AgentChatRole.MODEL, text = "Answer"),
                                ),
                        ),
                    onAction = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(AgentChatTags.HISTORY).assertIsDisplayed()
        composeRule.onNodeWithText("First").assertIsDisplayed()
        composeRule.onNodeWithText("Answer").assertIsDisplayed()
    }

    @Test
    fun agentSelectionCanChangeBeforeChatStarts() {
        var state by mutableStateOf(AgentChatUiState())
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                AgentChatScreen(
                    state = state,
                    onAction = { action ->
                        if (action is AgentChatAction.AgentChanged) {
                            state = state.copy(selectedAgent = action.agent)
                        }
                    },
                    onBack = {},
                )
            }
        }

        composeRule
            .onNodeWithTag("${AgentChatTags.AGENT_PREFIX}_${AgentChatAgentOption.GEMINI_2_5_FLASH_LITE.name}")
            .performClick()
        composeRule.onNodeWithText(AgentChatAgentOption.GEMINI_2_5_FLASH_LITE.modelName, substring = true).assertIsDisplayed()
    }

    @Test
    fun clearChatUnlocksAgentSelection() {
        var state by
            mutableStateOf(
                AgentChatUiState(
                    selectedAgent = AgentChatAgentOption.GEMINI_2_5_FLASH_LITE,
                    messages = listOf(AgentChatMessage(role = AgentChatRole.USER, text = "First")),
                ),
            )
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                AgentChatScreen(
                    state = state,
                    onAction = { action ->
                        if (action == AgentChatAction.ClearChat) {
                            state = state.copy(messages = emptyList())
                        }
                    },
                    onBack = {},
                )
            }
        }

        composeRule
            .onNodeWithTag("${AgentChatTags.AGENT_PREFIX}_${AgentChatAgentOption.GEMINI_2_5_FLASH.name}")
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(AgentChatTags.CLEAR_BUTTON).assertIsEnabled().performClick()
        composeRule
            .onNodeWithTag("${AgentChatTags.AGENT_PREFIX}_${AgentChatAgentOption.GEMINI_2_5_FLASH.name}")
            .assertIsEnabled()
    }
}
