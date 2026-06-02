package com.akhavanskii.aichallenge.feature.home

import app.cash.turbine.test
import com.akhavanskii.aichallenge.core.network.GeminiNetworkError
import com.akhavanskii.aichallenge.core.network.GeminiResult
import com.akhavanskii.aichallenge.core.network.GeminiTextClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(HomeContentState.Input, viewModel.uiState.value.contentState)
        assertTrue(viewModel.uiState.value.canSend)
    }

    @Test
    fun blankPromptKeepsSendDisabled() {
        val viewModel = HomeViewModel(FakeGeminiTextClient())

        viewModel.onAction(HomeAction.PromptChanged(" "))

        assertFalse(viewModel.uiState.value.canSend)
        assertEquals(HomeContentState.Idle, viewModel.uiState.value.contentState)
    }

    @Test
    fun submitShowsLoadingThenSuccess() =
        runTest {
            val deferred = CompletableDeferred<GeminiResult<String>>()
            val fakeClient = FakeGeminiTextClient(deferred)
            val viewModel = HomeViewModel(fakeClient)
            viewModel.onAction(HomeAction.PromptChanged("  Hello\nGemini  "))

            viewModel.uiState.test {
                assertEquals(HomeContentState.Input, awaitItem().contentState)
                viewModel.onAction(HomeAction.SubmitPrompt)
                assertEquals(HomeContentState.Loading, awaitItem().contentState)
                assertEquals("Hello Gemini", fakeClient.lastPrompt)

                deferred.complete(GeminiResult.Success("Response text"))
                runCurrent()

                assertEquals(HomeContentState.Success("Response text"), awaitItem().contentState)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun submitShowsErrorFromGeminiClient() =
        runTest {
            val deferred = CompletableDeferred<GeminiResult<String>>()
            val viewModel = HomeViewModel(FakeGeminiTextClient(deferred))
            viewModel.onAction(HomeAction.PromptChanged("Hello"))

            viewModel.uiState.test {
                awaitItem()
                viewModel.onAction(HomeAction.SubmitPrompt)
                assertEquals(HomeContentState.Loading, awaitItem().contentState)

                deferred.complete(GeminiResult.Failure(GeminiNetworkError.MissingApiKey))
                runCurrent()

                assertEquals(
                    HomeContentState.Error(GeminiNetworkError.MissingApiKey.userMessage),
                    awaitItem().contentState,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    private class FakeGeminiTextClient(
        private val deferredResult: CompletableDeferred<GeminiResult<String>> =
            CompletableDeferred(GeminiResult.Success("Response")),
    ) : GeminiTextClient {
        var lastPrompt: String? = null
            private set

        override suspend fun generate(prompt: String): GeminiResult<String> {
            lastPrompt = prompt
            return deferredResult.await()
        }
    }
}
