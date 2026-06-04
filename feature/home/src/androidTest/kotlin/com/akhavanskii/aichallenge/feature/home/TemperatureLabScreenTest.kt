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

class TemperatureLabScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun runButtonIsDisabledUntilTaskHasText() {
        var state by mutableStateOf(TemperatureLabUiState())
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                TemperatureLabScreen(
                    state = state,
                    onAction = { action ->
                        when (action) {
                            is TemperatureLabAction.ModelChanged -> state = state.copy(selectedModel = action.model)
                            is TemperatureLabAction.TaskChanged ->
                                state =
                                    state.copy(
                                        task = action.task,
                                        evaluationState = ResponsePaneState.Empty("Ready to compare three temperature settings."),
                                    )
                            is TemperatureLabAction.TemperatureChanged ->
                                state =
                                    state.copy(
                                        settings =
                                            state.settings.map { setting ->
                                                if (setting.slot == action.slot) {
                                                    setting.copy(temperature = action.temperature)
                                                } else {
                                                    setting
                                                }
                                            },
                                    )
                            TemperatureLabAction.SubmitTask -> Unit
                        }
                    },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(TemperatureLabTags.RUN_BUTTON).assertIsNotEnabled()
        composeRule
            .onNodeWithTag("${TemperatureLabTags.MODEL_PREFIX}_${GeminiModelOption.GEMINI_2_5_FLASH_LITE.name}")
            .performClick()
        composeRule.onNodeWithText(GeminiModelOption.GEMINI_2_5_FLASH_LITE.modelName, substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("${TemperatureLabTags.TEMPERATURE_PREFIX}_${TemperatureSlot.VARIANT_A.name}").assertIsDisplayed()
        composeRule.onNodeWithTag("${TemperatureLabTags.TEMPERATURE_PREFIX}_${TemperatureSlot.VARIANT_B.name}").assertIsDisplayed()
        composeRule.onNodeWithTag("${TemperatureLabTags.TEMPERATURE_PREFIX}_${TemperatureSlot.VARIANT_C.name}").assertIsDisplayed()
        composeRule.onNodeWithTag(TemperatureLabTags.TASK_INPUT).performTextInput("Solve this")
        composeRule.onNodeWithTag(TemperatureLabTags.RUN_BUTTON).assertIsEnabled()
    }

    @Test
    fun successStateShowsThreeOutputsAndEvaluation() {
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                TemperatureLabScreen(
                    state =
                        TemperatureLabUiState(
                            task = "Solve this",
                            outputs =
                                TemperatureSlot.defaultSettings().map { setting ->
                                    TemperatureLabOutput(
                                        slot = setting.slot,
                                        temperature = setting.temperature,
                                        state = ResponsePaneState.Success("${setting.slot.title} result"),
                                    )
                                },
                            evaluationState = ResponsePaneState.Success("Temperature B is best for balanced work."),
                        ),
                    onAction = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(TemperatureLabTags.OUTPUTS).performScrollTo()
        composeRule.onNodeWithText("Temperature A result").assertIsDisplayed()
        composeRule.onNodeWithText("Temperature B result").assertIsDisplayed()
        composeRule.onNodeWithText("Temperature C result").assertIsDisplayed()
        composeRule.onNodeWithTag(TemperatureLabTags.EVALUATION).performScrollTo()
        composeRule.onNodeWithText("Temperature B is best for balanced work.").assertIsDisplayed()
    }
}
