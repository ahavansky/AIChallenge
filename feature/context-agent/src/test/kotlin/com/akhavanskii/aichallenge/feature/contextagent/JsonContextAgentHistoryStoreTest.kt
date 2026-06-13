package com.akhavanskii.aichallenge.feature.contextagent

import com.akhavanskii.aichallenge.core.network.GeminiTokenUsage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class JsonContextAgentHistoryStoreTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun saveAndLoadRoundTrip() =
        runTest {
            val historyFile = File(temporaryFolder.root, "history.json")
            val store = createStore(historyFile, StandardTestDispatcher(testScheduler))
            val snapshot =
                ContextAgentHistorySnapshot(
                    selectedModel = ContextAgentModelOption.GEMMA_4_26B_A4B_IT,
                    selectedStrategy = ContextManagementStrategy.BRANCHING,
                    facts =
                        listOf(
                            ContextFact("goal", "Collect requirements."),
                            ContextFact("constraints", "No payments."),
                        ),
                    branchingState =
                        ContextBranchingState(
                            checkpointMessages =
                                listOf(
                                    ContextAgentMessage(role = ContextAgentRole.USER, text = "Shared checkpoint"),
                                    ContextAgentMessage(role = ContextAgentRole.MODEL, text = "Checkpoint answer"),
                                ),
                            branches =
                                listOf(
                                    ContextAgentBranch(
                                        id = ContextBranchId.A,
                                        messages =
                                            listOf(
                                                ContextAgentMessage(role = ContextAgentRole.USER, text = "A"),
                                                ContextAgentMessage(role = ContextAgentRole.MODEL, text = "A answer"),
                                            ),
                                    ),
                                    ContextAgentBranch(
                                        id = ContextBranchId.B,
                                        messages =
                                            listOf(
                                                ContextAgentMessage(role = ContextAgentRole.USER, text = "B"),
                                                ContextAgentMessage(role = ContextAgentRole.MODEL, text = "B answer"),
                                            ),
                                    ),
                                ),
                            activeBranchId = ContextBranchId.B,
                            hasCheckpoint = true,
                        ),
                    strategyStats =
                        ContextStrategyStats(
                            strategy = ContextManagementStrategy.STICKY_FACTS,
                            fullPromptTokens = 1_000,
                            strategyPromptTokens = 400,
                            savedPromptTokens = 600,
                            savedPromptPercent = 60,
                            storedMessageCount = 12,
                            requestMessageCount = 9,
                            droppedMessageCount = 4,
                            factsCount = 2,
                        ),
                    comparison =
                        ContextScenarioComparison(
                            reports =
                                listOf(
                                    ContextScenarioStrategyReport(
                                        strategy = ContextManagementStrategy.SLIDING_WINDOW,
                                        answer = "Sliding answer",
                                        promptTokens = 300,
                                        requestMessageCount = 7,
                                        quality = "Lower",
                                        stability = "Lower",
                                        tokenUse = "Lowest",
                                        userConvenience = "Simple",
                                    ),
                                ),
                            evaluation = "Facts kept more detail.",
                        ),
                    messages =
                        listOf(
                            ContextAgentMessage(role = ContextAgentRole.USER, text = "Remember this"),
                            ContextAgentMessage(
                                role = ContextAgentRole.MODEL,
                                text = "Stored answer",
                                tokenUsage =
                                    GeminiTokenUsage(
                                        conversationHistoryTokens = 400,
                                        modelResponseTokens = 20,
                                        totalTokens = 420,
                                    ),
                            ),
                            ContextAgentMessage(role = ContextAgentRole.MODEL, text = "Waiting", isLoading = true),
                        ),
                )

            store.save(snapshot)

            assertEquals(
                snapshot.copy(messages = snapshot.messages.filterNot { it.isLoading }),
                store.load(),
            )
        }

    @Test
    fun loadReturnsDefaultSnapshotForMalformedJson() =
        runTest {
            val historyFile = temporaryFolder.newFile("history.json")
            historyFile.writeText("{")
            val store = createStore(historyFile, StandardTestDispatcher(testScheduler))

            assertEquals(ContextAgentHistorySnapshot(), store.load())
        }

    @OptIn(ExperimentalSerializationApi::class)
    private fun createStore(
        historyFile: File,
        dispatcher: CoroutineDispatcher,
    ): JsonContextAgentHistoryStore =
        JsonContextAgentHistoryStore(
            historyFile = historyFile,
            json =
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    explicitNulls = false
                },
            dispatcher = dispatcher,
        )
}
