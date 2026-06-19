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
import org.junit.Assert.assertNull
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
    fun submitSendsFirstUserMessageWithProfileInstructionAndShowsResponse() =
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
            assertTrue(
                fakeAgent.calls
                    .single()
                    .systemInstruction
                    ?.contains("Senior Kotlin developer") == true,
            )
            assertNull(fakeAgent.calls.single().modelName)
            assertNull(fakeAgent.calls.single().totalTokenLimit)
            assertEquals(
                listOf(
                    AgentChatMessage(role = AgentChatRole.USER, text = "Hello Gemini"),
                    AgentChatMessage(role = AgentChatRole.MODEL, text = "Waiting for Gemini", isLoading = true),
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
                    AgentChatMemoryLayer.USER_PROFILE,
                    AgentChatMemoryLayer.LONG_TERM_MARKDOWN,
                    AgentChatMemoryLayer.TASK_CONTEXT,
                ),
                memory.lastRequest?.includedLayers,
            )
            assertTrue(
                fakeAgent.calls
                    .single()
                    .systemInstruction
                    ?.contains("Senior Kotlin developer") == true,
            )
            assertTrue((fakeAgent.calls.single().messages[0] as AgentMessage.User).text.contains("Answer with concise Kotlin examples"))
            assertTrue((fakeAgent.calls.single().messages[1] as AgentMessage.User).text.contains("TaskContext"))
            assertEquals(
                AgentMessage.User("Create the plan"),
                fakeAgent.calls
                    .single()
                    .messages
                    .last(),
            )
            assertEquals(memory.taskContext, historyStore.snapshot.memory.taskContext)
        }

    @Test
    fun profileSelectionPersistsAndSubmitUsesActiveProfileSystemInstruction() =
        runTest {
            val fakeAgent = FakeLlmAgent()
            val profileStore = FakeAgentChatUserProfileStore()
            val viewModel = createViewModel(fakeAgent = fakeAgent, userProfileStore = profileStore)
            runCurrent()

            viewModel.onAction(AgentChatAction.ProfileChanged(ANDROID_BEGINNER_PROFILE_ID))
            runCurrent()
            viewModel.onAction(AgentChatAction.InputChanged("Explain recomposition"))
            viewModel.onAction(AgentChatAction.Submit)
            runCurrent()

            assertEquals(ANDROID_BEGINNER_PROFILE_ID, viewModel.uiState.value.activeProfileId)
            assertEquals(ANDROID_BEGINNER_PROFILE_ID, profileStore.snapshot.activeProfileId)
            assertTrue(
                fakeAgent.calls
                    .single()
                    .systemInstruction
                    ?.contains("Android beginner") == true,
            )
            assertTrue(
                fakeAgent.calls
                    .single()
                    .systemInstruction
                    ?.contains("Explain step by step") == true,
            )
            assertFalse(
                fakeAgent.calls
                    .single()
                    .systemInstruction
                    .orEmpty()
                    .contains("Senior Kotlin developer"),
            )
        }

    @Test
    fun profileInputChangedPersistsEditedActiveProfile() =
        runTest {
            val profileStore = FakeAgentChatUserProfileStore()
            val viewModel = createViewModel(userProfileStore = profileStore)
            runCurrent()

            viewModel.onAction(
                AgentChatAction.ProfileInputChanged(
                    """
                    Title: Staff reviewer
                    Role: Kotlin code reviewer
                    Expertise: Staff
                    Style: challenge weak assumptions
                    Format: findings first
                    Constraint: no generic repository layers
                    """.trimIndent(),
                ),
            )
            runCurrent()

            assertEquals("Staff reviewer", viewModel.uiState.value.activeProfile.title)
            assertEquals("Kotlin code reviewer", profileStore.snapshot.activeProfile.role)
            assertTrue(
                profileStore.snapshot.activeProfile.constraints
                    .contains("no generic repository layers"),
            )
        }

    @Test
    fun compareProfilesSendsSamePromptWithDifferentSystemInstructions() =
        runTest {
            val fakeAgent =
                FakeLlmAgent(
                    results =
                        ArrayDeque(
                            listOf(
                                CompletableDeferred(GeminiResult.Success("Custom answer")),
                                CompletableDeferred(GeminiResult.Success("Beginner answer")),
                                CompletableDeferred(GeminiResult.Success("Senior answer")),
                            ),
                        ),
                )
            val viewModel = createViewModel(fakeAgent)
            runCurrent()

            viewModel.onAction(AgentChatAction.InputChanged("Explain memory"))
            viewModel.onAction(AgentChatAction.CompareProfiles)
            runCurrent()

            assertEquals(3, fakeAgent.calls.size)
            assertTrue(fakeAgent.calls.all { it.messages.last() == AgentMessage.User("Explain memory") })
            assertTrue(fakeAgent.calls[0].systemInstruction?.contains("Custom user") == true)
            assertTrue(fakeAgent.calls[1].systemInstruction?.contains("Android beginner") == true)
            assertTrue(fakeAgent.calls[2].systemInstruction?.contains("Senior Kotlin developer") == true)
            assertTrue(fakeAgent.calls.all { it.modelName == null && it.totalTokenLimit == null })
            assertEquals(
                listOf("Custom answer", "Beginner answer", "Senior answer"),
                viewModel.uiState.value.compareResults
                    .map { it.text },
            )
            assertEquals(emptyList<AgentChatMessage>(), viewModel.uiState.value.messages)
        }

    @Test
    fun stopCancelsRunningSubmitAndIgnoresLateResponse() =
        runTest {
            val response = CompletableDeferred<AgentResult<String>>()
            val fakeAgent = FakeLlmAgent(results = ArrayDeque(listOf(response)))
            val historyStore = FakeAgentChatHistoryStore()
            val viewModel = createViewModel(fakeAgent = fakeAgent, historyStore = historyStore)

            viewModel.onAction(AgentChatAction.InputChanged("Run long request"))
            viewModel.onAction(AgentChatAction.Submit)
            runCurrent()

            assertTrue(viewModel.uiState.value.isLoading)
            assertTrue(viewModel.uiState.value.canStop)

            viewModel.onAction(AgentChatAction.Stop)
            runCurrent()

            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals(
                AgentChatMessage(role = AgentChatRole.MODEL, text = "Stopped by user.", isError = true),
                viewModel.uiState.value.messages
                    .last(),
            )
            assertEquals(viewModel.uiState.value.messages, historyStore.snapshot.messages)

            response.complete(GeminiResult.Success("Late answer should be ignored"))
            runCurrent()

            assertFalse(
                viewModel.uiState.value.messages
                    .any { it.text.contains("Late answer should be ignored") },
            )
        }

    @Test
    fun stopCancelsProfileComparison() =
        runTest {
            val response = CompletableDeferred<AgentResult<String>>()
            val fakeAgent = FakeLlmAgent(results = ArrayDeque(listOf(response)))
            val viewModel = createViewModel(fakeAgent)
            runCurrent()

            viewModel.onAction(AgentChatAction.InputChanged("Compare styles"))
            viewModel.onAction(AgentChatAction.CompareProfiles)
            runCurrent()

            viewModel.onAction(AgentChatAction.Stop)
            runCurrent()

            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(
                viewModel.uiState.value.compareResults
                    .all { it.isError && it.text == "Stopped by user." },
            )
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
                AgentChatMessage(role = AgentChatRole.MODEL, text = GeminiNetworkError.MissingApiKey.userMessage, isError = true),
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
    fun initRestoresSavedMessages() =
        runTest {
            val historyStore =
                FakeAgentChatHistoryStore(
                    AgentChatHistorySnapshot(
                        messages =
                            listOf(
                                AgentChatMessage(role = AgentChatRole.USER, text = "Previous question"),
                                AgentChatMessage(role = AgentChatRole.MODEL, text = "Previous answer"),
                            ),
                    ),
                )
            val viewModel = createViewModel(historyStore = historyStore)

            runCurrent()

            assertEquals(
                listOf(
                    AgentChatMessage(role = AgentChatRole.USER, text = "Previous question"),
                    AgentChatMessage(role = AgentChatRole.MODEL, text = "Previous answer"),
                ),
                viewModel.uiState.value.messages,
            )
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
        userProfileStore: AgentChatUserProfileStore = FakeAgentChatUserProfileStore(),
    ): AgentChatViewModel = AgentChatViewModel(fakeAgent, historyStore, longTermMemoryStore, userProfileStore)

    private class FakeLlmAgent(
        result: AgentResult<String> = GeminiResult.Success("Answer"),
        private val results: ArrayDeque<CompletableDeferred<AgentResult<String>>> =
            ArrayDeque(listOf(CompletableDeferred(result))),
    ) : LlmAgent {
        val calls = mutableListOf<AgentCall>()

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
        val systemInstruction: String?,
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

    private class FakeAgentChatUserProfileStore(
        initialSnapshot: AgentChatProfileSnapshot = AgentChatProfileSnapshot(),
    ) : AgentChatUserProfileStore {
        var snapshot = initialSnapshot.normalized
            private set

        override suspend fun load(): AgentChatProfileSnapshot = snapshot

        override suspend fun save(snapshot: AgentChatProfileSnapshot) {
            this.snapshot = snapshot.normalized
        }
    }
}
