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
    fun inputChangedEnablesRunTask() {
        val viewModel = createViewModel()

        viewModel.onAction(AgentChatAction.InputChanged("Hello"))

        assertEquals("Hello", viewModel.uiState.value.input)
        assertTrue(viewModel.uiState.value.canRunTask)
    }

    @Test
    fun runTaskUsesTaskContextLongTermMarkdownAndProfileInPrompt() =
        runTest {
            val fakeAgent = FakeLlmAgent(results = successfulTaskResults())
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
            viewModel.onAction(AgentChatAction.StartTask)
            runCurrent()

            val memory = viewModel.uiState.value.memory
            assertEquals("build a memory layer demo", memory.taskContext.goal)
            assertEquals("implementation", memory.taskContext.stage)
            assertEquals(listOf("tests must stay offline"), memory.taskContext.constraints)
            assertEquals(
                listOf(
                    AgentChatMemoryLayer.USER_PROFILE,
                    AgentChatMemoryLayer.TASK_STATE,
                    AgentChatMemoryLayer.LONG_TERM_MARKDOWN,
                    AgentChatMemoryLayer.TASK_CONTEXT,
                ),
                memory.lastRequest?.includedLayers,
            )
            assertTrue(
                fakeAgent.calls
                    .first()
                    .systemInstruction
                    ?.contains("Senior Kotlin developer") == true,
            )
            val firstPromptMessages = fakeAgent.calls.first().messages
            assertTrue(
                firstPromptMessages.any { message ->
                    message is AgentMessage.User && message.text.contains("Formal task state")
                },
            )
            assertTrue(
                firstPromptMessages.any { message ->
                    message is AgentMessage.User && message.text.contains("Answer with concise Kotlin examples")
                },
            )
            assertTrue(
                firstPromptMessages.any { message ->
                    message is AgentMessage.User && message.text.contains("TaskContext")
                },
            )
            assertEquals(memory.taskContext, historyStore.snapshot.memory.taskContext)
        }

    @Test
    fun profileSelectionPersistsAndRunTaskUsesActiveProfileSystemInstruction() =
        runTest {
            val fakeAgent = FakeLlmAgent(results = successfulTaskResults())
            val profileStore = FakeAgentChatUserProfileStore()
            val viewModel = createViewModel(fakeAgent = fakeAgent, userProfileStore = profileStore)
            runCurrent()

            viewModel.onAction(AgentChatAction.ProfileChanged(ANDROID_BEGINNER_PROFILE_ID))
            runCurrent()
            viewModel.onAction(AgentChatAction.InputChanged("Explain recomposition"))
            viewModel.onAction(AgentChatAction.StartTask)
            runCurrent()

            assertEquals(ANDROID_BEGINNER_PROFILE_ID, viewModel.uiState.value.activeProfileId)
            assertEquals(ANDROID_BEGINNER_PROFILE_ID, profileStore.snapshot.activeProfileId)
            assertTrue(
                fakeAgent.calls
                    .all { call -> call.systemInstruction?.contains("Android beginner") == true },
            )
            assertTrue(
                fakeAgent.calls
                    .all { call -> call.systemInstruction?.contains("Explain step by step") == true },
            )
            assertFalse(
                fakeAgent.calls
                    .any { call ->
                        call.systemInstruction
                            .orEmpty()
                            .contains("Senior Kotlin developer")
                    },
            )
        }

    @Test
    fun startTaskUsesNormalizedUserMessageAndShowsFinalResponse() =
        runTest {
            val fakeAgent = FakeLlmAgent(results = successfulTaskResults())
            val viewModel = createViewModel(fakeAgent)
            runCurrent()

            viewModel.onAction(AgentChatAction.InputChanged("  Hello\nGemini  "))
            viewModel.onAction(AgentChatAction.StartTask)
            runCurrent()

            assertEquals("", viewModel.uiState.value.input)
            assertFalse(viewModel.uiState.value.canRunTask)
            assertTrue(
                fakeAgent.calls
                    .all { call -> call.modelName == null && call.totalTokenLimit == null },
            )
            assertEquals(
                listOf(
                    AgentChatMessage(role = AgentChatRole.USER, text = "Hello Gemini"),
                    AgentChatMessage(role = AgentChatRole.MODEL, text = "Final answer"),
                ),
                viewModel.uiState.value.messages,
            )
            assertTrue(
                fakeAgent.calls
                    .first()
                    .systemInstruction
                    ?.contains("Senior Kotlin developer") == true,
            )
            assertTrue(
                fakeAgent.calls
                    .first()
                    .messages
                    .last() is AgentMessage.User,
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
    fun compareProfilesUsesSamePromptWithDifferentSystemInstructions() =
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
            val fakeAgent = FakeLlmAgent(results = successfulTaskResults())
            val viewModel = createViewModel(fakeAgent)

            viewModel.onAction(AgentChatAction.TaskContextChanged("Goal: keep task context"))
            viewModel.onAction(AgentChatAction.LongTermMemoryChanged("# Preferences\n\n- Answer briefly"))
            viewModel.onAction(AgentChatAction.InputChanged("First"))
            viewModel.onAction(AgentChatAction.StartTask)
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
    fun startTaskRunsFormalPipelineAndStoresArtifacts() =
        runTest {
            val fakeAgent =
                FakeLlmAgent(
                    results =
                        ArrayDeque(
                            listOf(
                                CompletableDeferred(GeminiResult.Success("Requirements report")),
                                CompletableDeferred(GeminiResult.Success("Risks report")),
                                CompletableDeferred(GeminiResult.Success("Task spec")),
                                CompletableDeferred(GeminiResult.Success("Draft result")),
                                CompletableDeferred(GeminiResult.Success("Validation report")),
                                CompletableDeferred(GeminiResult.Success("Final answer")),
                            ),
                        ),
                )
            val historyStore = FakeAgentChatHistoryStore()
            val viewModel = createViewModel(fakeAgent = fakeAgent, historyStore = historyStore)
            runCurrent()

            viewModel.onAction(AgentChatAction.InputChanged("Build task state"))
            viewModel.onAction(AgentChatAction.StartTask)
            runCurrent()

            val taskState = viewModel.uiState.value.memory.taskState
            assertEquals(6, fakeAgent.calls.size)
            assertEquals(AgentTaskStatus.DONE, taskState.status)
            assertEquals(
                listOf(
                    AgentTaskArtifactType.REQUIREMENTS_REPORT,
                    AgentTaskArtifactType.RISKS_REPORT,
                    AgentTaskArtifactType.TASK_SPEC,
                    AgentTaskArtifactType.EXECUTION_DRAFT,
                    AgentTaskArtifactType.VALIDATION_REPORT,
                    AgentTaskArtifactType.FINAL_ANSWER,
                ),
                taskState.artifacts.map { it.type },
            )
            assertEquals(
                AgentChatMessage(role = AgentChatRole.MODEL, text = "Final answer"),
                viewModel.uiState.value.messages
                    .last(),
            )
            assertEquals(taskState, historyStore.snapshot.memory.taskState)
        }

    @Test
    fun pauseTaskCancelsRunningPipelineAndIgnoresLateResponse() =
        runTest {
            val response = CompletableDeferred<AgentResult<String>>()
            val fakeAgent =
                FakeLlmAgent(
                    results = ArrayDeque(listOf(response)),
                )
            val viewModel = createViewModel(fakeAgent)
            runCurrent()

            viewModel.onAction(AgentChatAction.InputChanged("Run long task"))
            viewModel.onAction(AgentChatAction.StartTask)
            runCurrent()

            assertEquals(2, fakeAgent.calls.size)
            assertEquals(AgentTaskStatus.RUNNING, viewModel.uiState.value.memory.taskState.status)

            viewModel.onAction(AgentChatAction.PauseTask)
            runCurrent()

            assertEquals(AgentTaskStatus.PAUSED, viewModel.uiState.value.memory.taskState.status)
            assertTrue(
                viewModel.uiState.value.memory.taskState.branches
                    .all { it.status == AgentTaskBranchStatus.PAUSED },
            )
            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(
                viewModel.uiState.value.messages
                    .last()
                    .text
                    .contains("Task paused"),
            )

            response.complete(GeminiResult.Success("Late task spec"))
            runCurrent()

            assertTrue(
                viewModel.uiState.value.memory.taskState.artifacts
                    .isEmpty(),
            )
        }

    @Test
    fun retryTaskRerunsOnlyFailedPlanningBranch() =
        runTest {
            val fakeAgent =
                FakeLlmAgent(
                    results =
                        ArrayDeque(
                            listOf(
                                CompletableDeferred(GeminiResult.Success("Requirements report")),
                                CompletableDeferred(GeminiResult.Failure(GeminiNetworkError.MissingApiKey)),
                                CompletableDeferred(GeminiResult.Success("Recovered risks report")),
                                CompletableDeferred(GeminiResult.Success("Task spec")),
                                CompletableDeferred(GeminiResult.Success("Draft result")),
                                CompletableDeferred(GeminiResult.Success("Validation report")),
                                CompletableDeferred(GeminiResult.Success("Final answer")),
                            ),
                        ),
                )
            val viewModel = createViewModel(fakeAgent)
            runCurrent()

            viewModel.onAction(AgentChatAction.InputChanged("Build task state"))
            viewModel.onAction(AgentChatAction.StartTask)
            runCurrent()

            val failedState = viewModel.uiState.value.memory.taskState
            assertEquals(2, fakeAgent.calls.size)
            assertEquals(AgentTaskStatus.FAILED, failedState.status)
            assertEquals("Requirements report", failedState.artifact(AgentTaskArtifactType.REQUIREMENTS_REPORT)?.text)
            assertEquals(AgentTaskBranchStatus.DONE, failedState.branches.first { it.id == AgentTaskBranchId.REQUIREMENTS }.status)
            assertEquals(AgentTaskBranchStatus.FAILED, failedState.branches.first { it.id == AgentTaskBranchId.RISKS }.status)

            viewModel.onAction(AgentChatAction.RetryTask)
            runCurrent()

            assertEquals(7, fakeAgent.calls.size)
            assertTrue((fakeAgent.calls[2].messages.last() as AgentMessage.User).text.contains("risks agent"))
            assertEquals(AgentTaskStatus.DONE, viewModel.uiState.value.memory.taskState.status)
            assertEquals("Final answer", viewModel.uiState.value.memory.taskState.finalAnswer)
        }

    @Test
    fun resumeTaskContinuesFromSavedStepWithoutRepeatingPlanning() =
        runTest {
            val plannedState =
                AgentTaskState(
                    taskId = "task-1",
                    originalPrompt = "Build task state",
                    stage = AgentTaskStage.EXECUTION,
                    step = AgentTaskStep.CREATE_DRAFT,
                    status = AgentTaskStatus.PAUSED,
                    branches =
                        listOf(
                            AgentTaskBranch(
                                id = AgentTaskBranchId.REQUIREMENTS,
                                expectedArtifactType = AgentTaskArtifactType.REQUIREMENTS_REPORT,
                                status = AgentTaskBranchStatus.DONE,
                            ),
                            AgentTaskBranch(
                                id = AgentTaskBranchId.RISKS,
                                expectedArtifactType = AgentTaskArtifactType.RISKS_REPORT,
                                status = AgentTaskBranchStatus.DONE,
                            ),
                        ),
                    artifacts =
                        listOf(
                            AgentTaskArtifact(AgentTaskArtifactType.REQUIREMENTS_REPORT, "Requirements report"),
                            AgentTaskArtifact(AgentTaskArtifactType.RISKS_REPORT, "Risks report"),
                            AgentTaskArtifact(AgentTaskArtifactType.TASK_SPEC, "Task spec"),
                        ),
                )
            val historyStore =
                FakeAgentChatHistoryStore(
                    AgentChatHistorySnapshot(
                        memory = AgentChatMemorySnapshot(taskState = plannedState),
                    ),
                )
            val fakeAgent =
                FakeLlmAgent(
                    results =
                        ArrayDeque(
                            listOf(
                                CompletableDeferred(GeminiResult.Success("Draft result")),
                                CompletableDeferred(GeminiResult.Success("Validation report")),
                                CompletableDeferred(GeminiResult.Success("Final answer")),
                            ),
                        ),
                )
            val viewModel = createViewModel(fakeAgent = fakeAgent, historyStore = historyStore)
            runCurrent()

            viewModel.onAction(AgentChatAction.ResumeTask)
            runCurrent()

            assertEquals(3, fakeAgent.calls.size)
            assertTrue(
                (
                    fakeAgent.calls
                        .first()
                        .messages
                        .last() as AgentMessage.User
                ).text.contains("execution"),
            )
            assertEquals(AgentTaskStatus.DONE, viewModel.uiState.value.memory.taskState.status)
            assertEquals("Final answer", viewModel.uiState.value.memory.taskState.finalAnswer)
        }

    private fun successfulTaskResults(): ArrayDeque<CompletableDeferred<AgentResult<String>>> =
        ArrayDeque(
            listOf(
                CompletableDeferred(GeminiResult.Success("Requirements report")),
                CompletableDeferred(GeminiResult.Success("Risks report")),
                CompletableDeferred(GeminiResult.Success("Task spec")),
                CompletableDeferred(GeminiResult.Success("Draft result")),
                CompletableDeferred(GeminiResult.Success("Validation report")),
                CompletableDeferred(GeminiResult.Success("Final answer")),
            ),
        )

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
