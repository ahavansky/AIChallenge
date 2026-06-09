package com.akhavanskii.aichallenge.feature.agentchat

import com.akhavanskii.aichallenge.core.network.AgentMessage
import com.akhavanskii.aichallenge.core.network.AgentResult
import com.akhavanskii.aichallenge.core.network.GeminiGenerationConfig
import com.akhavanskii.aichallenge.core.network.GeminiNetworkError
import com.akhavanskii.aichallenge.core.network.GeminiResult
import com.akhavanskii.aichallenge.core.network.LlmAgent
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
class AgentChatViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun inputChangedEnablesSend() {
        val viewModel = createViewModel()

        viewModel.onAction(AgentChatAction.InputChanged("Hello"))

        assertEquals("Hello", viewModel.uiState.value.input)
        assertTrue(viewModel.uiState.value.canSend)
    }

    @Test
    fun submitSendsFirstUserMessageAndShowsResponse() =
        runTest {
            val response = CompletableDeferred<AgentResult<String>>()
            val fakeAgent = FakeLlmAgent(results = ArrayDeque(listOf(response)))
            val viewModel = createViewModel(fakeAgent)
            viewModel.onAction(AgentChatAction.InputChanged("  Hello\nGemini  "))

            viewModel.onAction(AgentChatAction.Submit)
            runCurrent()

            assertEquals("", viewModel.uiState.value.input)
            assertFalse(viewModel.uiState.value.canSend)
            assertEquals(listOf(AgentMessage.User("Hello Gemini")), fakeAgent.calls.single().messages)
            assertEquals(
                listOf(
                    AgentChatMessage(role = AgentChatRole.USER, text = "Hello Gemini"),
                    AgentChatMessage(
                        role = AgentChatRole.MODEL,
                        text = "Waiting for Gemini 3.5 Flash",
                        isLoading = true,
                    ),
                ),
                viewModel.uiState.value.messages,
            )

            response.complete(GeminiResult.Success("Answer"))
            runCurrent()

            assertEquals(
                listOf(
                    AgentChatMessage(role = AgentChatRole.USER, text = "Hello Gemini"),
                    AgentChatMessage(role = AgentChatRole.MODEL, text = "Answer"),
                ),
                viewModel.uiState.value.messages,
            )
        }

    @Test
    fun submitSendsAccumulatedSuccessfulHistory() =
        runTest {
            val fakeAgent = FakeLlmAgent()
            val viewModel = createViewModel(fakeAgent)

            viewModel.onAction(AgentChatAction.InputChanged("First"))
            viewModel.onAction(AgentChatAction.Submit)
            runCurrent()
            viewModel.onAction(AgentChatAction.InputChanged("Second"))
            viewModel.onAction(AgentChatAction.Submit)
            runCurrent()

            assertEquals(
                listOf(
                    AgentMessage.User("First"),
                    AgentMessage.Model("Answer"),
                    AgentMessage.User("Second"),
                ),
                fakeAgent.calls[1].messages,
            )
            assertTrue(fakeAgent.calls.all { it.modelName == AgentChatAgentOption.GEMINI_3_5_FLASH.modelName })
        }

    @Test
    fun submitShowsErrorsButDoesNotSendThemBackInHistory() =
        runTest {
            val fakeAgent =
                FakeLlmAgent(
                    results =
                        ArrayDeque(
                            listOf(
                                CompletableDeferred(GeminiResult.Failure(GeminiNetworkError.MissingApiKey)),
                                CompletableDeferred(GeminiResult.Success("Recovered")),
                            ),
                        ),
                )
            val viewModel = createViewModel(fakeAgent)

            viewModel.onAction(AgentChatAction.InputChanged("First"))
            viewModel.onAction(AgentChatAction.Submit)
            runCurrent()
            viewModel.onAction(AgentChatAction.InputChanged("Second"))
            viewModel.onAction(AgentChatAction.Submit)
            runCurrent()

            assertEquals(listOf(AgentMessage.User("Second")), fakeAgent.calls[1].messages)
            assertEquals(
                AgentChatMessage(
                    role = AgentChatRole.MODEL,
                    text = GeminiNetworkError.MissingApiKey.userMessage,
                    isError = true,
                ),
                viewModel.uiState.value.messages[1],
            )
        }

    @Test
    fun blankSubmitDoesNotCallAgent() {
        val fakeAgent = FakeLlmAgent()
        val viewModel = createViewModel(fakeAgent)

        viewModel.onAction(AgentChatAction.InputChanged(" "))
        viewModel.onAction(AgentChatAction.Submit)

        assertTrue(fakeAgent.calls.isEmpty())
        assertEquals(
            listOf(
                AgentChatMessage(
                    role = AgentChatRole.MODEL,
                    text = "Enter a message before sending.",
                    isError = true,
                ),
            ),
            viewModel.uiState.value.messages,
        )
    }

    @Test
    fun submitUsesSelectedAgentModelName() =
        runTest {
            val fakeAgent = FakeLlmAgent()
            val viewModel = createViewModel(fakeAgent)

            viewModel.onAction(AgentChatAction.AgentChanged(AgentChatAgentOption.GEMINI_2_5_FLASH_LITE))
            viewModel.onAction(AgentChatAction.InputChanged("Hello"))
            viewModel.onAction(AgentChatAction.Submit)
            runCurrent()

            assertEquals(AgentChatAgentOption.GEMINI_2_5_FLASH_LITE, viewModel.uiState.value.selectedAgent)
            assertEquals(AgentChatAgentOption.GEMINI_2_5_FLASH_LITE.modelName, fakeAgent.calls.single().modelName)
        }

    @Test
    fun agentSelectionIsLockedAfterChatStartsAndUnlockedAfterClear() =
        runTest {
            val fakeAgent = FakeLlmAgent()
            val viewModel = createViewModel(fakeAgent)

            viewModel.onAction(AgentChatAction.AgentChanged(AgentChatAgentOption.GEMINI_2_5_FLASH_LITE))
            viewModel.onAction(AgentChatAction.InputChanged("First"))
            viewModel.onAction(AgentChatAction.Submit)
            runCurrent()
            viewModel.onAction(AgentChatAction.AgentChanged(AgentChatAgentOption.GEMINI_2_5_FLASH))

            assertEquals(AgentChatAgentOption.GEMINI_2_5_FLASH_LITE, viewModel.uiState.value.selectedAgent)
            assertTrue(viewModel.uiState.value.isAgentLocked)
            assertFalse(viewModel.uiState.value.canChangeAgent)

            viewModel.onAction(AgentChatAction.ClearChat)
            viewModel.onAction(AgentChatAction.AgentChanged(AgentChatAgentOption.GEMINI_2_5_FLASH))

            assertEquals(AgentChatAgentOption.GEMINI_2_5_FLASH, viewModel.uiState.value.selectedAgent)
            assertTrue(
                viewModel.uiState.value
                    .messages
                    .isEmpty(),
            )
            assertTrue(viewModel.uiState.value.canChangeAgent)
        }

    @Test
    fun initRestoresSavedMessagesAndSelectedAgent() =
        runTest {
            val historyStore =
                FakeAgentChatHistoryStore(
                    AgentChatHistorySnapshot(
                        selectedAgent = AgentChatAgentOption.GEMINI_2_5_FLASH,
                        messages =
                            listOf(
                                AgentChatMessage(role = AgentChatRole.USER, text = "Previous question"),
                                AgentChatMessage(role = AgentChatRole.MODEL, text = "Previous answer"),
                            ),
                    ),
                )
            val viewModel = createViewModel(historyStore = historyStore)

            runCurrent()

            assertEquals(AgentChatAgentOption.GEMINI_2_5_FLASH, viewModel.uiState.value.selectedAgent)
            assertEquals(
                listOf(
                    AgentChatMessage(role = AgentChatRole.USER, text = "Previous question"),
                    AgentChatMessage(role = AgentChatRole.MODEL, text = "Previous answer"),
                ),
                viewModel.uiState.value.messages,
            )
            assertTrue(viewModel.uiState.value.isAgentLocked)
        }

    @Test
    fun restoredHistoryIsSentAfterRestart() =
        runTest {
            val historyStore = FakeAgentChatHistoryStore()
            val firstAgent = FakeLlmAgent(GeminiResult.Success("First answer"))
            val firstViewModel = createViewModel(firstAgent, historyStore)

            firstViewModel.onAction(AgentChatAction.InputChanged("First question"))
            firstViewModel.onAction(AgentChatAction.Submit)
            runCurrent()

            val secondAgent = FakeLlmAgent()
            val restartedViewModel = createViewModel(secondAgent, historyStore)
            runCurrent()
            restartedViewModel.onAction(AgentChatAction.InputChanged("Second question"))
            restartedViewModel.onAction(AgentChatAction.Submit)
            runCurrent()

            assertEquals(
                listOf(
                    AgentMessage.User("First question"),
                    AgentMessage.Model("First answer"),
                    AgentMessage.User("Second question"),
                ),
                secondAgent.calls.single().messages,
            )
        }

    private fun createViewModel(
        fakeAgent: FakeLlmAgent = FakeLlmAgent(),
        historyStore: AgentChatHistoryStore = FakeAgentChatHistoryStore(),
    ): AgentChatViewModel = AgentChatViewModel(fakeAgent, historyStore)

    private class FakeLlmAgent(
        result: AgentResult<String> = GeminiResult.Success("Answer"),
        private val results: ArrayDeque<CompletableDeferred<AgentResult<String>>> =
            ArrayDeque(listOf(CompletableDeferred(result))),
    ) : LlmAgent {
        val calls = mutableListOf<AgentCall>()

        override suspend fun sendMessage(
            messages: List<AgentMessage>,
            generationConfig: GeminiGenerationConfig?,
            modelName: String?,
        ): AgentResult<String> {
            calls += AgentCall(messages = messages, generationConfig = generationConfig, modelName = modelName)
            val result =
                if (results.size > 1) {
                    results.removeFirst()
                } else {
                    results.first()
                }
            return result.await()
        }
    }

    private data class AgentCall(
        val messages: List<AgentMessage>,
        val generationConfig: GeminiGenerationConfig?,
        val modelName: String?,
    )

    private class FakeAgentChatHistoryStore(
        initialSnapshot: AgentChatHistorySnapshot = AgentChatHistorySnapshot(),
    ) : AgentChatHistoryStore {
        var snapshot = initialSnapshot
            private set

        override suspend fun load(): AgentChatHistorySnapshot = snapshot

        override suspend fun save(snapshot: AgentChatHistorySnapshot) {
            this.snapshot = snapshot
        }
    }
}
