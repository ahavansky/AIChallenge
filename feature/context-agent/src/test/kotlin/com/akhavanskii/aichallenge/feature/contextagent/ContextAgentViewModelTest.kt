package com.akhavanskii.aichallenge.feature.contextagent

import com.akhavanskii.aichallenge.core.network.AgentMessage
import com.akhavanskii.aichallenge.core.network.AgentResult
import com.akhavanskii.aichallenge.core.network.GeminiGenerationConfig
import com.akhavanskii.aichallenge.core.network.GeminiNetworkError
import com.akhavanskii.aichallenge.core.network.GeminiResult
import com.akhavanskii.aichallenge.core.network.GeminiTokenUsage
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
class ContextAgentViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun compressedSubmitSummarizesOldMessagesAndSendsSummaryWithRawTail() =
        runTest {
            val savedMessages =
                (1..9).flatMap { index ->
                    listOf(
                        ContextAgentMessage(role = ContextAgentRole.USER, text = "Question $index"),
                        ContextAgentMessage(role = ContextAgentRole.MODEL, text = "Answer $index"),
                    )
                }
            val historyStore =
                FakeContextAgentHistoryStore(
                    ContextAgentHistorySnapshot(messages = savedMessages),
                )
            val fakeAgent =
                FakeLlmAgent(
                    results =
                        ArrayDeque(
                            listOf(
                                completedText("Summary through answer 5"),
                                completedText(
                                    text = "Compressed final answer",
                                    tokenUsage =
                                        GeminiTokenUsage(
                                            conversationHistoryTokens = 420,
                                            modelResponseTokens = 30,
                                            totalTokens = 450,
                                        ),
                                ),
                            ),
                        ),
                    tokenCountResults =
                        ArrayDeque(
                            listOf(
                                GeminiResult.Success(1_200),
                                GeminiResult.Success(420),
                            ),
                        ),
                )
            val viewModel = createViewModel(fakeAgent = fakeAgent, historyStore = historyStore)
            runCurrent()

            viewModel.onAction(ContextAgentAction.InputChanged("Next question"))
            viewModel.onAction(ContextAgentAction.Submit)
            runCurrent()

            assertEquals(2, fakeAgent.calls.size)
            assertTrue(
                fakeAgent.calls[0]
                    .messages
                    .single()
                    .text
                    .contains("Question 1"),
            )
            assertTrue(
                fakeAgent.calls[0]
                    .messages
                    .single()
                    .text
                    .contains("Answer 5"),
            )
            assertFalse(
                fakeAgent.calls[0]
                    .messages
                    .single()
                    .text
                    .contains("Question 6"),
            )

            val compressedMessages = fakeAgent.calls[1].messages
            assertTrue((compressedMessages.first() as AgentMessage.User).text.contains("Summary through answer 5"))
            assertFalse(compressedMessages.any { it.text == "Question 1" || it.text == "Answer 5" })
            assertTrue(compressedMessages.contains(AgentMessage.User("Question 6")))
            assertTrue(compressedMessages.contains(AgentMessage.User("Next question")))
            assertEquals(
                ContextCompressionStats(
                    fullPromptTokens = 1_200,
                    compressedPromptTokens = 420,
                    savedPromptTokens = 780,
                    savedPromptPercent = 65,
                    summarizedMessageCount = 10,
                    rawMessageCount = 9,
                    requestMessageCount = 10,
                ),
                viewModel.uiState.value.contextState.latestStats,
            )
            assertEquals("Summary through answer 5", viewModel.uiState.value.contextState.summary)
            assertEquals(
                "Compressed final answer",
                viewModel.uiState.value.messages
                    .last()
                    .text,
            )
            assertEquals(viewModel.uiState.value.contextState, historyStore.snapshot.contextState)
        }

    @Test
    fun submitUsesCappedOutputBudgetForGemma31() =
        runTest {
            val fakeAgent =
                FakeLlmAgent(
                    result =
                        GeminiResult.Success(
                            value = "Gemma answer",
                            tokenUsage =
                                GeminiTokenUsage(
                                    conversationHistoryTokens = 27,
                                    modelResponseTokens = 20,
                                    totalTokens = 47,
                                ),
                        ),
                    tokenCountResults =
                        ArrayDeque(
                            listOf(
                                GeminiResult.Success(27),
                                GeminiResult.Success(27),
                            ),
                        ),
                )
            val viewModel =
                createViewModel(
                    fakeAgent = fakeAgent,
                    historyStore =
                        FakeContextAgentHistoryStore(
                            ContextAgentHistorySnapshot(
                                selectedModel = ContextAgentModelOption.GEMMA_4_31B_IT,
                            ),
                        ),
                )
            runCurrent()

            viewModel.onAction(ContextAgentAction.InputChanged("Project constraints"))
            viewModel.onAction(ContextAgentAction.Submit)
            runCurrent()

            val call = fakeAgent.calls.single()
            val state = viewModel.uiState.value
            val lastMessage = state.messages.last()

            assertEquals(1, fakeAgent.calls.size)
            assertEquals(ContextAgentModelOption.GEMMA_4_31B_IT.modelName, call.modelName)
            assertEquals(ContextAgentModelOption.GEMMA_4_31B_IT.inputTokenLimit, call.totalTokenLimit)
            assertEquals(1_024, call.generationConfig?.maxOutputTokens)
            assertEquals("Gemma answer", lastMessage.text)
        }

    @Test
    fun submitShowsProviderInstabilityMessageForGemma31InternalError() =
        runTest {
            val fakeAgent =
                FakeLlmAgent(
                    result = GeminiResult.Failure(GeminiNetworkError.Http(statusCode = 500, body = "{}")),
                    tokenCountResults =
                        ArrayDeque(
                            listOf(
                                GeminiResult.Success(27),
                                GeminiResult.Success(27),
                            ),
                        ),
                )
            val viewModel =
                createViewModel(
                    fakeAgent = fakeAgent,
                    historyStore =
                        FakeContextAgentHistoryStore(
                            ContextAgentHistorySnapshot(
                                selectedModel = ContextAgentModelOption.GEMMA_4_31B_IT,
                            ),
                        ),
                )
            runCurrent()

            viewModel.onAction(ContextAgentAction.InputChanged("Project constraints"))
            viewModel.onAction(ContextAgentAction.Submit)
            runCurrent()

            val state = viewModel.uiState.value
            val lastMessage = state.messages.last()

            assertTrue(lastMessage.isError)
            assertTrue(lastMessage.text.contains("Gemma 4 31B returned provider HTTP 500"))
            assertTrue(lastMessage.text.contains("not context compression"))
        }

    @Test
    fun runComparisonProducesFullCompressedAndQualityOutputs() =
        runTest {
            val savedMessages =
                (1..9).flatMap { index ->
                    listOf(
                        ContextAgentMessage(role = ContextAgentRole.USER, text = "Question $index"),
                        ContextAgentMessage(role = ContextAgentRole.MODEL, text = "Answer $index"),
                    )
                }
            val fakeAgent =
                FakeLlmAgent(
                    results =
                        ArrayDeque(
                            listOf(
                                completedText("Full answer keeps all facts"),
                                completedText("Summary of old comparison facts"),
                                completedText("Compressed answer keeps facts"),
                                completedText("Quality is similar; compressed saves tokens."),
                            ),
                        ),
                    tokenCountResults =
                        ArrayDeque(
                            listOf(
                                GeminiResult.Success(900),
                                GeminiResult.Success(300),
                            ),
                        ),
                )
            val historyStore =
                FakeContextAgentHistoryStore(
                    ContextAgentHistorySnapshot(messages = savedMessages),
                )
            val viewModel = createViewModel(fakeAgent = fakeAgent, historyStore = historyStore)
            runCurrent()

            viewModel.onAction(ContextAgentAction.InputChanged("Compare the next step"))
            viewModel.onAction(ContextAgentAction.RunComparison)
            runCurrent()

            assertEquals(4, fakeAgent.calls.size)
            assertEquals(2, fakeAgent.tokenCountCalls.size)
            assertTrue(
                fakeAgent.calls[0]
                    .messages
                    .first()
                    .text == "Question 1",
            )
            assertTrue(
                fakeAgent.calls[0]
                    .messages
                    .last()
                    .text == "Compare the next step",
            )
            assertEquals(4_096, fakeAgent.calls[0].generationConfig?.maxOutputTokens)
            assertEquals(4_096, fakeAgent.calls[2].generationConfig?.maxOutputTokens)
            assertEquals(2_048, fakeAgent.calls[3].generationConfig?.maxOutputTokens)
            assertTrue(
                fakeAgent.calls[2]
                    .messages
                    .first()
                    .text
                    .contains("Summary of old comparison facts"),
            )
            assertTrue(
                fakeAgent.calls[3]
                    .messages
                    .single()
                    .text
                    .contains("Full-history answer"),
            )
            assertEquals(
                ContextCompressionStats(
                    fullPromptTokens = 900,
                    compressedPromptTokens = 300,
                    savedPromptTokens = 600,
                    savedPromptPercent = 66,
                    summarizedMessageCount = 10,
                    rawMessageCount = 9,
                    requestMessageCount = 10,
                ),
                viewModel.uiState.value.contextState.latestStats,
            )
            assertEquals(
                ContextQualityComparison(
                    fullHistoryAnswer = "Full answer keeps all facts",
                    compressedHistoryAnswer = "Compressed answer keeps facts",
                    evaluation = "Quality is similar; compressed saves tokens.",
                ),
                viewModel.uiState.value.comparison,
            )
            val messages = viewModel.uiState.value.messages
            val answerWithoutCompression = messages[messages.lastIndex - 2]
            val answerWithCompression = messages[messages.lastIndex - 1]
            val qualityComparison = messages.last()

            assertEquals(
                "Compare the next step",
                messages
                    .drop(savedMessages.size)
                    .first()
                    .text,
            )
            assertEquals(
                "Answer without compression\n\nFull answer keeps all facts",
                answerWithoutCompression.text,
            )
            assertFalse(answerWithoutCompression.includeInContext)
            assertEquals(
                "Answer with compression\n\nCompressed answer keeps facts",
                answerWithCompression.text,
            )
            assertTrue(answerWithCompression.includeInContext)
            assertEquals(
                "Quality comparison\n\nQuality is similar; compressed saves tokens.",
                qualityComparison.text,
            )
            assertFalse(qualityComparison.includeInContext)
            assertEquals(viewModel.uiState.value.comparison, historyStore.snapshot.comparison)
        }

    @Test
    fun runComparisonUsesLowerOutputBudgetForGemma31() =
        runTest {
            val savedMessages =
                (1..9).flatMap { index ->
                    listOf(
                        ContextAgentMessage(role = ContextAgentRole.USER, text = "Question $index"),
                        ContextAgentMessage(role = ContextAgentRole.MODEL, text = "Answer $index"),
                    )
                }
            val fakeAgent =
                FakeLlmAgent(
                    results =
                        ArrayDeque(
                            listOf(
                                completedText("Full answer"),
                                completedText("Summary"),
                                completedText("Compressed answer"),
                                completedText("Quality"),
                            ),
                        ),
                    tokenCountResults =
                        ArrayDeque(
                            listOf(
                                GeminiResult.Success(900),
                                GeminiResult.Success(300),
                            ),
                        ),
                )
            val viewModel =
                createViewModel(
                    fakeAgent = fakeAgent,
                    historyStore =
                        FakeContextAgentHistoryStore(
                            ContextAgentHistorySnapshot(
                                messages = savedMessages,
                                selectedModel = ContextAgentModelOption.GEMMA_4_31B_IT,
                            ),
                        ),
                )
            runCurrent()

            viewModel.onAction(ContextAgentAction.InputChanged("Compare the next step"))
            viewModel.onAction(ContextAgentAction.RunComparison)
            runCurrent()

            assertEquals(1_024, fakeAgent.calls[0].generationConfig?.maxOutputTokens)
            assertEquals(1_024, fakeAgent.calls[2].generationConfig?.maxOutputTokens)
            assertEquals(1_024, fakeAgent.calls[3].generationConfig?.maxOutputTokens)
            assertTrue(fakeAgent.calls.all { it.modelName == ContextAgentModelOption.GEMMA_4_31B_IT.modelName })
        }

    @Test
    fun modelSelectionIsLockedAfterChatStarts() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onAction(ContextAgentAction.ModelChanged(ContextAgentModelOption.GEMINI_2_5_FLASH_LITE))
            viewModel.onAction(ContextAgentAction.InputChanged("Hello"))
            viewModel.onAction(ContextAgentAction.Submit)
            runCurrent()
            viewModel.onAction(ContextAgentAction.ModelChanged(ContextAgentModelOption.GEMMA_4_31B_IT))

            assertEquals(ContextAgentModelOption.GEMINI_2_5_FLASH_LITE, viewModel.uiState.value.selectedModel)
            assertFalse(viewModel.uiState.value.canChangeModel)
        }

    private fun createViewModel(
        fakeAgent: FakeLlmAgent = FakeLlmAgent(),
        historyStore: ContextAgentHistoryStore = FakeContextAgentHistoryStore(),
    ): ContextAgentViewModel = ContextAgentViewModel(fakeAgent, historyStore)

    private fun completedText(
        text: String,
        tokenUsage: GeminiTokenUsage? = null,
    ): CompletableDeferred<AgentResult<String>> = CompletableDeferred(GeminiResult.Success(text, tokenUsage = tokenUsage))

    private class FakeLlmAgent(
        result: AgentResult<String> = GeminiResult.Success("Answer"),
        private val results: ArrayDeque<CompletableDeferred<AgentResult<String>>> =
            ArrayDeque(listOf(CompletableDeferred(result))),
        private val tokenCountResults: ArrayDeque<AgentResult<Int>> =
            ArrayDeque(listOf(GeminiResult.Success(100))),
    ) : LlmAgent {
        val calls = mutableListOf<AgentCall>()
        val tokenCountCalls = mutableListOf<TokenCountCall>()

        override suspend fun countTokens(
            messages: List<AgentMessage>,
            modelName: String?,
        ): AgentResult<Int> {
            tokenCountCalls += TokenCountCall(messages = messages, modelName = modelName)
            return if (tokenCountResults.size > 1) {
                tokenCountResults.removeFirst()
            } else {
                tokenCountResults.first()
            }
        }

        override suspend fun sendMessage(
            messages: List<AgentMessage>,
            generationConfig: GeminiGenerationConfig?,
            modelName: String?,
            totalTokenLimit: Int?,
        ): AgentResult<String> {
            calls +=
                AgentCall(
                    messages = messages,
                    generationConfig = generationConfig,
                    modelName = modelName,
                    totalTokenLimit = totalTokenLimit,
                )
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
        val totalTokenLimit: Int?,
    )

    private data class TokenCountCall(
        val messages: List<AgentMessage>,
        val modelName: String?,
    )

    private class FakeContextAgentHistoryStore(
        initialSnapshot: ContextAgentHistorySnapshot = ContextAgentHistorySnapshot(),
    ) : ContextAgentHistoryStore {
        var snapshot = initialSnapshot
            private set

        override suspend fun load(): ContextAgentHistorySnapshot = snapshot

        override suspend fun save(snapshot: ContextAgentHistorySnapshot) {
            this.snapshot = snapshot
        }
    }
}
