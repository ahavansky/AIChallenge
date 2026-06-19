package com.akhavanskii.aichallenge.feature.contextagent

import com.akhavanskii.aichallenge.core.network.AgentMessage
import com.akhavanskii.aichallenge.core.network.AgentResult
import com.akhavanskii.aichallenge.core.network.GeminiGenerationConfig
import com.akhavanskii.aichallenge.core.network.GeminiNetworkError
import com.akhavanskii.aichallenge.core.network.GeminiResult
import com.akhavanskii.aichallenge.core.network.GeminiTokenUsage
import com.akhavanskii.aichallenge.core.network.LlmAgent
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
    fun slidingWindowSubmitSendsAndStoresOnlyRecentMessages() =
        runTest {
            val savedMessages =
                (1..5).flatMap { index ->
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
                                completedText(
                                    text = "Window answer",
                                    tokenUsage =
                                        GeminiTokenUsage(
                                            conversationHistoryTokens = 400,
                                            modelResponseTokens = 20,
                                            totalTokens = 420,
                                        ),
                                ),
                            ),
                        ),
                    tokenCountResults =
                        ArrayDeque(
                            listOf(
                                GeminiResult.Success(1_000),
                                GeminiResult.Success(400),
                            ),
                        ),
                )
            val viewModel =
                createViewModel(
                    fakeAgent = fakeAgent,
                    historyStore = FakeContextAgentHistoryStore(ContextAgentHistorySnapshot(messages = savedMessages)),
                )
            runCurrent()

            viewModel.onAction(ContextAgentAction.InputChanged("Final question"))
            viewModel.onAction(ContextAgentAction.Submit)
            runCurrent()

            val requestMessages = fakeAgent.calls.single().messages
            assertFalse(requestMessages.any { it.text == "Question 1" || it.text == "Question 2" })
            assertTrue(requestMessages.contains(AgentMessage.User("Question 3")))
            assertTrue(requestMessages.contains(AgentMessage.User("Final question")))
            assertEquals(7, requestMessages.size)

            val state = viewModel.uiState.value
            assertEquals(CONTEXT_AGENT_RECENT_MESSAGE_COUNT, state.messages.size)
            assertEquals("Question 3", state.messages.first().text)
            assertEquals("Window answer", state.messages.last().text)
            assertEquals(
                ContextStrategyStats(
                    strategy = ContextManagementStrategy.SLIDING_WINDOW,
                    fullPromptTokens = 1_000,
                    strategyPromptTokens = 400,
                    savedPromptTokens = 600,
                    savedPromptPercent = 60,
                    storedMessageCount = 10,
                    requestMessageCount = 7,
                    droppedMessageCount = 4,
                ),
                state.strategyStats,
            )
        }

    @Test
    fun stickyFactsSubmitUpdatesFactsAndSendsFactsWithRecentWindow() =
        runTest {
            val fakeAgent =
                FakeLlmAgent(
                    results = ArrayDeque(listOf(completedText("Facts answer"))),
                    tokenCountResults =
                        ArrayDeque(
                            listOf(
                                GeminiResult.Success(120),
                                GeminiResult.Success(90),
                            ),
                        ),
                )
            val viewModel =
                createViewModel(
                    fakeAgent = fakeAgent,
                    historyStore =
                        FakeContextAgentHistoryStore(
                            ContextAgentHistorySnapshot(
                                selectedStrategy = ContextManagementStrategy.STICKY_FACTS,
                            ),
                        ),
                )
            runCurrent()

            viewModel.onAction(
                ContextAgentAction.InputChanged(
                    "Цель собрать ТЗ. Ограничение без оплаты в MVP.",
                ),
            )
            viewModel.onAction(ContextAgentAction.Submit)
            runCurrent()

            val requestMessages = fakeAgent.calls.single().messages
            val factsBlock = requestMessages.first() as AgentMessage.User
            assertTrue(factsBlock.text.contains("Sticky facts memory"))
            assertTrue(factsBlock.text.contains("- goal:"))
            assertTrue(factsBlock.text.contains("- constraints:"))
            assertEquals(
                "Facts answer",
                viewModel.uiState.value.messages
                    .last()
                    .text,
            )
            assertTrue(
                viewModel.uiState.value.facts
                    .any { it.key == "goal" },
            )
            assertTrue(
                viewModel.uiState.value.facts
                    .any { it.key == "constraints" },
            )
            assertEquals(
                2,
                viewModel.uiState.value.strategyStats
                    ?.factsCount,
            )
        }

    @Test
    fun branchingCheckpointKeepsTwoBranchesIndependent() =
        runTest {
            val fakeAgent =
                FakeLlmAgent(
                    results =
                        ArrayDeque(
                            listOf(
                                completedText("Base answer"),
                                completedText("Branch A answer"),
                                completedText("Branch B answer"),
                            ),
                        ),
                    tokenCountResults =
                        ArrayDeque(
                            listOf(
                                GeminiResult.Success(10),
                                GeminiResult.Success(10),
                                GeminiResult.Success(20),
                                GeminiResult.Success(20),
                                GeminiResult.Success(30),
                                GeminiResult.Success(30),
                            ),
                        ),
                )
            val viewModel =
                createViewModel(
                    fakeAgent = fakeAgent,
                    historyStore =
                        FakeContextAgentHistoryStore(
                            ContextAgentHistorySnapshot(
                                selectedStrategy = ContextManagementStrategy.BRANCHING,
                            ),
                        ),
                )
            runCurrent()

            viewModel.onAction(ContextAgentAction.InputChanged("Shared context"))
            viewModel.onAction(ContextAgentAction.Submit)
            runCurrent()
            viewModel.onAction(ContextAgentAction.SaveCheckpoint)
            viewModel.onAction(ContextAgentAction.CreateBranches)
            viewModel.onAction(ContextAgentAction.InputChanged("Branch A decision"))
            viewModel.onAction(ContextAgentAction.Submit)
            runCurrent()
            viewModel.onAction(ContextAgentAction.BranchChanged(ContextBranchId.B))
            viewModel.onAction(ContextAgentAction.InputChanged("Branch B decision"))
            viewModel.onAction(ContextAgentAction.Submit)
            runCurrent()

            val state = viewModel.uiState.value
            val branchA = state.branchingState.branches.first { it.id == ContextBranchId.A }
            val branchB = state.branchingState.branches.first { it.id == ContextBranchId.B }

            assertEquals(2, state.branchingState.checkpointMessages.size)
            assertTrue(branchA.messages.any { it.text == "Branch A answer" })
            assertFalse(branchA.messages.any { it.text == "Branch B answer" })
            assertTrue(branchB.messages.any { it.text == "Branch B answer" })
            assertFalse(branchB.messages.any { it.text == "Branch A answer" })
            assertEquals(ContextBranchId.B, state.branchingState.activeBranchId)
            assertTrue(state.activeMessages.any { it.text == "Base answer" })
            assertTrue(state.activeMessages.any { it.text == "Branch B answer" })
        }

    @Test
    fun scenarioComparisonRunsSameScenarioAcrossStrategies() =
        runTest {
            val fakeAgent =
                FakeLlmAgent(
                    results =
                        ArrayDeque(
                            listOf(
                                completedText("Sliding spec"),
                                completedText("Facts spec"),
                                completedText("Branch A spec"),
                                completedText("Branch B spec"),
                            ),
                        ),
                    tokenCountResults =
                        ArrayDeque(
                            listOf(
                                GeminiResult.Success(300),
                                GeminiResult.Success(420),
                                GeminiResult.Success(520),
                                GeminiResult.Success(540),
                            ),
                        ),
                )
            val viewModel = createViewModel(fakeAgent = fakeAgent)
            runCurrent()

            viewModel.onAction(ContextAgentAction.RunScenarioComparison)
            runCurrent()

            val comparison = viewModel.uiState.value.comparison
            requireNotNull(comparison)
            assertEquals(4, fakeAgent.calls.size)
            assertEquals(4, comparison.reports.size)
            assertEquals(ContextManagementStrategy.SLIDING_WINDOW, comparison.reports[0].strategy)
            assertEquals(ContextManagementStrategy.STICKY_FACTS, comparison.reports[1].strategy)
            assertEquals(ContextManagementStrategy.BRANCHING, comparison.reports[2].strategy)
            assertEquals(ContextBranchId.A.title, comparison.reports[2].branchTitle)
            assertEquals(ContextBranchId.B.title, comparison.reports[3].branchTitle)

            assertFalse(fakeAgent.calls[0].messages.any { it.text.contains("Цель: собрать ТЗ") })
            assertTrue(
                fakeAgent.calls[1]
                    .messages
                    .first()
                    .text
                    .contains("Sticky facts memory"),
            )
            assertTrue(fakeAgent.calls[2].messages.any { it.text.contains("Ветка A") })
            assertTrue(fakeAgent.calls[3].messages.any { it.text.contains("Ветка B") })
            assertTrue(comparison.evaluation.contains("Sliding Window"))
        }

    @Test
    fun submitUsesCappedOutputBudgetForGemma31() =
        runTest {
            val fakeAgent =
                FakeLlmAgent(
                    results = ArrayDeque(listOf(completedText("Gemma answer"))),
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

            assertEquals(ContextAgentModelOption.GEMMA_4_31B_IT.modelName, call.modelName)
            assertEquals(ContextAgentModelOption.GEMMA_4_31B_IT.inputTokenLimit, call.totalTokenLimit)
            assertEquals(1_024, call.generationConfig?.maxOutputTokens)
        }

    @Test
    fun submitShowsProviderInstabilityMessageForGemma31InternalError() =
        runTest {
            val fakeAgent =
                FakeLlmAgent(
                    results =
                        ArrayDeque(
                            listOf(
                                GeminiResult.Failure(GeminiNetworkError.Http(statusCode = 500, body = "{}")),
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

            val lastMessage =
                viewModel.uiState.value.messages
                    .last()

            assertTrue(lastMessage.isError)
            assertTrue(lastMessage.text.contains("Gemma 4 31B returned provider HTTP 500"))
            assertTrue(lastMessage.text.contains("not context management"))
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
    ): AgentResult<String> = GeminiResult.Success(text, tokenUsage = tokenUsage)

    private class FakeLlmAgent(
        private val results: ArrayDeque<AgentResult<String>> =
            ArrayDeque(listOf(GeminiResult.Success("Answer"))),
        private val tokenCountResults: ArrayDeque<AgentResult<Int>> =
            ArrayDeque(listOf(GeminiResult.Success(100))),
    ) : LlmAgent {
        val calls = mutableListOf<AgentCall>()
        val tokenCountCalls = mutableListOf<TokenCountCall>()

        override suspend fun countTokens(
            messages: List<AgentMessage>,
            systemInstruction: String?,
            modelName: String?,
        ): AgentResult<Int> {
            tokenCountCalls +=
                TokenCountCall(
                    messages = messages,
                    systemInstruction = systemInstruction,
                    modelName = modelName,
                )
            return if (tokenCountResults.size > 1) {
                tokenCountResults.removeFirst()
            } else {
                tokenCountResults.first()
            }
        }

        override suspend fun sendMessage(
            messages: List<AgentMessage>,
            systemInstruction: String?,
            generationConfig: GeminiGenerationConfig?,
            modelName: String?,
            totalTokenLimit: Int?,
        ): AgentResult<String> {
            calls +=
                AgentCall(
                    messages = messages,
                    systemInstruction = systemInstruction,
                    generationConfig = generationConfig,
                    modelName = modelName,
                    totalTokenLimit = totalTokenLimit,
                )
            return if (results.size > 1) {
                results.removeFirst()
            } else {
                results.first()
            }
        }
    }

    private data class AgentCall(
        val messages: List<AgentMessage>,
        val systemInstruction: String?,
        val generationConfig: GeminiGenerationConfig?,
        val modelName: String?,
        val totalTokenLimit: Int?,
    )

    private data class TokenCountCall(
        val messages: List<AgentMessage>,
        val systemInstruction: String?,
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
