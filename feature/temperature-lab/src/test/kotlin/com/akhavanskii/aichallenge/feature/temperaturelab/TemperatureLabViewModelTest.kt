package com.akhavanskii.aichallenge.feature.temperaturelab

import com.akhavanskii.aichallenge.core.network.GeminiGenerationConfig
import com.akhavanskii.aichallenge.core.network.GeminiNetworkError
import com.akhavanskii.aichallenge.core.network.GeminiResult
import com.akhavanskii.aichallenge.core.network.GeminiTextClient
import com.akhavanskii.aichallenge.feature.common.GeminiModelOption
import com.akhavanskii.aichallenge.feature.common.ResponsePaneState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TemperatureLabViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun actionsUpdateTaskModelAndTemperature() {
        val viewModel = TemperatureLabViewModel(RecordingGeminiTextClient())

        viewModel.onAction(TemperatureLabAction.TaskChanged("Compare this"))
        viewModel.onAction(TemperatureLabAction.ModelChanged(GeminiModelOption.GEMINI_2_5_FLASH_LITE))
        viewModel.onAction(TemperatureLabAction.TemperatureChanged(TemperatureSlot.VARIANT_A, 1.1))

        val state = viewModel.uiState.value
        assertEquals("Compare this", state.task)
        assertEquals(GeminiModelOption.GEMINI_2_5_FLASH_LITE, state.selectedModel)
        assertEquals(1.1, state.settings.single { it.slot == TemperatureSlot.VARIANT_A }.temperature, 0.0)
        assertTrue(state.canRun)
        assertEquals(ResponsePaneState.Empty("Ready to compare three temperature settings."), state.evaluationState)
    }

    @Test
    fun submitRunsThreeTemperaturesThenEvaluatesOutputs() =
        runTest {
            val fakeClient =
                RecordingGeminiTextClient { prompt, generationConfig ->
                    when {
                        prompt == "Original task" && generationConfig?.temperature == 0.2 -> GeminiResult.Success("Low answer")
                        prompt == "Original task" && generationConfig?.temperature == 0.7 -> GeminiResult.Success("Medium answer")
                        prompt == "Original task" && generationConfig?.temperature == 1.4 -> GeminiResult.Success("High answer")
                        prompt.startsWith("Ты сравниваешь три ответа Gemini") -> GeminiResult.Success("Evaluation answer")
                        else -> GeminiResult.Failure(GeminiNetworkError.Http(statusCode = 400, body = prompt))
                    }
                }
            val viewModel = TemperatureLabViewModel(fakeClient)
            viewModel.onAction(TemperatureLabAction.ModelChanged(GeminiModelOption.GEMINI_2_5_FLASH_LITE))
            viewModel.onAction(TemperatureLabAction.TaskChanged("  Original\n task  "))

            viewModel.onAction(TemperatureLabAction.SubmitTask)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("Original task", state.task)
            assertFalse(state.isLoading)
            assertEquals(ResponsePaneState.Success("Low answer"), state.outputState(TemperatureSlot.VARIANT_A))
            assertEquals(ResponsePaneState.Success("Medium answer"), state.outputState(TemperatureSlot.VARIANT_B))
            assertEquals(ResponsePaneState.Success("High answer"), state.outputState(TemperatureSlot.VARIANT_C))
            assertEquals(ResponsePaneState.Success("Evaluation answer"), state.evaluationState)

            assertEquals(4, fakeClient.calls.size)
            assertTrue(fakeClient.calls.all { it.modelName == GeminiModelOption.GEMINI_2_5_FLASH_LITE.modelName })
            assertEquals(
                listOf(0.2, 0.7, 1.4),
                fakeClient.calls.mapNotNull { it.generationConfig?.temperature }.sorted(),
            )
            val evaluationCall = fakeClient.calls.single { it.generationConfig == null }
            assertTrue(evaluationCall.prompt.contains("Для каких типов задач лучше подходит каждая настройка temperature"))
            assertTrue(evaluationCall.prompt.contains("Low answer"))
            assertTrue(evaluationCall.prompt.contains("High answer"))
        }

    @Test
    fun submitSkipsEvaluationWhenAllTemperaturesFail() =
        runTest {
            val fakeClient =
                RecordingGeminiTextClient { _, _ ->
                    GeminiResult.Failure(GeminiNetworkError.MissingApiKey)
                }
            val viewModel = TemperatureLabViewModel(fakeClient)
            viewModel.onAction(TemperatureLabAction.TaskChanged("Original task"))

            viewModel.onAction(TemperatureLabAction.SubmitTask)
            advanceUntilIdle()

            assertEquals(3, fakeClient.calls.size)
            assertEquals(
                ResponsePaneState.Error("Evaluation skipped: no successful temperature outputs."),
                viewModel.uiState.value.evaluationState,
            )
        }

    private fun TemperatureLabUiState.outputState(slot: TemperatureSlot): ResponsePaneState = outputs.single { it.slot == slot }.state

    private class RecordingGeminiTextClient(
        private val responder: (String, GeminiGenerationConfig?) -> GeminiResult<String> = { _, _ ->
            GeminiResult.Success("OK")
        },
    ) : GeminiTextClient {
        val calls = mutableListOf<GenerateCall>()

        override suspend fun generate(
            prompt: String,
            generationConfig: GeminiGenerationConfig?,
            modelName: String?,
        ): GeminiResult<String> {
            calls += GenerateCall(prompt = prompt, generationConfig = generationConfig, modelName = modelName)
            return responder(prompt, generationConfig)
        }
    }

    private data class GenerateCall(
        val prompt: String,
        val generationConfig: GeminiGenerationConfig?,
        val modelName: String?,
    )
}
