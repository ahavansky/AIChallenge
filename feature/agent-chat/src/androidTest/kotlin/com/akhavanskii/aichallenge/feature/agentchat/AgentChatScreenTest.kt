package com.akhavanskii.aichallenge.feature.agentchat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.akhavanskii.aichallenge.core.designsystem.AIChallengeTheme
import com.akhavanskii.aichallenge.core.network.GeminiTokenUsage
import org.junit.Assert.assertTrue
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
    fun longDraftKeepsActionButtonsVisible() {
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

        val longDraft =
            (1..20).joinToString(separator = "\n") { line ->
                "Constraint: line $line must stay editable without hiding actions"
            }

        composeRule.onNodeWithTag(AgentChatTags.INPUT).performTextInput(longDraft)

        composeRule.onNodeWithTag(AgentChatTags.SEND_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentChatTags.CLEAR_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentChatTags.STOP_BUTTON).assertIsDisplayed()
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
        composeRule.onNodeWithText("First").fetchSemanticsNode()
        composeRule.onNodeWithText("Answer").fetchSemanticsNode()
    }

    @Test
    fun tokenUsageIsDisplayedInSeparateSummary() {
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                AgentChatScreen(
                    state =
                        AgentChatUiState(
                            messages =
                                listOf(
                                    AgentChatMessage(role = AgentChatRole.USER, text = "First"),
                                    AgentChatMessage(
                                        role = AgentChatRole.MODEL,
                                        text = "Answer",
                                        tokenUsage =
                                            GeminiTokenUsage(
                                                currentRequestTokens = 3,
                                                conversationHistoryTokens = 12,
                                                modelResponseTokens = 5,
                                                totalTokens = 17,
                                            ),
                                    ),
                                ),
                        ),
                    onAction = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(AgentChatTags.TOKEN_USAGE).assertIsDisplayed()
        composeRule.onNodeWithText("Req/history/resp: 3 / 12 / 5", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Total/limit: 17 / 1,114,112 (model), left 1,114,095", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Window: sliding ready | max 1,114,112", substring = true).assertIsDisplayed()
    }

    @Test
    fun memoryLayersSummaryIsDisplayed() {
        val taskContext =
            AgentChatTaskContext(
                goal = "Build memory",
                constraints = listOf("Keep tests offline"),
            )
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                AgentChatScreen(
                    state =
                        AgentChatUiState(
                            messages =
                                listOf(
                                    AgentChatMessage(role = AgentChatRole.USER, text = "Question"),
                                    AgentChatMessage(role = AgentChatRole.MODEL, text = "Answer"),
                                ),
                            taskContextInput = taskContext.toEditableText(),
                            memory =
                                AgentChatMemorySnapshot(
                                    taskContext = taskContext,
                                    longTermMarkdown =
                                        AgentChatLongTermMarkdown(
                                            markdown =
                                                """
                                                # Preferences

                                                - Concise
                                                """.trimIndent(),
                                        ),
                                    lastRequest =
                                        AgentChatMemoryRequestContext(
                                            includedLayers =
                                                listOf(
                                                    AgentChatMemoryLayer.SHORT_TERM,
                                                    AgentChatMemoryLayer.TASK_CONTEXT,
                                                    AgentChatMemoryLayer.LONG_TERM_MARKDOWN,
                                                ),
                                            chatHistoryMessageCount = 2,
                                            taskContextItemCount = 2,
                                            longTermMarkdownChars = 16,
                                        ),
                                ),
                        ),
                    onAction = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(AgentChatTags.MEMORY_LAYERS).assertIsDisplayed()
        composeRule.onNodeWithText("Short-term (2 messages)", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Source: chat history DB", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("User: Question", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Source: TaskContext", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Goal: Build memory", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Constraint: Keep tests offline", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Source: agent_chat_memory.md", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Concise", substring = true).assertIsDisplayed()
        composeRule
            .onNodeWithText("Prompt context: Short-term, TaskContext, Long-term markdown", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(AgentChatTags.TASK_CONTEXT_EDITOR_TOGGLE).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentChatTags.LONG_TERM_MEMORY_EDITOR_TOGGLE).assertIsDisplayed()
        composeRule.onAllNodesWithTag(AgentChatTags.TASK_CONTEXT_INPUT).assertCountEquals(0)
        composeRule.onAllNodesWithTag(AgentChatTags.LONG_TERM_MEMORY_INPUT).assertCountEquals(0)
    }

    @Test
    fun memoryEditorsDispatchActions() {
        var state by mutableStateOf(AgentChatUiState())
        val actions = mutableListOf<AgentChatAction>()
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                AgentChatScreen(
                    state = state,
                    onAction = { action ->
                        actions += action
                        state =
                            when (action) {
                                is AgentChatAction.TaskContextChanged ->
                                    state.copy(
                                        taskContextInput = action.input,
                                        memory = state.memory.withTaskContext(AgentChatTaskContext.fromEditableText(action.input)),
                                    )
                                is AgentChatAction.LongTermMemoryChanged ->
                                    state.copy(
                                        memory =
                                            state.memory.withLongTermMarkdown(
                                                state.memory.longTermMarkdown.copy(markdown = action.markdown),
                                            ),
                                        isLongTermMemoryDirty = true,
                                    )
                                AgentChatAction.SaveLongTermMemory -> state.copy(isLongTermMemoryDirty = false)
                                else -> state
                            }
                    },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(AgentChatTags.TASK_CONTEXT_EDITOR_TOGGLE).performClick()
        composeRule.onNodeWithTag(AgentChatTags.TASK_CONTEXT_INPUT).performScrollTo().performTextInput("\nGoal: Demo")
        composeRule.onNodeWithTag(AgentChatTags.LONG_TERM_MEMORY_EDITOR_TOGGLE).performScrollTo().performClick()
        composeRule.onNodeWithTag(AgentChatTags.LONG_TERM_MEMORY_INPUT).performScrollTo().performTextInput("\n- Answer briefly")
        composeRule
            .onNodeWithTag(AgentChatTags.SAVE_LONG_TERM_MEMORY_BUTTON)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        assertTrue(actions.any { it is AgentChatAction.TaskContextChanged })
        assertTrue(actions.any { it is AgentChatAction.LongTermMemoryChanged })
        assertTrue(actions.contains(AgentChatAction.SaveLongTermMemory))
    }

    @Test
    fun longMessagesAreCollapsedAndExpandOnClick() {
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                AgentChatScreen(
                    state =
                        AgentChatUiState(
                            messages =
                                listOf(
                                    AgentChatMessage(
                                        role = AgentChatRole.MODEL,
                                        text =
                                            """
                                            Plan:
                                            1. **Define** `AgentMessage` memory boundaries.
                                            2. Add working memory fields.
                                            3. Add long-term memory fields.
                                            4. Build request prompt from selected layers.
                                            5. Validate markdown rendering.
                                            """.trimIndent(),
                                    ),
                                ),
                        ),
                    onAction = {},
                    onBack = {},
                )
            }
        }

        val message = composeRule.onNodeWithTag("${AgentChatTags.MESSAGE}_0")
        message.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        composeRule.onNodeWithText("Define AgentMessage", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("**Define**", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("`AgentMessage`", substring = true).assertCountEquals(0)

        message.performClick()

        message.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        composeRule.onNodeWithText("5.", substring = true).assertIsDisplayed()
    }

    @Test
    fun profileComparisonResultsAreCollapsedAndExpandOnClick() {
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                AgentChatScreen(
                    state =
                        AgentChatUiState(
                            compareResults =
                                listOf(
                                    AgentChatProfileCompareResult(
                                        profileId = SENIOR_KOTLIN_PROFILE_ID,
                                        profileTitle = "Senior Kotlin developer",
                                        text =
                                            """
                                            1. Start with the system instruction.
                                            2. Keep the same user prompt.
                                            3. Compare tone and format.
                                            4. Check whether constraints changed the answer.
                                            5. Final detail appears after expansion.
                                            """.trimIndent(),
                                    ),
                                ),
                        ),
                    onAction = {},
                    onBack = {},
                )
            }
        }

        val result = composeRule.onNodeWithTag("${AgentChatTags.PROFILE_COMPARE_RESULT_PREFIX}_$SENIOR_KOTLIN_PROFILE_ID")
        result.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        composeRule.onNodeWithText("Start with the system instruction", substring = true).assertIsDisplayed()

        result.performClick()

        result.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        composeRule.onNodeWithText("Final detail appears after expansion", substring = true).assertIsDisplayed()
    }

    @Test
    fun customTokenLimitCanBeChanged() {
        var state by
            mutableStateOf(
                AgentChatUiState(
                    messages =
                        listOf(
                            AgentChatMessage(role = AgentChatRole.USER, text = "First"),
                            AgentChatMessage(
                                role = AgentChatRole.MODEL,
                                text = "Answer",
                                tokenUsage = GeminiTokenUsage(totalTokens = 17),
                            ),
                        ),
                ),
            )
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                AgentChatScreen(
                    state = state,
                    onAction = { action ->
                        if (action is AgentChatAction.TokenLimitChanged) {
                            state = state.copy(customTotalTokenLimit = action.input.filter { it.isDigit() }.toInt())
                        }
                    },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(AgentChatTags.TOKEN_LIMIT_INPUT).assertIsDisplayed().performTextInput("100")

        composeRule.onNodeWithText("Total/limit: 17 / 100 (custom), left 83", substring = true).fetchSemanticsNode()
    }

    @Test
    fun shortScenarioButtonShowsTokenGrowthAfterUserSetsLimit() {
        setInteractiveScenarioContent()

        composeRule.onNodeWithTag(AgentChatTags.TOKEN_LIMIT_INPUT).performTextInput("1000")
        composeRule
            .onNodeWithTag("${AgentChatTags.SCENARIO_PREFIX}_${AgentChatScenario.SHORT.name}")
            .performClick()

        composeRule.onNodeWithText("Total/limit: 690 / 1,000 (custom), left 310", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Turn 3: cumulative context grows.", substring = true).fetchSemanticsNode()
        composeRule.onNodeWithText("total 690", substring = true).fetchSemanticsNode()
        composeRule.onNodeWithText("Cost index: x3.8", substring = true).fetchSemanticsNode()
    }

    @Test
    fun longScenarioButtonShowsCostGrowthAfterUserSetsLimit() {
        setInteractiveScenarioContent()

        composeRule.onNodeWithTag(AgentChatTags.TOKEN_LIMIT_INPUT).performTextInput("20000")
        composeRule
            .onNodeWithTag("${AgentChatTags.SCENARIO_PREFIX}_${AgentChatScenario.LONG.name}")
            .performClick()

        composeRule.onNodeWithText("Total/limit: 14,600 / 1,500 (custom), left 0", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Turn 12: cumulative context grows.", substring = true).fetchSemanticsNode()
        composeRule.onNodeWithText("total 14,600", substring = true).fetchSemanticsNode()
        composeRule.onNodeWithText("Cost index: x21.2", substring = true).fetchSemanticsNode()
    }

    @Test
    fun overLimitScenarioButtonShowsBreakageAndSlidingWindowAfterUserSetsLimit() {
        setInteractiveScenarioContent()

        composeRule.onNodeWithTag(AgentChatTags.TOKEN_LIMIT_INPUT).performTextInput("3200")
        composeRule
            .onNodeWithTag("${AgentChatTags.SCENARIO_PREFIX}_${AgentChatScenario.OVER_MODEL_LIMIT.name}")
            .performClick()

        composeRule.onNodeWithText("Total/limit: 1,500 / 1,500 (custom), left 0", substring = true).fetchSemanticsNode()
        composeRule.onNodeWithText("Full-history budget reached.", substring = true).fetchSemanticsNode()
        composeRule.onNodeWithText("full history total 1,920 / active model limit 1,500", substring = true).fetchSemanticsNode()
        composeRule.onNodeWithText("Breakage: full history cannot be sent", substring = true).fetchSemanticsNode()
        composeRule.onNodeWithText("Sliding window retry.", substring = true).fetchSemanticsNode()
        composeRule.onNodeWithText("older facts are no longer in the model context", substring = true).fetchSemanticsNode()
    }

    @Test
    fun stopButtonIsEnabledWhileRequestIsLoading() {
        var state by
            mutableStateOf(
                AgentChatUiState(
                    messages =
                        listOf(
                            AgentChatMessage(role = AgentChatRole.USER, text = "Run long scenario"),
                            AgentChatMessage(
                                role = AgentChatRole.MODEL,
                                text = "Waiting for Gemini 3.5 Flash",
                                isLoading = true,
                            ),
                        ),
                ),
            )
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                AgentChatScreen(
                    state = state,
                    onAction = { action ->
                        if (action == AgentChatAction.Stop) {
                            state =
                                state.copy(
                                    messages =
                                        listOf(
                                            AgentChatMessage(role = AgentChatRole.USER, text = "Run long scenario"),
                                            AgentChatMessage(
                                                role = AgentChatRole.MODEL,
                                                text = "Stopped by user.",
                                                isError = true,
                                            ),
                                        ),
                                )
                        }
                    },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(AgentChatTags.STOP_BUTTON).assertIsEnabled().performClick()

        composeRule.onNodeWithText("Stopped by user.").assertIsDisplayed()
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
            .onNodeWithTag(AgentChatTags.AGENT_MENU_BUTTON)
            .performClick()
        composeRule
            .onNodeWithTag("${AgentChatTags.AGENT_PREFIX}_${AgentChatAgentOption.GEMINI_2_5_FLASH_LITE.name}")
            .performClick()
        composeRule.onNodeWithText(AgentChatAgentOption.GEMINI_2_5_FLASH_LITE.title, substring = true).assertIsDisplayed()
    }

    @Test
    fun gemmaAgentSelectionCanChangeBeforeChatStarts() {
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
            .onNodeWithTag(AgentChatTags.AGENT_MENU_BUTTON)
            .performClick()
        composeRule
            .onNodeWithTag("${AgentChatTags.AGENT_PREFIX}_${AgentChatAgentOption.GEMMA_4_26B_A4B_IT.name}")
            .performClick()

        composeRule
            .onNodeWithText(AgentChatAgentOption.GEMMA_4_26B_A4B_IT.title, substring = true)
            .assertIsDisplayed()
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

        composeRule.onNodeWithTag(AgentChatTags.AGENT_MENU_BUTTON).assertIsNotEnabled()
        composeRule.onNodeWithTag(AgentChatTags.CLEAR_BUTTON).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(AgentChatTags.AGENT_MENU_BUTTON).assertIsEnabled()
    }

    private fun setInteractiveScenarioContent() {
        var state by mutableStateOf(AgentChatUiState())
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                AgentChatScreen(
                    state = state,
                    onAction = { action ->
                        state =
                            when (action) {
                                is AgentChatAction.TokenLimitChanged ->
                                    state.copy(
                                        customTotalTokenLimit = action.input.filter { it.isDigit() }.toIntOrNull(),
                                    )
                                is AgentChatAction.ScenarioSelected -> {
                                    val scenarioLimit =
                                        if (
                                            action.scenario == AgentChatScenario.LONG ||
                                            action.scenario == AgentChatScenario.OVER_MODEL_LIMIT
                                        ) {
                                            1_500
                                        } else {
                                            state.effectiveTotalTokenLimit
                                        }
                                    state.copy(
                                        input = "",
                                        customTotalTokenLimit =
                                            if (
                                                action.scenario == AgentChatScenario.LONG ||
                                                action.scenario == AgentChatScenario.OVER_MODEL_LIMIT
                                            ) {
                                                scenarioLimit
                                            } else {
                                                state.customTotalTokenLimit
                                            },
                                        messages =
                                            action.scenario.toTestMessages(
                                                activeLimit = scenarioLimit,
                                                modelLimit = state.selectedAgent.totalTokenLimit,
                                            ),
                                    )
                                }
                                else -> state
                            }
                    },
                    onBack = {},
                )
            }
        }
    }
}

private fun AgentChatScenario.toTestMessages(
    activeLimit: Int,
    modelLimit: Int,
): List<AgentChatMessage> =
    when (this) {
        AgentChatScenario.SHORT ->
            listOf(
                AgentChatMessage(
                    role = AgentChatRole.USER,
                    text = "Run token scenario: Short dialog. Active limit: $activeLimit tokens.",
                ),
                scenarioModelMessage(turn = 1, current = 70, history = 110, response = 70, total = 180, costIndex = "x1.0"),
                scenarioModelMessage(turn = 2, current = 80, history = 340, response = 80, total = 420, costIndex = "x2.3"),
                scenarioModelMessage(turn = 3, current = 90, history = 580, response = 110, total = 690, costIndex = "x3.8"),
            )
        AgentChatScenario.LONG ->
            listOf(
                AgentChatMessage(
                    role = AgentChatRole.USER,
                    text = "Run token scenario: Long dialog. Active limit: $activeLimit tokens.",
                ),
                scenarioModelMessage(turn = 4, current = 400, history = 2_800, response = 400, total = 3_200, costIndex = "x4.6"),
                scenarioModelMessage(turn = 8, current = 500, history = 7_100, response = 700, total = 7_800, costIndex = "x11.3"),
                scenarioModelMessage(turn = 12, current = 600, history = 13_400, response = 1_200, total = 14_600, costIndex = "x21.2"),
            )
        AgentChatScenario.OVER_MODEL_LIMIT ->
            listOf(
                AgentChatMessage(
                    role = AgentChatRole.USER,
                    text = "Run token scenario: Over model limit. Active limit: $activeLimit tokens.",
                ),
                scenarioModelMessage(
                    turn = 40,
                    current = 12_000,
                    history = 838_000,
                    response = 12_000,
                    total = 850_000,
                    costIndex = "x1,231.9",
                ),
                AgentChatMessage(
                    role = AgentChatRole.MODEL,
                    text =
                        "Full-history budget reached.\n" +
                            "Tokens: full history total 1,920 / active model limit ${activeLimit.formatTestCount()} " +
                            "(model max ${modelLimit.formatTestCount()}).\n" +
                            "Breakage: full history cannot be sent; the oldest turns must be dropped.",
                    isError = true,
                    tokenUsage = GeminiTokenUsage(totalTokens = activeLimit + 420),
                ),
                AgentChatMessage(
                    role = AgentChatRole.MODEL,
                    text =
                        "Sliding window retry.\n" +
                            "Tokens after trimming: total ${activeLimit.formatTestCount()} / active limit " +
                            "${activeLimit.formatTestCount()}.\n" +
                            "Result: the chat continues, but older facts are no longer in the model context.",
                    tokenUsage = GeminiTokenUsage(totalTokens = activeLimit, slidingWindowApplied = true),
                ),
            )
    }

private fun scenarioModelMessage(
    turn: Int,
    current: Int,
    history: Int,
    response: Int,
    total: Int,
    costIndex: String,
): AgentChatMessage =
    AgentChatMessage(
        role = AgentChatRole.MODEL,
        text =
            "Turn $turn: cumulative context grows.\n" +
                "Tokens: current ${current.formatTestCount()}, history ${history.formatTestCount()}, " +
                "response ${response.formatTestCount()}, total ${total.formatTestCount()}.\n" +
                "Cost index: $costIndex.\n" +
                "Budget status: full context still fits.",
        tokenUsage =
            GeminiTokenUsage(
                currentRequestTokens = current,
                conversationHistoryTokens = history,
                modelResponseTokens = response,
                totalTokens = total,
                slidingWindowApplied = false,
            ),
    )

private fun Int.formatTestCount(): String = "%,d".format(this)
