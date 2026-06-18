package com.akhavanskii.aichallenge.feature.agentchat

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
    fun submitAttachesTokenUsageToModelResponse() =
        runTest {
            val tokenUsage =
                GeminiTokenUsage(
                    currentRequestTokens = 3,
                    conversationHistoryTokens = 11,
                    modelResponseTokens = 5,
                    totalTokens = 16,
                )
            val fakeAgent = FakeLlmAgent(GeminiResult.Success("Answer", tokenUsage = tokenUsage))
            val viewModel = createViewModel(fakeAgent)

            viewModel.onAction(AgentChatAction.InputChanged("Hello"))
            viewModel.onAction(AgentChatAction.Submit)
            runCurrent()

            assertEquals(
                AgentChatMessage(
                    role = AgentChatRole.MODEL,
                    text = "Answer",
                    tokenUsage = tokenUsage,
                ),
                viewModel.uiState.value.messages[1],
            )
        }

    @Test
    fun tokenLimitChangedStoresSanitizedCustomLimit() =
        runTest {
            val historyStore = FakeAgentChatHistoryStore()
            val viewModel = createViewModel(historyStore = historyStore)

            viewModel.onAction(AgentChatAction.TokenLimitChanged("12,345 tokens"))
            runCurrent()

            assertEquals(12_345, viewModel.uiState.value.customTotalTokenLimit)
            assertEquals(12_345, viewModel.uiState.value.effectiveTotalTokenLimit)
            assertEquals(12_345, historyStore.snapshot.customTotalTokenLimit)
        }

    @Test
    fun tokenLimitChangedClampsToSelectedModelLimit() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onAction(AgentChatAction.TokenLimitChanged("9999999999"))
            runCurrent()

            assertEquals(
                AgentChatAgentOption.GEMINI_3_5_FLASH.totalTokenLimit,
                viewModel.uiState.value.customTotalTokenLimit,
            )
        }

    @Test
    fun blankTokenLimitUsesModelLimit() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onAction(AgentChatAction.TokenLimitChanged("100"))
            viewModel.onAction(AgentChatAction.TokenLimitChanged(""))
            runCurrent()

            assertEquals(null, viewModel.uiState.value.customTotalTokenLimit)
            assertEquals(
                AgentChatAgentOption.GEMINI_3_5_FLASH.totalTokenLimit,
                viewModel.uiState.value.effectiveTotalTokenLimit,
            )
        }

    @Test
    fun shortScenarioRunsRealAgentTurnsWithGrowingTokenUsage() =
        runTest {
            val fakeAgent =
                FakeLlmAgent(
                    results =
                        ArrayDeque(
                            listOf(
                                completedResult("First live answer", totalTokens = 180),
                                completedResult("Second live answer", totalTokens = 420),
                                completedResult("Third live answer", totalTokens = 690),
                            ),
                        ),
                )
            val historyStore = FakeAgentChatHistoryStore()
            val viewModel = createViewModel(fakeAgent = fakeAgent, historyStore = historyStore)

            viewModel.onAction(AgentChatAction.TokenLimitChanged("1000"))
            viewModel.onAction(AgentChatAction.ScenarioSelected(AgentChatScenario.SHORT))
            runCurrent()

            assertEquals(3, fakeAgent.calls.size)
            assertEquals(1_000, fakeAgent.calls.single { it.messages.size == 1 }.totalTokenLimit)
            assertTrue(
                (fakeAgent.calls[0].messages.single() as AgentMessage.User)
                    .text
                    .startsWith("Scenario Short dialog, turn 1"),
            )
            assertEquals(
                listOf(
                    fakeAgent.calls[0].messages[0],
                    AgentMessage.Model("First live answer"),
                    fakeAgent.calls[1].messages[2],
                ),
                fakeAgent.calls[1].messages,
            )
            assertTrue(fakeAgent.calls[2].messages.contains(AgentMessage.Model("Second live answer")))
            assertEquals(1_000, viewModel.uiState.value.customTotalTokenLimit)
            assertEquals(
                690,
                viewModel.uiState.value.latestTokenUsage
                    ?.totalTokens,
            )
            assertEquals(310, viewModel.uiState.value.remainingTokenBudget)
            assertTrue(
                viewModel.uiState.value
                    .messages
                    .last()
                    .text
                    .contains("Third live answer"),
            )
            assertEquals(viewModel.uiState.value.messages, historyStore.snapshot.messages)
        }

    @Test
    fun longScenarioCapsHighLimitWithoutApplyingSlidingWindow() =
        runTest {
            val scenarioLimit = 1_500
            val fakeAgent =
                FakeLlmAgent(
                    results =
                        ArrayDeque(
                            listOf(
                                completedResult(
                                    text = "Long overflow answer",
                                    totalTokens = scenarioLimit + 160,
                                ),
                            ),
                        ),
                )
            val viewModel = createViewModel(fakeAgent = fakeAgent)

            viewModel.onAction(AgentChatAction.TokenLimitChanged("20000"))
            viewModel.onAction(AgentChatAction.ScenarioSelected(AgentChatScenario.LONG))
            runCurrent()

            assertEquals(6, fakeAgent.calls.size)
            assertEquals(scenarioLimit, viewModel.uiState.value.customTotalTokenLimit)
            assertTrue(fakeAgent.calls.all { it.totalTokenLimit == null })
            assertTrue(
                (fakeAgent.calls[0].messages.single() as AgentMessage.User)
                    .text
                    .contains("long1_179"),
            )
            assertFalse(
                viewModel.uiState.value.messages.any { message ->
                    message.isError && message.text.contains("Full-history budget reached")
                },
            )
            assertEquals(
                scenarioLimit + 160,
                viewModel.uiState.value.latestTokenUsage
                    ?.totalTokens,
            )
            assertFalse(
                viewModel.uiState.value.latestTokenUsage
                    ?.slidingWindowApplied == true,
            )
            assertTrue(viewModel.uiState.value.isTokenLimitReached)
        }

    @Test
    fun overLimitScenarioRunsRealAgentTurnsAndShowsSlidingWindowNotice() =
        runTest {
            val requestedLimit = 2_400
            val scenarioLimit = 1_500
            val fakeAgent =
                FakeLlmAgent(
                    results =
                        ArrayDeque(
                            listOf(
                                completedResult("Large context answer", totalTokens = 900),
                                completedResult(
                                    text = "Context keeps growing",
                                    totalTokens = 1_350,
                                ),
                                completedResult(
                                    text = "Sliding window answer",
                                    totalTokens = scenarioLimit,
                                    slidingWindowApplied = true,
                                ),
                                completedResult(
                                    text = "Final answer after trimming",
                                    totalTokens = scenarioLimit,
                                    slidingWindowApplied = true,
                                ),
                            ),
                        ),
                )
            val viewModel = createViewModel(fakeAgent = fakeAgent)

            viewModel.onAction(AgentChatAction.TokenLimitChanged(requestedLimit.toString()))
            viewModel.onAction(AgentChatAction.ScenarioSelected(AgentChatScenario.OVER_MODEL_LIMIT))
            runCurrent()

            assertEquals(4, fakeAgent.calls.size)
            assertEquals(scenarioLimit, viewModel.uiState.value.customTotalTokenLimit)
            assertTrue(
                (fakeAgent.calls[0].messages.single() as AgentMessage.User)
                    .text
                    .startsWith("Scenario Over model limit, turn 1"),
            )
            assertTrue(
                (fakeAgent.calls[0].messages.single() as AgentMessage.User)
                    .text
                    .contains("anchor1_219"),
            )
            assertTrue(fakeAgent.calls[2].messages.contains(AgentMessage.Model("Context keeps growing")))
            assertTrue(fakeAgent.calls.all { it.totalTokenLimit == scenarioLimit })
            assertTrue(fakeAgent.calls.all { it.generationConfig?.maxOutputTokens == 160 })
            assertTrue(
                viewModel.uiState.value.messages.any { message ->
                    message.isError && message.text.contains("Full-history budget reached")
                },
            )
            assertTrue(
                viewModel.uiState.value
                    .messages
                    .last()
                    .text
                    .contains("Final answer after trimming"),
            )
            assertEquals(
                scenarioLimit,
                viewModel.uiState.value.latestTokenUsage
                    ?.totalTokens,
            )
            assertTrue(
                viewModel.uiState.value.latestTokenUsage
                    ?.slidingWindowApplied == true,
            )
        }

    @Test
    fun overLimitScenarioUsesDemoLimitWhenUserDidNotEnterCustomLimit() =
        runTest {
            val scenarioLimit = 1_500
            val fakeAgent =
                FakeLlmAgent(
                    results =
                        ArrayDeque(
                            listOf(
                                completedResult(
                                    text = "Demo overflow answer",
                                    totalTokens = scenarioLimit,
                                    slidingWindowApplied = true,
                                ),
                            ),
                        ),
                )
            val viewModel = createViewModel(fakeAgent = fakeAgent)

            viewModel.onAction(AgentChatAction.ScenarioSelected(AgentChatScenario.OVER_MODEL_LIMIT))
            runCurrent()

            assertEquals(4, fakeAgent.calls.size)
            assertTrue(fakeAgent.calls.all { it.totalTokenLimit == scenarioLimit })
            assertEquals(scenarioLimit, viewModel.uiState.value.customTotalTokenLimit)
        }

    @Test
    fun stopCancelsRunningScenarioAndIgnoresLateResponse() =
        runTest {
            val firstResponse = CompletableDeferred<AgentResult<String>>()
            val fakeAgent =
                FakeLlmAgent(
                    results =
                        ArrayDeque(
                            listOf(
                                firstResponse,
                                completedResult("Second answer should not run", totalTokens = 420),
                            ),
                        ),
                )
            val historyStore = FakeAgentChatHistoryStore()
            val viewModel = createViewModel(fakeAgent = fakeAgent, historyStore = historyStore)

            viewModel.onAction(AgentChatAction.ScenarioSelected(AgentChatScenario.LONG))
            runCurrent()

            assertEquals(1, fakeAgent.calls.size)
            assertTrue(viewModel.uiState.value.isLoading)
            assertTrue(viewModel.uiState.value.canStop)

            viewModel.onAction(AgentChatAction.Stop)
            runCurrent()

            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(
                viewModel.uiState.value
                    .messages
                    .last()
                    .isError,
            )
            assertEquals(
                "Stopped by user.",
                viewModel.uiState.value
                    .messages
                    .last()
                    .text,
            )
            assertEquals(viewModel.uiState.value.messages, historyStore.snapshot.messages)

            firstResponse.complete(GeminiResult.Success("Late answer should be ignored"))
            runCurrent()

            assertEquals(1, fakeAgent.calls.size)
            assertFalse(
                viewModel.uiState.value.messages.any { message ->
                    message.text.contains("Late answer should be ignored") ||
                        message.text.contains("Second answer should not run")
                },
            )
        }

    @Test
    fun submitPassesCustomTokenLimitWhenPreviousUsageReachedBudget() =
        runTest {
            val fakeAgent = FakeLlmAgent()
            val viewModel =
                createViewModel(
                    fakeAgent = fakeAgent,
                    historyStore =
                        FakeAgentChatHistoryStore(
                            AgentChatHistorySnapshot(
                                customTotalTokenLimit = 10,
                                messages =
                                    listOf(
                                        AgentChatMessage(role = AgentChatRole.USER, text = "Previous"),
                                        AgentChatMessage(
                                            role = AgentChatRole.MODEL,
                                            text = "Answer",
                                            tokenUsage = GeminiTokenUsage(totalTokens = 10),
                                        ),
                                    ),
                            ),
                        ),
                )
            runCurrent()

            viewModel.onAction(AgentChatAction.InputChanged("Next"))
            viewModel.onAction(AgentChatAction.Submit)
            runCurrent()

            assertTrue(viewModel.uiState.value.isTokenLimitReached)
            assertEquals(
                listOf(
                    AgentMessage.User("Previous"),
                    AgentMessage.Model("Answer"),
                    AgentMessage.User("Next"),
                ),
                fakeAgent.calls.single().messages,
            )
            assertEquals(10, fakeAgent.calls.single().totalTokenLimit)
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
    fun submitUsesTaskContextAndLongTermMarkdownInPrompt() =
        runTest {
            val fakeAgent = FakeLlmAgent()
            val historyStore = FakeAgentChatHistoryStore()
            val longTermMemoryStore =
                FakeAgentChatLongTermMemoryStore(
                    AgentChatLongTermMarkdown(
                        markdown =
                            """
                            # Preferences

                            - Answer with concise Kotlin examples.
                            """.trimIndent(),
                    ),
                )
            val viewModel =
                createViewModel(
                    fakeAgent = fakeAgent,
                    historyStore = historyStore,
                    longTermMemoryStore = longTermMemoryStore,
                )
            runCurrent()

            viewModel.onAction(
                AgentChatAction.TaskContextChanged(
                    """
                    Goal: build a memory layer demo
                    Stage: implementation
                    Constraint: tests must stay offline
                    """.trimIndent(),
                ),
            )
            viewModel.onAction(AgentChatAction.InputChanged("Create the plan"))
            viewModel.onAction(AgentChatAction.Submit)
            runCurrent()

            val memory = viewModel.uiState.value.memory
            assertEquals("build a memory layer demo", memory.taskContext.goal)
            assertEquals("implementation", memory.taskContext.stage)
            assertEquals(listOf("tests must stay offline"), memory.taskContext.constraints)
            assertEquals(
                listOf(
                    AgentChatMemoryLayer.LONG_TERM_MARKDOWN,
                    AgentChatMemoryLayer.TASK_CONTEXT,
                ),
                memory.lastRequest?.includedLayers,
            )
            assertTrue(
                (fakeAgent.calls.single().messages[0] as AgentMessage.User)
                    .text
                    .contains("Answer with concise Kotlin examples"),
            )
            assertTrue(
                (fakeAgent.calls.single().messages[1] as AgentMessage.User)
                    .text
                    .contains("TaskContext"),
            )
            assertEquals(
                AgentMessage.User("Create the plan"),
                fakeAgent
                    .calls
                    .single()
                    .messages
                    .last(),
            )
            assertEquals(memory.taskContext, historyStore.snapshot.memory.taskContext)
        }

    @Test
    fun saveLongTermMemoryWritesMarkdownStore() =
        runTest {
            val longTermMemoryStore = FakeAgentChatLongTermMemoryStore()
            val viewModel = createViewModel(longTermMemoryStore = longTermMemoryStore)

            viewModel.onAction(
                AgentChatAction.LongTermMemoryChanged(
                    """
                    # Invariants

                    - Never commit API keys.
                    """.trimIndent(),
                ),
            )
            assertTrue(viewModel.uiState.value.isLongTermMemoryDirty)

            viewModel.onAction(AgentChatAction.SaveLongTermMemory)
            runCurrent()

            assertFalse(viewModel.uiState.value.isLongTermMemoryDirty)
            assertTrue(longTermMemoryStore.memory.markdown.contains("Never commit API keys"))
        }

    @Test
    fun clearChatKeepsTaskContextAndLongTermMemory() =
        runTest {
            val fakeAgent = FakeLlmAgent()
            val viewModel = createViewModel(fakeAgent)

            viewModel.onAction(AgentChatAction.TaskContextChanged("Goal: keep task context"))
            viewModel.onAction(AgentChatAction.LongTermMemoryChanged("# Preferences\n\n- Answer briefly"))
            viewModel.onAction(AgentChatAction.InputChanged("First"))
            viewModel.onAction(AgentChatAction.Submit)
            runCurrent()
            viewModel.onAction(AgentChatAction.ClearChat)
            runCurrent()

            assertEquals(emptyList<AgentChatMessage>(), viewModel.uiState.value.messages)
            assertEquals("keep task context", viewModel.uiState.value.memory.taskContext.goal)
            assertTrue(
                viewModel.uiState.value.memory.longTermMarkdown.markdown
                    .contains("Answer briefly"),
            )
            assertEquals(null, viewModel.uiState.value.memory.lastRequest)
        }

    @Test
    fun clearTaskContextClearsOnlyWorkingMemory() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onAction(AgentChatAction.TaskContextChanged("Goal: remove me"))
            viewModel.onAction(AgentChatAction.LongTermMemoryChanged("# Preferences\n\n- Keep me"))
            viewModel.onAction(AgentChatAction.ClearTaskContext)
            runCurrent()

            assertEquals(0, viewModel.uiState.value.memory.taskContext.itemCount)
            assertTrue(
                viewModel.uiState.value.memory.longTermMarkdown.markdown
                    .contains("Keep me"),
            )
            assertEquals(null, viewModel.uiState.value.memory.lastRequest)
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
    fun submitUsesSelectedGemmaAgentModelNameAndLimit() =
        runTest {
            val fakeAgent = FakeLlmAgent()
            val viewModel = createViewModel(fakeAgent)

            viewModel.onAction(AgentChatAction.AgentChanged(AgentChatAgentOption.GEMMA_4_26B_A4B_IT))
            viewModel.onAction(AgentChatAction.InputChanged("Hello Gemma"))
            viewModel.onAction(AgentChatAction.Submit)
            runCurrent()

            assertEquals(AgentChatAgentOption.GEMMA_4_26B_A4B_IT, viewModel.uiState.value.selectedAgent)
            assertEquals(AgentChatAgentOption.GEMMA_4_26B_A4B_IT.modelName, fakeAgent.calls.single().modelName)
            assertEquals(AgentChatAgentOption.GEMMA_4_26B_A4B_IT.totalTokenLimit, fakeAgent.calls.single().totalTokenLimit)
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
                        customTotalTokenLimit = 12_345,
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
            assertEquals(12_345, viewModel.uiState.value.customTotalTokenLimit)
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
        longTermMemoryStore: AgentChatLongTermMemoryStore = FakeAgentChatLongTermMemoryStore(),
    ): AgentChatViewModel = AgentChatViewModel(fakeAgent, historyStore, longTermMemoryStore)

    private fun completedResult(
        text: String,
        totalTokens: Int,
        slidingWindowApplied: Boolean = false,
    ): CompletableDeferred<AgentResult<String>> =
        CompletableDeferred(
            GeminiResult.Success(
                value = text,
                tokenUsage =
                    GeminiTokenUsage(
                        currentRequestTokens = totalTokens / 10,
                        conversationHistoryTokens = totalTokens - (totalTokens / 5),
                        modelResponseTokens = totalTokens / 10,
                        totalTokens = totalTokens,
                        slidingWindowApplied = slidingWindowApplied,
                    ),
            ),
        )

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

    private class FakeAgentChatLongTermMemoryStore(
        initialMemory: AgentChatLongTermMarkdown = AgentChatLongTermMarkdown(),
    ) : AgentChatLongTermMemoryStore {
        var memory = initialMemory
            private set

        override suspend fun load(): AgentChatLongTermMarkdown = memory

        override suspend fun save(memory: AgentChatLongTermMarkdown) {
            this.memory = memory
        }
    }
}
