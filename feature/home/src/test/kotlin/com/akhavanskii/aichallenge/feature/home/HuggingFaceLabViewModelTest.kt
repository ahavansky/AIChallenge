package com.akhavanskii.aichallenge.feature.home

import com.akhavanskii.aichallenge.core.network.GeminiGenerationConfig
import com.akhavanskii.aichallenge.core.network.GeminiNetworkError
import com.akhavanskii.aichallenge.core.network.GeminiResult
import com.akhavanskii.aichallenge.core.network.GeminiTextClient
import com.akhavanskii.aichallenge.core.network.HuggingFaceNetworkError
import com.akhavanskii.aichallenge.core.network.HuggingFaceResponseMetadata
import com.akhavanskii.aichallenge.core.network.HuggingFaceResult
import com.akhavanskii.aichallenge.core.network.HuggingFaceTextClient
import com.akhavanskii.aichallenge.core.network.HuggingFaceTextResponse
import com.akhavanskii.aichallenge.core.network.HuggingFaceTokenUsage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HuggingFaceLabViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun actionsUpdateTaskAndGeminiModel() {
        val viewModel =
            HuggingFaceLabViewModel(
                huggingFaceTextClient = RecordingHuggingFaceTextClient(),
                geminiTextClient = RecordingGeminiTextClient(),
            )

        viewModel.onAction(HuggingFaceLabAction.TaskChanged("Compare this"))
        viewModel.onAction(HuggingFaceLabAction.GeminiModelChanged(GeminiModelOption.GEMINI_2_5_FLASH_LITE))

        val state = viewModel.uiState.value
        assertEquals("Compare this", state.task)
        assertEquals(GeminiModelOption.GEMINI_2_5_FLASH_LITE, state.selectedGeminiModel)
        assertTrue(state.canRun)
        assertEquals(ResponsePaneState.Empty("Ready to benchmark three HuggingFace models."), state.evaluationState)
    }

    @Test
    fun submitRunsThreeHuggingFaceModelsThenEvaluatesOutputs() =
        runTest {
            val huggingFaceClient =
                RecordingHuggingFaceTextClient { _, modelName ->
                    HuggingFaceResult.Success(
                        HuggingFaceTextResponse(
                            text = "Answer from $modelName",
                            tokenUsage =
                                HuggingFaceTokenUsage(
                                    promptTokens = 10,
                                    completionTokens = 20,
                                    totalTokens = 30,
                                    reasoningTokens = 4,
                                ),
                            metadata =
                                HuggingFaceResponseMetadata(
                                    attemptCount = 2,
                                    finishReasons = listOf("stop"),
                                ),
                        ),
                    )
                }
            val geminiClient = RecordingGeminiTextClient { _, _, _ -> GeminiResult.Success("Gemini evaluation") }
            val viewModel =
                HuggingFaceLabViewModel(
                    huggingFaceTextClient = huggingFaceClient,
                    geminiTextClient = geminiClient,
                )
            viewModel.onAction(HuggingFaceLabAction.GeminiModelChanged(GeminiModelOption.GEMINI_2_5_FLASH_LITE))
            viewModel.onAction(HuggingFaceLabAction.TaskChanged("  Original\n task  "))

            viewModel.onAction(HuggingFaceLabAction.SubmitTask)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("Original task", state.task)
            assertFalse(state.isLoading)
            HuggingFaceModelPreset.entries.forEach { preset ->
                val output = state.outputFor(preset)
                assertEquals(ResponsePaneState.Success("Answer from ${preset.modelName}"), output.state)
                assertNotNull(output.responseTimeMillis)
                assertEquals(30, output.tokenUsage?.totalTokens)
                assertEquals(4, output.tokenUsage?.reasoningTokens)
                assertEquals(16, output.tokenUsage?.visibleOutputTokens)
                assertEquals(2, output.metadata?.attemptCount)
            }
            assertEquals(ResponsePaneState.Success("Gemini evaluation"), state.evaluationState)

            assertEquals(
                HuggingFaceModelPreset.entries.map { it.modelName }.toSet(),
                huggingFaceClient.calls.map { it.modelName }.toSet(),
            )
            assertTrue(huggingFaceClient.calls.all { it.prompt == "Original task" })
            assertEquals(1, geminiClient.calls.size)
            val evaluationCall = geminiClient.calls.single()
            assertEquals(GeminiModelOption.GEMINI_2_5_FLASH_LITE.modelName, evaluationCall.modelName)
            assertTrue(evaluationCall.prompt.contains("Качество ответов"))
            assertTrue(evaluationCall.prompt.contains("responseTimeMs="))
            assertTrue(evaluationCall.prompt.contains("completionTokensPerSecond="))
            assertTrue(evaluationCall.prompt.contains("retryCount=1"))
            assertTrue(evaluationCall.prompt.contains("finishReasons=stop"))
            assertTrue(evaluationCall.prompt.contains("visible_output_tokens=16"))
            assertTrue(evaluationCall.prompt.contains("reasoning_tokens=4"))
            assertTrue(evaluationCall.prompt.contains("total_tokens=30"))
            assertTrue(evaluationCall.prompt.contains("accuracy, instruction_following, clarity"))
            assertTrue(evaluationCall.prompt.contains(HuggingFaceModelPreset.STRONG.modelName))
            assertTrue(evaluationCall.prompt.contains("Answer from ${HuggingFaceModelPreset.WEAK.modelName}"))
        }

    @Test
    fun submitSkipsEvaluationWhenAllHuggingFaceModelsFail() =
        runTest {
            val huggingFaceClient =
                RecordingHuggingFaceTextClient { _, _ ->
                    HuggingFaceResult.Failure(HuggingFaceNetworkError.MissingApiKey)
                }
            val geminiClient = RecordingGeminiTextClient()
            val viewModel =
                HuggingFaceLabViewModel(
                    huggingFaceTextClient = huggingFaceClient,
                    geminiTextClient = geminiClient,
                )
            viewModel.onAction(HuggingFaceLabAction.TaskChanged("Original task"))

            viewModel.onAction(HuggingFaceLabAction.SubmitTask)
            advanceUntilIdle()

            assertEquals(3, huggingFaceClient.calls.size)
            assertEquals(0, geminiClient.calls.size)
            assertEquals(
                ResponsePaneState.Error("Evaluation skipped: no successful HuggingFace outputs."),
                viewModel.uiState.value.evaluationState,
            )
        }

    private fun HuggingFaceLabUiState.outputFor(preset: HuggingFaceModelPreset): HuggingFaceLabOutput =
        outputs.single { it.preset == preset }

    private class RecordingHuggingFaceTextClient(
        private val responder: (String, String) -> HuggingFaceResult<HuggingFaceTextResponse> = { _, _ ->
            HuggingFaceResult.Success(HuggingFaceTextResponse(text = "OK", tokenUsage = null))
        },
    ) : HuggingFaceTextClient {
        val calls = mutableListOf<HuggingFaceCall>()

        override suspend fun generate(
            prompt: String,
            modelName: String,
        ): HuggingFaceResult<HuggingFaceTextResponse> {
            calls += HuggingFaceCall(prompt = prompt, modelName = modelName)
            return responder(prompt, modelName)
        }
    }

    private class RecordingGeminiTextClient(
        private val responder: (String, GeminiGenerationConfig?, String?) -> GeminiResult<String> = { _, _, _ ->
            GeminiResult.Failure(GeminiNetworkError.MissingApiKey)
        },
    ) : GeminiTextClient {
        val calls = mutableListOf<GeminiCall>()

        override suspend fun generate(
            prompt: String,
            generationConfig: GeminiGenerationConfig?,
            modelName: String?,
        ): GeminiResult<String> {
            calls += GeminiCall(prompt = prompt, generationConfig = generationConfig, modelName = modelName)
            return responder(prompt, generationConfig, modelName)
        }
    }

    private data class HuggingFaceCall(
        val prompt: String,
        val modelName: String,
    )

    private data class GeminiCall(
        val prompt: String,
        val generationConfig: GeminiGenerationConfig?,
        val modelName: String?,
    )
}
