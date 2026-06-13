package com.akhavanskii.aichallenge.feature.contextagent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
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
    fun conversationScrollsToLatestMessageWhenMessagesChange() {
        val initialMessages =
            List(size = 28) { index ->
                ContextAgentMessage(
                    role = ContextAgentRole.USER,
                    text = "Historical requirement ${index + 1}: keep this card in the long conversation.",
                )
            }
        val newUserMessage = "New user requirement: courier app needs barcode scanning."
        val finalAgentAnswer = "New agent answer: barcode scanning is added to the requirements."
        var state by mutableStateOf(ContextAgentUiState(messages = initialMessages))

        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                Box(modifier = Modifier.size(width = 360.dp, height = 640.dp)) {
                    ContextAgentScreen(
                        state = state,
                        onAction = {},
                        onBack = {},
                    )
                }
            }
        }

        composeRule.runOnIdle {
            state =
                state.copy(
                    messages = initialMessages + ContextAgentMessage(role = ContextAgentRole.USER, text = newUserMessage),
                )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(newUserMessage).assertIsDisplayed()

        composeRule.runOnIdle {
            state =
                state.copy(
                    messages =
                        state.messages +
                            ContextAgentMessage(
                                role = ContextAgentRole.MODEL,
                                text = "Agent is thinking...",
                                isLoading = true,
                            ),
                )
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            state =
                state.copy(
                    messages =
                        state.messages.dropLast(1) +
                            ContextAgentMessage(role = ContextAgentRole.MODEL, text = finalAgentAnswer),
                )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(finalAgentAnswer).assertIsDisplayed()
    }

    @Test
    fun factsStatsAndScenarioComparisonAreDisplayed() {
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                ContextAgentScreen(
                    state =
                        ContextAgentUiState(
                            selectedStrategy = ContextManagementStrategy.STICKY_FACTS,
                            facts =
                                listOf(
                                    ContextFact("goal", "Collect requirements."),
                                    ContextFact("constraints", "No payments."),
                                ),
                            strategyStats =
                                ContextStrategyStats(
                                    strategy = ContextManagementStrategy.STICKY_FACTS,
                                    fullPromptTokens = 1_000,
                                    strategyPromptTokens = 360,
                                    savedPromptTokens = 640,
                                    savedPromptPercent = 64,
                                    storedMessageCount = 12,
                                    requestMessageCount = 9,
                                    droppedMessageCount = 4,
                                    factsCount = 2,
                                ),
                            comparison =
                                ContextScenarioComparison(
                                    reports =
                                        listOf(
                                            ContextScenarioStrategyReport(
                                                strategy = ContextManagementStrategy.STICKY_FACTS,
                                                answer = "Facts answer kept the important constraints.",
                                                promptTokens = 360,
                                                requestMessageCount = 9,
                                                quality = "High",
                                                stability = "High",
                                                tokenUse = "Low",
                                                userConvenience = "Good",
                                            ),
                                        ),
                                    evaluation = "Sticky Facts kept more detail than the window.",
                                ),
                        ),
                    onAction = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(ContextAgentTags.STRATEGY_STATS).assertIsDisplayed()
        composeRule.onNodeWithText("Prompt tokens: 1,000 -> 360", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(ContextAgentTags.FACTS).assertIsDisplayed()
        composeRule.onNodeWithText("- goal: Collect requirements.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(ContextAgentTags.SCENARIO_COMPARISON).assertIsDisplayed()
        composeRule.onNodeWithText("Sticky Facts kept more detail", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Facts answer kept the important constraints.", substring = true).assertIsDisplayed()
    }

    @Test
    fun branchControlsAreDisplayedForBranchingStrategy() {
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                ContextAgentScreen(
                    state =
                        ContextAgentUiState(
                            selectedStrategy = ContextManagementStrategy.BRANCHING,
                            branchingState =
                                ContextBranchingState(
                                    checkpointMessages =
                                        listOf(
                                            ContextAgentMessage(role = ContextAgentRole.USER, text = "Checkpoint"),
                                        ),
                                    hasCheckpoint = true,
                                ),
                        ),
                    onAction = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("${ContextAgentTags.BRANCH_PREFIX}_${ContextBranchId.A.name}").assertIsDisplayed()
        composeRule.onNodeWithTag("${ContextAgentTags.BRANCH_PREFIX}_${ContextBranchId.B.name}").assertIsDisplayed()
        composeRule.onNodeWithTag(ContextAgentTags.CREATE_BRANCHES_BUTTON).assertIsEnabled()
    }
}
