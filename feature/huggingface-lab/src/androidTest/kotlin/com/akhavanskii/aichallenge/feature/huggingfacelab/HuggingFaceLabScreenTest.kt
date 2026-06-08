package com.akhavanskii.aichallenge.feature.huggingfacelab

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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.akhavanskii.aichallenge.core.designsystem.AIChallengeTheme
import com.akhavanskii.aichallenge.core.network.HuggingFaceResponseMetadata
import com.akhavanskii.aichallenge.core.network.HuggingFaceTokenUsage
import com.akhavanskii.aichallenge.feature.common.GeminiModelOption
import com.akhavanskii.aichallenge.feature.common.ResponsePaneState
import org.junit.Rule
import org.junit.Test

class HuggingFaceLabScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun runButtonIsDisabledUntilTaskHasText() {
        var state by mutableStateOf(HuggingFaceLabUiState())
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                HuggingFaceLabScreen(
                    state = state,
                    onAction = { action ->
                        when (action) {
                            is HuggingFaceLabAction.GeminiModelChanged -> state = state.copy(selectedGeminiModel = action.model)
                            is HuggingFaceLabAction.TaskChanged ->
                                state =
                                    state.copy(
                                        task = action.task,
                                        evaluationState = ResponsePaneState.Empty("Ready to benchmark three HuggingFace models."),
                                    )
                            HuggingFaceLabAction.SubmitTask -> Unit
                        }
                    },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(HuggingFaceLabTags.RUN_BUTTON).assertIsNotEnabled()
        composeRule
            .onNodeWithTag("${HuggingFaceLabTags.GEMINI_MODEL_PREFIX}_${GeminiModelOption.GEMINI_2_5_FLASH_LITE.name}")
            .performClick()
        composeRule.onNodeWithText(GeminiModelOption.GEMINI_2_5_FLASH_LITE.modelName, substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(HuggingFaceLabTags.TASK_INPUT).performTextInput("Solve this")
        composeRule.onNodeWithTag(HuggingFaceLabTags.RUN_BUTTON).assertIsEnabled()
        HuggingFaceModelPreset.entries.forEach { preset ->
            composeRule
                .onNodeWithTag("${HuggingFaceLabTags.MODEL_PREFIX}_${preset.name}")
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun successStateShowsOutputsMetricsAndEvaluation() {
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                HuggingFaceLabScreen(
                    state =
                        HuggingFaceLabUiState(
                            task = "Solve this",
                            outputs =
                                HuggingFaceModelPreset.entries.mapIndexed { index, preset ->
                                    HuggingFaceLabOutput(
                                        preset = preset,
                                        state = ResponsePaneState.Success("${preset.title} result"),
                                        responseTimeMillis = 1000L + index,
                                        tokenUsage =
                                            HuggingFaceTokenUsage(
                                                promptTokens = 10,
                                                completionTokens = 20 + index,
                                                totalTokens = 30 + index,
                                                reasoningTokens = 5,
                                            ),
                                        metadata =
                                            HuggingFaceResponseMetadata(
                                                attemptCount = 1 + index,
                                                finishReasons = listOf("stop"),
                                            ),
                                    )
                                },
                            evaluationState =
                                ResponsePaneState.Success(
                                    """
                                    ### Общий рейтинг моделей

                                    | Критерий | Weak | Medium |
                                    | :--- | :--- | :--- |
                                    | **Accuracy** | 5 | 4 |
                                    | **Instruction following** | 5 | 5 |

                                    1. **Weak** is fast and accurate.
                                    """.trimIndent(),
                                ),
                        ),
                    onAction = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(HuggingFaceLabTags.OUTPUTS).performScrollTo()
        composeRule.onNodeWithText("GPT-OSS 20B result", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Response time: 1000 ms", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Throughput: 20.00 completion tok/s", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Attempts: 1; retries: 0", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("Finish reason: stop", substring = true).assertCountEquals(3)
        composeRule.onNodeWithText("Tokens: total 30", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("visible 15, reasoning 5", substring = true).assertIsDisplayed()
        composeRule
            .onNodeWithTag("${HuggingFaceLabTags.OUTPUT_PREFIX}_${HuggingFaceModelPreset.STRONG.name}")
            .performScrollTo()
        composeRule.onNodeWithText("GPT-OSS 120B Cerebras result", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag(HuggingFaceLabTags.EVALUATION).performScrollTo()
        composeRule.onNodeWithText("Общий рейтинг моделей").assertIsDisplayed()
        composeRule.onNodeWithText("Accuracy").assertIsDisplayed()
        composeRule.onAllNodesWithText("Weak: 5").assertCountEquals(2)
        composeRule.onNodeWithText("Medium: 4").assertIsDisplayed()
        composeRule.onNodeWithText("Weak is fast and accurate.", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("| Критерий", substring = true).assertCountEquals(0)
    }
}
