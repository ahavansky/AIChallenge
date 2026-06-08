package com.akhavanskii.aichallenge.feature.home

import app.cash.turbine.test
import com.akhavanskii.aichallenge.core.network.GeminiGenerationConfig
import com.akhavanskii.aichallenge.core.network.GeminiNetworkError
import com.akhavanskii.aichallenge.core.network.GeminiResult
import com.akhavanskii.aichallenge.core.network.GeminiTextClient
import com.akhavanskii.aichallenge.feature.common.ResponsePaneState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun promptChangedMovesStateToInputAndEnablesSend() {
        val viewModel = HomeViewModel(FakeGeminiTextClient())

        viewModel.onAction(HomeAction.PromptChanged("Hello"))

        assertEquals("Hello", viewModel.uiState.value.prompt)
        assertEquals(HomeComparisonState.input(), viewModel.uiState.value.comparisonState)
        assertTrue(viewModel.uiState.value.canSend)
    }

    @Test
    fun blankPromptKeepsSendDisabled() {
        val viewModel = HomeViewModel(FakeGeminiTextClient())

        viewModel.onAction(HomeAction.PromptChanged(" "))

        assertFalse(viewModel.uiState.value.canSend)
        assertEquals(HomeComparisonState.idle(), viewModel.uiState.value.comparisonState)
    }

    @Test
    fun generationConfigChangedUpdatesConfigAndResetsComparison() {
        val viewModel = HomeViewModel(FakeGeminiTextClient())
        viewModel.onAction(HomeAction.PromptChanged("Hello"))

        viewModel.onAction(
            HomeAction.GenerationConfigChanged(
                GeminiGenerationConfigUiState(temperature = 1.2),
            ),
        )

        assertEquals(1.2, viewModel.uiState.value.generationConfig.temperature, 0.0)
        assertEquals(HomeComparisonState.input(), viewModel.uiState.value.comparisonState)
    }

    @Test
    fun submitStartsConfiguredAndBaselineRequestsThenShowsBothResponses() =
        runTest {
            val configuredResult = CompletableDeferred<GeminiResult<String>>()
            val baselineResult = CompletableDeferred<GeminiResult<String>>()
            val fakeClient =
                FakeGeminiTextClient(
                    configuredResult = configuredResult,
                    baselineResult = baselineResult,
                )
            val viewModel = HomeViewModel(fakeClient)
            viewModel.onAction(HomeAction.PromptChanged("  Hello\nGemini  "))

            viewModel.uiState.test {
                assertEquals(HomeComparisonState.input(), awaitItem().comparisonState)
                viewModel.onAction(HomeAction.SubmitPrompt)

                val loadingState = awaitItem()
                assertEquals("Hello Gemini", loadingState.prompt)
                assertEquals(HomeComparisonState.loading(), loadingState.comparisonState)

                runCurrent()
                assertEquals(2, fakeClient.calls.size)
                val configuredCall = fakeClient.calls.single { it.generationConfig != null }
                val baselineCall = fakeClient.calls.single { it.generationConfig == null }
                assertEquals("Hello Gemini", configuredCall.prompt)
                assertNotNull(configuredCall.generationConfig)
                assertEquals(
                    GeminiGenerationConfigUiState.RESPONSE_MIME_TYPE_JSON,
                    configuredCall.generationConfig?.responseMimeType,
                )
                assertEquals(
                    GeminiGenerationConfigUiState.DEFAULT_RESPONSE_SCHEMA,
                    configuredCall.generationConfig?.responseSchemaJson,
                )
                assertNull(configuredCall.generationConfig?.presencePenalty)
                assertNull(configuredCall.generationConfig?.frequencyPenalty)
                assertEquals("Hello Gemini", baselineCall.prompt)
                assertNull(baselineCall.generationConfig)

                configuredResult.complete(GeminiResult.Success("Configured response"))
                runCurrent()
                assertEquals(
                    ResponsePaneState.Success("Configured response"),
                    awaitItem().comparisonState.configured,
                )

                baselineResult.complete(GeminiResult.Success("Baseline response"))
                runCurrent()
                assertEquals(
                    HomeComparisonState(
                        configured = ResponsePaneState.Success("Configured response"),
                        baseline = ResponsePaneState.Success("Baseline response"),
                    ),
                    awaitItem().comparisonState,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun submitShowsIndependentErrorAndSuccess() =
        runTest {
            val configuredResult = CompletableDeferred<GeminiResult<String>>()
            val baselineResult = CompletableDeferred<GeminiResult<String>>()
            val viewModel =
                HomeViewModel(
                    FakeGeminiTextClient(
                        configuredResult = configuredResult,
                        baselineResult = baselineResult,
                    ),
                )
            viewModel.onAction(HomeAction.PromptChanged("Hello"))

            viewModel.onAction(HomeAction.SubmitPrompt)
            runCurrent()
            configuredResult.complete(GeminiResult.Failure(GeminiNetworkError.MissingApiKey))
            baselineResult.complete(GeminiResult.Success("Baseline response"))
            runCurrent()

            val comparisonState = viewModel.uiState.value.comparisonState
            assertEquals(
                ResponsePaneState.Error(GeminiNetworkError.MissingApiKey.userMessage),
                comparisonState.configured,
            )
            assertEquals(ResponsePaneState.Success("Baseline response"), comparisonState.baseline)
        }

    @Test
    fun invalidConfiguredParamsStillSendBaselineRequest() =
        runTest {
            val baselineResult = CompletableDeferred<GeminiResult<String>>(GeminiResult.Success("Baseline response"))
            val fakeClient = FakeGeminiTextClient(baselineResult = baselineResult)
            val viewModel = HomeViewModel(fakeClient)
            viewModel.onAction(HomeAction.PromptChanged("Hello"))
            viewModel.onAction(
                HomeAction.GenerationConfigChanged(
                    GeminiGenerationConfigUiState(
                        responseMimeType = GeminiGenerationConfigUiState.RESPONSE_MIME_TYPE_TEXT,
                        responseSchema = """{"type":"object"}""",
                    ),
                ),
            )

            viewModel.onAction(HomeAction.SubmitPrompt)
            runCurrent()

            assertEquals(1, fakeClient.calls.size)
            assertNull(fakeClient.calls.single().generationConfig)
            assertEquals(
                ResponsePaneState.Error("responseSchema requires application/json or text/x.enum responseMimeType."),
                viewModel.uiState.value.comparisonState.configured,
            )
            assertEquals(
                ResponsePaneState.Success("Baseline response"),
                viewModel.uiState.value.comparisonState.baseline,
            )
        }

    private class FakeGeminiTextClient(
        private val configuredResult: CompletableDeferred<GeminiResult<String>> =
            CompletableDeferred(GeminiResult.Success("Configured response")),
        private val baselineResult: CompletableDeferred<GeminiResult<String>> =
            CompletableDeferred(GeminiResult.Success("Baseline response")),
    ) : GeminiTextClient {
        val calls = mutableListOf<GenerateCall>()

        override suspend fun generate(
            prompt: String,
            generationConfig: GeminiGenerationConfig?,
            modelName: String?,
        ): GeminiResult<String> {
            calls += GenerateCall(prompt = prompt, generationConfig = generationConfig)
            return if (generationConfig == null) {
                baselineResult.await()
            } else {
                configuredResult.await()
            }
        }
    }

    private data class GenerateCall(
        val prompt: String,
        val generationConfig: GeminiGenerationConfig?,
    )
}
