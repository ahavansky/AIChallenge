package com.akhavanskii.aichallenge.feature.agentchat

import com.akhavanskii.aichallenge.core.network.AgentMessage
import com.akhavanskii.aichallenge.core.network.AgentResult
import com.akhavanskii.aichallenge.core.network.GeminiGenerationConfig
import com.akhavanskii.aichallenge.core.network.GeminiNetworkError
import com.akhavanskii.aichallenge.core.network.GeminiResult
import com.akhavanskii.aichallenge.core.network.LlmAgent
import com.akhavanskii.aichallenge.core.network.McpClient
import com.akhavanskii.aichallenge.core.network.McpDiscoveryResult
import com.akhavanskii.aichallenge.core.network.McpNetworkError
import com.akhavanskii.aichallenge.core.network.McpServerInfo
import com.akhavanskii.aichallenge.core.network.McpTool
import com.akhavanskii.aichallenge.core.network.McpToolCall
import com.akhavanskii.aichallenge.core.network.McpToolCallResult
import com.akhavanskii.aichallenge.core.network.McpToolDiscovery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            viewModel.onAction(AgentChatAction.ApprovePlan)
            runCurrent()
            viewModel.onAction(AgentChatAction.AcceptValidation)
            runCurrent()

            assertEquals("", viewModel.uiState.value.input)
            assertFalse(viewModel.uiState.value.canRunTask)
            assertTrue(
                fakeAgent.calls
                    .all { call ->
                        call.modelName == AgentChatModelOption.DEFAULT.modelName &&
                            call.totalTokenLimit == null
                    },
            )
            assertEquals(
                listOf(
                    AgentChatMessage(role = AgentChatRole.USER, text = "Hello Gemini"),
                    AgentChatMessage(
                        role = AgentChatRole.MODEL,
                        text = "Task plan is ready for review. Approve the plan to continue execution, or request a plan revision.",
                    ),
                    AgentChatMessage(
                        role = AgentChatRole.MODEL,
                        text = "Validation passed. Accept validation to produce the final answer, or request an execution revision.",
                    ),
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
    fun modelSelectionPersistsAndRunTaskUsesSelectedModel() =
        runTest {
            val fakeAgent = FakeLlmAgent(results = successfulTaskResults())
            val historyStore = FakeAgentChatHistoryStore()
            val viewModel = createViewModel(fakeAgent = fakeAgent, historyStore = historyStore)
            runCurrent()

            viewModel.onAction(AgentChatAction.ModelChanged(AgentChatModelOption.DEEPSEEK_V4_FLASH))
            runCurrent()
            viewModel.onAction(AgentChatAction.InputChanged("Create a compact plan"))
            viewModel.onAction(AgentChatAction.StartTask)
            runCurrent()

            assertEquals(AgentChatModelOption.DEEPSEEK_V4_FLASH, viewModel.uiState.value.selectedModel)
            assertEquals(AgentChatModelOption.DEEPSEEK_V4_FLASH, historyStore.snapshot.selectedModel)
            assertTrue(
                fakeAgent.calls.all { call ->
                    call.modelName == AgentChatModelOption.DEEPSEEK_V4_FLASH.modelName
                },
            )
        }

    @Test
    fun listFetchToolsShowsToolListWithoutCallingLlm() =
        runTest {
            val fakeAgent = FakeLlmAgent()
            val fakeMcpClient =
                FakeMcpClient(
                    result =
                        McpDiscoveryResult.Success(
                            McpToolDiscovery(
                                serverInfo = McpServerInfo(name = "fetch", version = "1.0.0"),
                                toolsCapabilityAdvertised = true,
                                tools =
                                    listOf(
                                        McpTool(
                                            name = "fetch",
                                            title = "Fetch",
                                            description = "Fetches a URL from the internet.",
                                            inputSchemaJson = """{"type":"object"}""",
                                            requiredInputNames = listOf("url"),
                                        ),
                                    ),
                            ),
                        ),
                )
            val viewModel = createViewModel(fakeAgent = fakeAgent, mcpClient = fakeMcpClient)
            runCurrent()

            viewModel.onAction(AgentChatAction.ListFetchTools)
            runCurrent()

            val message =
                viewModel
                    .uiState
                    .value
                    .messages
                    .last()
            assertFalse(message.isLoading)
            assertFalse(message.isError)
            assertTrue(message.text.contains("MCP connected to fetch 1.0.0."))
            assertTrue(message.text.contains("`fetch`"))
            assertTrue(message.text.contains("Required args: `url`"))
            assertEquals(1, fakeMcpClient.callCount)
            assertEquals(0, fakeAgent.calls.size)
        }

    @Test
    fun listFetchToolsShowsErrorWithoutCallingLlm() =
        runTest {
            val fakeAgent = FakeLlmAgent()
            val fakeMcpClient = FakeMcpClient(result = McpDiscoveryResult.Failure(McpNetworkError.MissingEndpoint))
            val viewModel = createViewModel(fakeAgent = fakeAgent, mcpClient = fakeMcpClient)
            runCurrent()

            viewModel.onAction(AgentChatAction.ListFetchTools)
            runCurrent()

            val message =
                viewModel
                    .uiState
                    .value
                    .messages
                    .last()
            assertTrue(message.isError)
            assertTrue(message.text.contains("MCP server URL is missing"))
            assertEquals(1, fakeMcpClient.callCount)
            assertEquals(0, fakeAgent.calls.size)
        }

    @Test
    fun listFetchToolsIgnoresSecondRequestWhileLoading() =
        runTest {
            val pendingResult = CompletableDeferred<McpDiscoveryResult<McpToolDiscovery>>()
            val fakeMcpClient = FakeMcpClient(results = ArrayDeque(listOf(pendingResult)))
            val viewModel = createViewModel(mcpClient = fakeMcpClient)
            runCurrent()

            viewModel.onAction(AgentChatAction.ListFetchTools)
            runCurrent()
            viewModel.onAction(AgentChatAction.ListFetchTools)
            runCurrent()

            assertEquals(1, fakeMcpClient.callCount)
            assertTrue(viewModel.uiState.value.isLoading)

            pendingResult.complete(
                McpDiscoveryResult.Success(
                    McpToolDiscovery(
                        serverInfo = null,
                        toolsCapabilityAdvertised = true,
                        tools = emptyList(),
                    ),
                ),
            )
            runCurrent()

            assertEquals(1, fakeMcpClient.callCount)
            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(
                viewModel
                    .uiState
                    .value
                    .messages
                    .last()
                    .text
                    .contains("Connected, but no tools returned."),
            )
        }

    @Test
    fun callGitHubRepositoryToolCallsMcpAndUsesResultInLlmPrompt() =
        runTest {
            val fakeAgent = FakeLlmAgent(result = GeminiResult.Success("OkHttp is a Kotlin HTTP client."))
            val fakeMcpClient =
                FakeMcpClient(
                    toolResult =
                        McpToolCallResult.Success(
                            McpToolCall(
                                name = "github_repository_summary",
                                contentText =
                                    """
                                    GitHub repository summary
                                    Full name: square/okhttp
                                    Stars: 47000
                                    Language: Kotlin
                                    """.trimIndent(),
                                isError = false,
                            ),
                        ),
                )
            val viewModel = createViewModel(fakeAgent = fakeAgent, mcpClient = fakeMcpClient)
            runCurrent()

            viewModel.onAction(AgentChatAction.InputChanged("square/okhttp"))
            viewModel.onAction(AgentChatAction.CallGitHubRepositoryTool)
            runCurrent()

            assertEquals(1, fakeMcpClient.toolCallCount)
            assertEquals("github_repository_summary", fakeMcpClient.lastToolName)
            val arguments = fakeMcpClient.lastArguments
            assertEquals(
                "square",
                arguments
                    ?.get("owner")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                "okhttp",
                arguments
                    ?.get("repo")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(1, fakeAgent.calls.size)
            assertTrue(
                fakeAgent
                    .calls
                    .single()
                    .messages
                    .any { it.text.contains("Full name: square/okhttp") },
            )
            val message =
                viewModel
                    .uiState
                    .value
                    .messages
                    .last()
            assertFalse(message.isError)
            assertTrue(message.text.contains("MCP tool `github_repository_summary` returned"))
            assertTrue(message.text.contains("Agent answer"))
            assertTrue(message.text.contains("OkHttp is a Kotlin HTTP client."))
        }

    @Test
    fun callGitHubRepositoryToolShowsToolErrorWithoutCallingLlm() =
        runTest {
            val fakeAgent = FakeLlmAgent()
            val fakeMcpClient =
                FakeMcpClient(
                    toolResult =
                        McpToolCallResult.Success(
                            McpToolCall(
                                name = "github_repository_summary",
                                contentText = "Repository not found: square/missing.",
                                isError = true,
                            ),
                        ),
                )
            val viewModel = createViewModel(fakeAgent = fakeAgent, mcpClient = fakeMcpClient)
            runCurrent()

            viewModel.onAction(AgentChatAction.InputChanged("square/missing"))
            viewModel.onAction(AgentChatAction.CallGitHubRepositoryTool)
            runCurrent()

            val message =
                viewModel
                    .uiState
                    .value
                    .messages
                    .last()
            assertTrue(message.isError)
            assertTrue(message.text.contains("Repository not found"))
            assertEquals(1, fakeMcpClient.toolCallCount)
            assertEquals(0, fakeAgent.calls.size)
        }

    @Test
    fun callGitHubRepositoryToolIgnoresSecondRequestWhileLoading() =
        runTest {
            val pendingResult = CompletableDeferred<McpToolCallResult>()
            val fakeMcpClient = FakeMcpClient(toolResults = ArrayDeque(listOf(pendingResult)))
            val viewModel = createViewModel(mcpClient = fakeMcpClient)
            runCurrent()

            viewModel.onAction(AgentChatAction.InputChanged("square/okhttp"))
            viewModel.onAction(AgentChatAction.CallGitHubRepositoryTool)
            runCurrent()
            viewModel.onAction(AgentChatAction.InputChanged("JetBrains/kotlin"))
            viewModel.onAction(AgentChatAction.CallGitHubRepositoryTool)
            runCurrent()

            assertEquals(1, fakeMcpClient.toolCallCount)
            assertTrue(viewModel.uiState.value.isLoading)

            pendingResult.complete(McpToolCallResult.Failure(McpNetworkError.Network("stopped")))
            runCurrent()

            assertEquals(1, fakeMcpClient.toolCallCount)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun startTaskRefusesHardInvariantConflictBeforeCallingLlm() =
        runTest {
            val fakeAgent = FakeLlmAgent(results = successfulTaskResults())
            val historyStore = FakeAgentChatHistoryStore()
            val viewModel =
                createViewModel(
                    fakeAgent = fakeAgent,
                    historyStore = historyStore,
                    invariantStore = FakeAgentChatInvariantStore(androidStackInvariants()),
                )
            runCurrent()

            viewModel.onAction(AgentChatAction.InputChanged("Build this screen with React Native"))
            viewModel.onAction(AgentChatAction.StartTask)
            runCurrent()

            assertEquals(0, fakeAgent.calls.size)
            assertEquals(AgentTaskStatus.IDLE, viewModel.uiState.value.memory.taskState.status)
            assertEquals("", viewModel.uiState.value.input)
            assertEquals(
                AgentChatMessage(role = AgentChatRole.USER, text = "Build this screen with React Native"),
                viewModel
                    .uiState
                    .value
                    .messages
                    .first(),
            )
            val refusal =
                viewModel
                    .uiState
                    .value
                    .messages
                    .last()
            assertTrue(refusal.isError)
            assertTrue(refusal.text.contains("Android stack"))
            assertTrue(refusal.text.contains("Kotlin/Compose"))
            assertEquals(AgentChatInvariantCheckStatus.BLOCKED, viewModel.uiState.value.lastInvariantCheck.status)
            assertEquals("React Native", viewModel.uiState.value.lastInvariantCheck.conflict)
            assertEquals(viewModel.uiState.value.messages, historyStore.snapshot.messages)
        }

    @Test
    fun violatingModelOutputIsRepairedOnceBeforeArtifactIsStored() =
        runTest {
            val historyStore =
                FakeAgentChatHistoryStore(
                    AgentChatHistorySnapshot(
                        memory = AgentChatMemorySnapshot(taskState = pausedExecutionState()),
                    ),
                )
            val fakeAgent =
                FakeLlmAgent(
                    results =
                        ArrayDeque(
                            listOf(
                                CompletableDeferred(GeminiResult.Success("Use React Native for the screen.")),
                                CompletableDeferred(GeminiResult.Success("Use Kotlin and Jetpack Compose for the screen.")),
                                CompletableDeferred(GeminiResult.Success("Validation intent")),
                                CompletableDeferred(GeminiResult.Success("Validation constraints")),
                                CompletableDeferred(GeminiResult.Success("Validation context")),
                                CompletableDeferred(GeminiResult.Success("Validation solution")),
                                CompletableDeferred(GeminiResult.Success("Validation review")),
                                CompletableDeferred(GeminiResult.Success("Validation outcome: PASS\nInvariant check: passed")),
                            ),
                        ),
                )
            val viewModel =
                createViewModel(
                    fakeAgent = fakeAgent,
                    historyStore = historyStore,
                    invariantStore = FakeAgentChatInvariantStore(androidStackInvariants()),
                )
            runCurrent()

            viewModel.onAction(AgentChatAction.ResumeTask)
            runCurrent()

            val taskState = viewModel.uiState.value.memory.taskState
            assertEquals(8, fakeAgent.calls.size)
            assertTrue(
                (fakeAgent.calls[1].messages.last() as AgentMessage.User)
                    .text
                    .contains("violated hard invariants"),
            )
            assertEquals(
                "Use Kotlin and Jetpack Compose for the screen.",
                taskState.artifact(AgentTaskArtifactType.EXECUTION_DRAFT)?.text,
            )
            assertEquals(AgentChatInvariantCheckStatus.REPAIRED, viewModel.uiState.value.lastInvariantCheck.status)
            assertEquals(AgentTaskStatus.WAITING_FOR_USER, taskState.status)
            assertEquals(AgentValidationOutcome.PASS, taskState.validationOutcome)
        }

    @Test
    fun repeatedInvariantViolationFailsCurrentTaskStep() =
        runTest {
            val historyStore =
                FakeAgentChatHistoryStore(
                    AgentChatHistorySnapshot(
                        memory = AgentChatMemorySnapshot(taskState = pausedExecutionState()),
                    ),
                )
            val fakeAgent =
                FakeLlmAgent(
                    results =
                        ArrayDeque(
                            listOf(
                                CompletableDeferred(GeminiResult.Success("Use React Native for the screen.")),
                                CompletableDeferred(GeminiResult.Success("Still use React Native for the screen.")),
                            ),
                        ),
                )
            val viewModel =
                createViewModel(
                    fakeAgent = fakeAgent,
                    historyStore = historyStore,
                    invariantStore = FakeAgentChatInvariantStore(androidStackInvariants()),
                )
            runCurrent()

            viewModel.onAction(AgentChatAction.ResumeTask)
            runCurrent()

            assertEquals(2, fakeAgent.calls.size)
            assertEquals(AgentTaskStatus.FAILED, viewModel.uiState.value.memory.taskState.status)
            assertEquals(AgentChatInvariantCheckStatus.FAILED, viewModel.uiState.value.lastInvariantCheck.status)
            assertTrue(
                viewModel.uiState.value.messages
                    .last()
                    .text
                    .contains("could not be repaired"),
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
            assertTrue(
                fakeAgent.calls.all {
                    it.modelName == AgentChatModelOption.DEFAULT.modelName && it.totalTokenLimit == null
                },
            )
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
            val fakeAgent = FakeLlmAgent(results = successfulTaskResults())
            val historyStore = FakeAgentChatHistoryStore()
            val viewModel = createViewModel(fakeAgent = fakeAgent, historyStore = historyStore)
            runCurrent()

            viewModel.onAction(AgentChatAction.InputChanged("Build task state"))
            viewModel.onAction(AgentChatAction.StartTask)
            runCurrent()
            viewModel.onAction(AgentChatAction.ApprovePlan)
            runCurrent()
            viewModel.onAction(AgentChatAction.AcceptValidation)
            runCurrent()

            val taskState = viewModel.uiState.value.memory.taskState
            assertEquals(24, fakeAgent.calls.size)
            assertEquals(AgentTaskStatus.DONE, taskState.status)
            assertTrue(taskState.artifacts.any { it.type == AgentTaskArtifactType.TASK_SPEC })
            assertTrue(taskState.artifacts.any { it.type == AgentTaskArtifactType.EXECUTION_DRAFT })
            assertTrue(taskState.artifacts.any { it.type == AgentTaskArtifactType.VALIDATION_REPORT })
            assertTrue(taskState.artifacts.any { it.type == AgentTaskArtifactType.FINAL_ANSWER })
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

            assertEquals(5, fakeAgent.calls.size)
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
                                CompletableDeferred(GeminiResult.Success("Intent report")),
                                CompletableDeferred(GeminiResult.Failure(GeminiNetworkError.MissingApiKey)),
                                CompletableDeferred(GeminiResult.Success("Context report")),
                                CompletableDeferred(GeminiResult.Success("Solution report")),
                                CompletableDeferred(GeminiResult.Success("Review report")),
                                CompletableDeferred(GeminiResult.Success("Recovered constraints report")),
                                CompletableDeferred(GeminiResult.Success("Task spec")),
                            ),
                        ),
                )
            val viewModel = createViewModel(fakeAgent)
            runCurrent()

            viewModel.onAction(AgentChatAction.InputChanged("Build task state"))
            viewModel.onAction(AgentChatAction.StartTask)
            runCurrent()

            val failedState = viewModel.uiState.value.memory.taskState
            assertEquals(5, fakeAgent.calls.size)
            assertEquals(AgentTaskStatus.FAILED, failedState.status)
            assertEquals("Intent report", failedState.artifact(AgentTaskArtifactType.INTENT_REPORT)?.text)
            assertEquals(AgentTaskBranchStatus.DONE, failedState.branches.first { it.id == AgentTaskBranchId.INTENT }.status)
            assertEquals(AgentTaskBranchStatus.FAILED, failedState.branches.first { it.id == AgentTaskBranchId.CONSTRAINTS }.status)

            viewModel.onAction(AgentChatAction.RetryTask)
            runCurrent()

            assertEquals(7, fakeAgent.calls.size)
            assertTrue((fakeAgent.calls[5].messages.last() as AgentMessage.User).text.contains("constraints specialist"))
            assertEquals(AgentTaskStatus.WAITING_FOR_USER, viewModel.uiState.value.memory.taskState.status)
            assertEquals(AgentTaskWaitingReason.PLAN_APPROVAL, viewModel.uiState.value.memory.taskState.waitingReason)
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
                    artifacts =
                        listOf(
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
                                CompletableDeferred(GeminiResult.Success("Validation intent")),
                                CompletableDeferred(GeminiResult.Success("Validation constraints")),
                                CompletableDeferred(GeminiResult.Success("Validation context")),
                                CompletableDeferred(GeminiResult.Success("Validation solution")),
                                CompletableDeferred(GeminiResult.Success("Validation review")),
                                CompletableDeferred(GeminiResult.Success("Validation outcome: PASS\nValidation report")),
                            ),
                        ),
                )
            val viewModel = createViewModel(fakeAgent = fakeAgent, historyStore = historyStore)
            runCurrent()

            viewModel.onAction(AgentChatAction.ResumeTask)
            runCurrent()

            assertEquals(7, fakeAgent.calls.size)
            assertTrue(
                (
                    fakeAgent.calls
                        .first()
                        .messages
                        .last() as AgentMessage.User
                ).text.contains("execution"),
            )
            assertEquals(AgentTaskStatus.WAITING_FOR_USER, viewModel.uiState.value.memory.taskState.status)
            assertEquals(AgentTaskWaitingReason.VALIDATION_APPROVAL, viewModel.uiState.value.memory.taskState.waitingReason)
        }

    private fun successfulTaskResults(): ArrayDeque<CompletableDeferred<AgentResult<String>>> =
        ArrayDeque(
            listOf(
                CompletableDeferred(GeminiResult.Success("Planning intent report")),
                CompletableDeferred(GeminiResult.Success("Planning constraints report")),
                CompletableDeferred(GeminiResult.Success("Planning context report")),
                CompletableDeferred(GeminiResult.Success("Planning solution report")),
                CompletableDeferred(GeminiResult.Success("Planning review report")),
                CompletableDeferred(GeminiResult.Success("Task spec")),
                CompletableDeferred(GeminiResult.Success("Execution intent report")),
                CompletableDeferred(GeminiResult.Success("Execution constraints report")),
                CompletableDeferred(GeminiResult.Success("Execution context report")),
                CompletableDeferred(GeminiResult.Success("Execution solution report")),
                CompletableDeferred(GeminiResult.Success("Execution review report")),
                CompletableDeferred(GeminiResult.Success("Draft result")),
                CompletableDeferred(GeminiResult.Success("Validation intent report")),
                CompletableDeferred(GeminiResult.Success("Validation constraints report")),
                CompletableDeferred(GeminiResult.Success("Validation context report")),
                CompletableDeferred(GeminiResult.Success("Validation solution report")),
                CompletableDeferred(GeminiResult.Success("Validation review report")),
                CompletableDeferred(GeminiResult.Success("Validation outcome: PASS\nValidation report")),
                CompletableDeferred(GeminiResult.Success("Final intent report")),
                CompletableDeferred(GeminiResult.Success("Final constraints report")),
                CompletableDeferred(GeminiResult.Success("Final context report")),
                CompletableDeferred(GeminiResult.Success("Final solution report")),
                CompletableDeferred(GeminiResult.Success("Final review report")),
                CompletableDeferred(GeminiResult.Success("Final answer")),
            ),
        )

    private fun androidStackInvariants(): AgentChatInvariantSet =
        AgentChatInvariantSet(
            markdown =
                """
                # Invariants

                Invariant: Android stack
                Type: tech_stack
                Severity: hard
                Rule: Android implementation must use Kotlin and Jetpack Compose.
                Reject: React Native
                Reason: The project architecture and stack are fixed.
                Alternative: Offer a Kotlin/Compose solution instead.
                """.trimIndent(),
        )

    private fun pausedExecutionState(): AgentTaskState =
        AgentTaskState(
            taskId = "task-1",
            originalPrompt = "Build Android screen",
            stage = AgentTaskStage.EXECUTION,
            step = AgentTaskStep.CREATE_DRAFT,
            status = AgentTaskStatus.PAUSED,
            artifacts =
                listOf(
                    AgentTaskArtifact(AgentTaskArtifactType.TASK_SPEC, "Task spec"),
                ),
        )

    private fun createViewModel(
        fakeAgent: FakeLlmAgent = FakeLlmAgent(),
        historyStore: AgentChatHistoryStore = FakeAgentChatHistoryStore(),
        longTermMemoryStore: AgentChatLongTermMemoryStore = FakeAgentChatLongTermMemoryStore(),
        userProfileStore: AgentChatUserProfileStore = FakeAgentChatUserProfileStore(),
        invariantStore: AgentChatInvariantStore = FakeAgentChatInvariantStore(),
        mcpClient: McpClient = FakeMcpClient(),
    ): AgentChatViewModel =
        AgentChatViewModel(
            llmAgent = fakeAgent,
            historyStore = historyStore,
            longTermMemoryStore = longTermMemoryStore,
            userProfileStore = userProfileStore,
            invariantStore = invariantStore,
            mcpClient = mcpClient,
        )

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

    private class FakeMcpClient(
        result: McpDiscoveryResult<McpToolDiscovery> =
            McpDiscoveryResult.Success(
                McpToolDiscovery(
                    serverInfo = null,
                    toolsCapabilityAdvertised = true,
                    tools = emptyList(),
                ),
            ),
        private val results: ArrayDeque<CompletableDeferred<McpDiscoveryResult<McpToolDiscovery>>> =
            ArrayDeque(listOf(CompletableDeferred(result))),
        toolResult: McpToolCallResult =
            McpToolCallResult.Success(
                McpToolCall(
                    name = "github_repository_summary",
                    contentText = "GitHub repository summary",
                    isError = false,
                ),
            ),
        private val toolResults: ArrayDeque<CompletableDeferred<McpToolCallResult>> =
            ArrayDeque(listOf(CompletableDeferred(toolResult))),
    ) : McpClient {
        var callCount = 0
            private set

        var toolCallCount = 0
            private set

        var lastToolName: String? = null
            private set

        var lastArguments: JsonObject? = null
            private set

        override suspend fun listTools(): McpDiscoveryResult<McpToolDiscovery> {
            callCount += 1
            val result =
                if (results.size > 1) {
                    results.removeFirst()
                } else {
                    results.first()
                }
            return result.await()
        }

        override suspend fun callTool(
            name: String,
            arguments: JsonObject,
        ): McpToolCallResult {
            toolCallCount += 1
            lastToolName = name
            lastArguments = arguments
            val result =
                if (toolResults.size > 1) {
                    toolResults.removeFirst()
                } else {
                    toolResults.first()
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

    private class FakeAgentChatInvariantStore(
        initialInvariants: AgentChatInvariantSet = AgentChatInvariantSet(),
    ) : AgentChatInvariantStore {
        var invariants = initialInvariants
            private set

        override suspend fun load(): AgentChatInvariantSet = invariants

        override suspend fun save(invariants: AgentChatInvariantSet) {
            this.invariants = invariants
        }
    }
}
