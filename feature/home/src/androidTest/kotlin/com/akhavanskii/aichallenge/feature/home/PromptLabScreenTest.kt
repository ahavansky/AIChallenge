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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.akhavanskii.aichallenge.core.designsystem.AIChallengeTheme
import org.junit.Rule
import org.junit.Test

class PromptLabScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun runButtonIsDisabledUntilTaskHasText() {
        var state by mutableStateOf(PromptLabUiState())
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                PromptLabScreen(
                    state = state,
                    onAction = { action ->
                        when (action) {
                            is PromptLabAction.ModelChanged -> state = state.copy(selectedModel = action.model)
                            is PromptLabAction.TaskChanged ->
                                state =
                                    state.copy(
                                        task = action.task,
                                        comparisonState = ResponsePaneState.Empty("Ready to run four prompt methods."),
                                    )
                            PromptLabAction.SubmitTask -> Unit
                        }
                    },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(PromptLabTags.RUN_BUTTON).assertIsNotEnabled()
        composeRule
            .onNodeWithTag("${PromptLabTags.MODEL_PREFIX}_${GeminiModelOption.GEMINI_2_5_FLASH.name}")
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag("${PromptLabTags.MODEL_PREFIX}_${GeminiModelOption.GEMINI_2_5_FLASH_LITE.name}")
            .performClick()
        composeRule.onNodeWithText(GeminiModelOption.GEMINI_2_5_FLASH_LITE.modelName, substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(PromptLabTags.TASK_INPUT).performTextInput("Solve this")
        composeRule.onNodeWithTag(PromptLabTags.RUN_BUTTON).assertIsEnabled()
    }

    @Test
    fun successStateShowsFourOutputsAndComparison() {
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                PromptLabScreen(
                    state =
                        PromptLabUiState(
                            task = "Solve this",
                            outputs =
                                PromptLabStrategy.entries.map { strategy ->
                                    PromptLabStrategyOutput(
                                        strategy = strategy,
                                        state = ResponsePaneState.Success("${strategy.title} result"),
                                    )
                                },
                            comparisonState = ResponsePaneState.Success("Expert group is most accurate."),
                        ),
                    onAction = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(PromptLabTags.OUTPUTS).performScrollTo()
        composeRule.onNodeWithText("Direct prompt result").assertIsDisplayed()
        composeRule.onNodeWithText("Step-by-step result").assertIsDisplayed()
        composeRule.onNodeWithText("Generated prompt result").assertIsDisplayed()
        composeRule.onNodeWithText("Expert group result").assertIsDisplayed()
        composeRule.onNodeWithTag(PromptLabTags.COMPARISON).performScrollTo()
        composeRule.onNodeWithText("Expert group is most accurate.").assertIsDisplayed()
    }
}
