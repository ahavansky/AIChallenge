package com.akhavanskii.aichallenge.feature.home

import com.akhavanskii.aichallenge.core.network.GeminiGenerationConfig
import com.akhavanskii.aichallenge.core.network.GeminiNetworkError
import com.akhavanskii.aichallenge.core.network.GeminiResult
import com.akhavanskii.aichallenge.core.network.GeminiTextClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PromptLabViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun taskChangedEnablesRunAndResetsComparison() {
        val viewModel = PromptLabViewModel(RecordingGeminiTextClient())

        viewModel.onAction(PromptLabAction.TaskChanged("Solve this"))

        assertEquals("Solve this", viewModel.uiState.value.task)
        assertTrue(viewModel.uiState.value.canRun)
        assertEquals(
            ResponsePaneState.Empty("Ready to run four prompt methods."),
            viewModel.uiState.value.comparisonState,
        )
    }

    @Test
    fun modelChangedUpdatesSelectedModel() {
        val viewModel = PromptLabViewModel(RecordingGeminiTextClient())

        viewModel.onAction(PromptLabAction.ModelChanged(PromptLabGeminiModel.GEMINI_2_5_FLASH_LITE))

        assertEquals(PromptLabGeminiModel.GEMINI_2_5_FLASH_LITE, viewModel.uiState.value.selectedModel)
    }

    @Test
    fun submitRunsFourMethodsThenComparesOutputs() =
        runTest {
            val fakeClient =
                RecordingGeminiTextClient { prompt ->
                    when {
                        prompt == "Original task" -> GeminiResult.Success("Direct answer")
                        prompt.startsWith("Решай пошагово") -> GeminiResult.Success("Step answer")
                        prompt.startsWith("Составь лучший промпт") -> GeminiResult.Success("Improved prompt")
                        prompt == "Improved prompt" -> GeminiResult.Success("Generated answer")
                        prompt.startsWith("Создай группу экспертов") -> GeminiResult.Success("Experts answer")
                        prompt.startsWith("Ты сравниваешь четыре ответа") -> GeminiResult.Success("Comparison answer")
                        else -> GeminiResult.Failure(GeminiNetworkError.Http(statusCode = 400, body = prompt))
                    }
                }
            val viewModel = PromptLabViewModel(fakeClient)
            viewModel.onAction(PromptLabAction.ModelChanged(PromptLabGeminiModel.GEMINI_2_5_FLASH_LITE))
            viewModel.onAction(PromptLabAction.TaskChanged("  Original\n task  "))

            viewModel.onAction(PromptLabAction.SubmitTask)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("Original task", state.task)
            assertFalse(state.isLoading)
            assertEquals(ResponsePaneState.Success("Direct answer"), state.outputState(PromptLabStrategy.DIRECT))
            assertEquals(ResponsePaneState.Success("Step answer"), state.outputState(PromptLabStrategy.STEP_BY_STEP))
            assertEquals(
                ResponsePaneState.Success("Generated prompt:\nImproved prompt\n\nFinal answer:\nGenerated answer"),
                state.outputState(PromptLabStrategy.GENERATED_PROMPT),
            )
            assertEquals(ResponsePaneState.Success("Experts answer"), state.outputState(PromptLabStrategy.EXPERT_GROUP))
            assertEquals(ResponsePaneState.Success("Comparison answer"), state.comparisonState)

            assertEquals(6, fakeClient.calls.size)
            assertTrue(fakeClient.calls.all { it.generationConfig == null })
            assertTrue(fakeClient.calls.all { it.modelName == PromptLabGeminiModel.GEMINI_2_5_FLASH_LITE.modelName })
            assertTrue(fakeClient.calls.any { it.prompt == "Original task" })
            assertTrue(fakeClient.calls.any { it.prompt == "Improved prompt" })
            val comparisonPrompt = fakeClient.calls.single { it.prompt.startsWith("Ты сравниваешь четыре ответа") }.prompt
            assertTrue(comparisonPrompt.contains("Отличаются ли ответы"))
            assertTrue(comparisonPrompt.contains("Какой способ дал наиболее точный результат"))
            assertTrue(comparisonPrompt.contains("Direct answer"))
            assertTrue(comparisonPrompt.contains("Experts answer"))
        }

    @Test
    fun submitSkipsComparisonWhenAllMethodsFail() =
        runTest {
            val fakeClient =
                RecordingGeminiTextClient {
                    GeminiResult.Failure(GeminiNetworkError.MissingApiKey)
                }
            val viewModel = PromptLabViewModel(fakeClient)
            viewModel.onAction(PromptLabAction.TaskChanged("Original task"))

            viewModel.onAction(PromptLabAction.SubmitTask)
            advanceUntilIdle()

            assertEquals(4, fakeClient.calls.size)
            assertEquals(
                ResponsePaneState.Error("Comparison skipped: no successful strategy outputs."),
                viewModel.uiState.value.comparisonState,
            )
        }

    private fun PromptLabUiState.outputState(strategy: PromptLabStrategy): ResponsePaneState =
        outputs.single { it.strategy == strategy }.state

    private class RecordingGeminiTextClient(
        private val responder: (String) -> GeminiResult<String> = { GeminiResult.Success("OK") },
    ) : GeminiTextClient {
        val calls = mutableListOf<GenerateCall>()

        override suspend fun generate(
            prompt: String,
            generationConfig: GeminiGenerationConfig?,
            modelName: String?,
        ): GeminiResult<String> {
            calls += GenerateCall(prompt = prompt, generationConfig = generationConfig, modelName = modelName)
            return responder(prompt)
        }
    }

    private data class GenerateCall(
        val prompt: String,
        val generationConfig: GeminiGenerationConfig?,
        val modelName: String?,
    )
}
