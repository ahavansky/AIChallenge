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
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.akhavanskii.aichallenge.core.designsystem.AIChallengeTheme
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
        composeRule.onNodeWithText("User profile", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Source: Senior Kotlin developer", substring = true).assertIsDisplayed()
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
                                is AgentChatAction.ProfileInputChanged ->
                                    state.copy(
                                        profileInput = action.input,
                                        profiles =
                                            state.profiles.map { profile ->
                                                if (profile.id == state.activeProfileId) {
                                                    AgentChatUserProfile.fromEditableText(profile.id, profile.title, action.input)
                                                } else {
                                                    profile
                                                }
                                            },
                                    )
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

        composeRule.onNodeWithTag(AgentChatTags.PROFILE_EDITOR_TOGGLE).performClick()
        composeRule.onNodeWithTag(AgentChatTags.PROFILE_INPUT).performScrollTo().performTextInput("\nStyle: Be brief")
        composeRule.onNodeWithTag(AgentChatTags.TASK_CONTEXT_EDITOR_TOGGLE).performScrollTo().performClick()
        composeRule.onNodeWithTag(AgentChatTags.TASK_CONTEXT_INPUT).performScrollTo().performTextInput("\nGoal: Demo")
        composeRule.onNodeWithTag(AgentChatTags.LONG_TERM_MEMORY_EDITOR_TOGGLE).performScrollTo().performClick()
        composeRule.onNodeWithTag(AgentChatTags.LONG_TERM_MEMORY_INPUT).performScrollTo().performTextInput("\n- Answer briefly")
        composeRule
            .onNodeWithTag(AgentChatTags.SAVE_LONG_TERM_MEMORY_BUTTON)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        assertTrue(actions.any { it is AgentChatAction.ProfileInputChanged })
        assertTrue(actions.any { it is AgentChatAction.TaskContextChanged })
        assertTrue(actions.any { it is AgentChatAction.LongTermMemoryChanged })
        assertTrue(actions.contains(AgentChatAction.SaveLongTermMemory))
    }

    @Test
    fun profileSelectionDispatchesAction() {
        var state by mutableStateOf(AgentChatUiState())
        val actions = mutableListOf<AgentChatAction>()
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                AgentChatScreen(
                    state = state,
                    onAction = { action ->
                        actions += action
                        if (action is AgentChatAction.ProfileChanged) {
                            state =
                                state.copy(
                                    activeProfileId = action.profileId,
                                    profileInput = state.profiles.first { it.id == action.profileId }.toEditableText(),
                                )
                        }
                    },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(AgentChatTags.PROFILE_MENU_BUTTON).performClick()
        composeRule.onNodeWithTag("${AgentChatTags.PROFILE_PREFIX}_$ANDROID_BEGINNER_PROFILE_ID").performClick()

        assertTrue(actions.contains(AgentChatAction.ProfileChanged(ANDROID_BEGINNER_PROFILE_ID)))
        composeRule
            .onNodeWithTag(AgentChatTags.PROFILE_MENU_BUTTON)
            .assertTextContains("Android beginner", substring = true)
    }

    @Test
    fun profileCompareButtonDispatchesAction() {
        var state by mutableStateOf(AgentChatUiState(input = "Explain memory"))
        val actions = mutableListOf<AgentChatAction>()
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                AgentChatScreen(
                    state = state,
                    onAction = { action ->
                        actions += action
                        if (action == AgentChatAction.CompareProfiles) {
                            state =
                                state.copy(
                                    input = "",
                                    compareResults =
                                        listOf(
                                            AgentChatProfileCompareResult(
                                                profileId = SENIOR_KOTLIN_PROFILE_ID,
                                                profileTitle = "Senior Kotlin developer",
                                                text = "Profile-specific answer",
                                            ),
                                        ),
                                )
                        }
                    },
                    onBack = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(AgentChatTags.COMPARE_PROFILES_BUTTON)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        assertTrue(actions.contains(AgentChatAction.CompareProfiles))
        composeRule.onNodeWithTag(AgentChatTags.PROFILE_COMPARE_RESULTS).assertIsDisplayed()
        composeRule.onNodeWithText("Profile-specific answer", substring = true).assertIsDisplayed()
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
    fun stopButtonIsEnabledWhileRequestIsLoading() {
        var state by
            mutableStateOf(
                AgentChatUiState(
                    messages =
                        listOf(
                            AgentChatMessage(role = AgentChatRole.USER, text = "Run long request"),
                            AgentChatMessage(role = AgentChatRole.MODEL, text = "Waiting for Gemini", isLoading = true),
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
                                            AgentChatMessage(role = AgentChatRole.USER, text = "Run long request"),
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
    fun clearChatDispatchesAction() {
        var state by
            mutableStateOf(
                AgentChatUiState(
                    messages = listOf(AgentChatMessage(role = AgentChatRole.USER, text = "First")),
                ),
            )
        val actions = mutableListOf<AgentChatAction>()
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                AgentChatScreen(
                    state = state,
                    onAction = { action ->
                        actions += action
                        if (action == AgentChatAction.ClearChat) {
                            state = state.copy(messages = emptyList())
                        }
                    },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(AgentChatTags.CLEAR_BUTTON).assertIsEnabled().performClick()

        assertTrue(actions.contains(AgentChatAction.ClearChat))
        composeRule.onNodeWithText("No messages yet.").assertIsDisplayed()
    }
}
