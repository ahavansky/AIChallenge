package com.akhavanskii.aichallenge.feature.agentchat

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
class JsonAgentChatHistoryStoreTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun saveAndLoadRoundTrip() =
        runTest {
            val historyFile = File(temporaryFolder.root, "history.json")
            val store = createStore(historyFile, StandardTestDispatcher(testScheduler))
            val snapshot =
                AgentChatHistorySnapshot(
                    selectedAgent = AgentChatAgentOption.GEMINI_2_5_FLASH_LITE,
                    customTotalTokenLimit = 12_345,
                    messages =
                        listOf(
                            AgentChatMessage(role = AgentChatRole.USER, text = "Remember this"),
                            AgentChatMessage(
                                role = AgentChatRole.MODEL,
                                text = "Stored answer",
                                tokenUsage =
                                    GeminiTokenUsage(
                                        currentRequestTokens = 3,
                                        conversationHistoryTokens = 9,
                                        modelResponseTokens = 4,
                                        totalTokens = 13,
                                    ),
                            ),
                            AgentChatMessage(role = AgentChatRole.MODEL, text = "Waiting", isLoading = true),
                        ),
                )

            store.save(snapshot)

            val expectedMessages = snapshot.messages.filterNot { it.isLoading }
            assertEquals(
                snapshot.copy(
                    messages = expectedMessages,
                    memory = snapshot.memory.withShortTermFromMessages(expectedMessages),
                ),
                store.load(),
            )
        }

    @Test
    fun saveAndLoadMemoryLayersRoundTrip() =
        runTest {
            val historyFile = File(temporaryFolder.root, "history.json")
            val store = createStore(historyFile, StandardTestDispatcher(testScheduler))
            val snapshot =
                AgentChatHistorySnapshot(
                    messages =
                        listOf(
                            AgentChatMessage(role = AgentChatRole.USER, text = "Goal: build memory"),
                            AgentChatMessage(role = AgentChatRole.MODEL, text = "Goal saved"),
                        ),
                    memory =
                        AgentChatMemorySnapshot(
                            working = AgentChatWorkingMemory(goal = "build memory"),
                            longTerm =
                                AgentChatLongTermMemory(
                                    preferences = listOf("concise answers"),
                                    invariants = listOf("do not commit secrets"),
                                ),
                            lastRequest =
                                AgentChatMemoryRequestContext(
                                    includedLayers =
                                        listOf(
                                            AgentChatMemoryLayer.WORKING,
                                            AgentChatMemoryLayer.LONG_TERM,
                                        ),
                                    workingItemCount = 1,
                                    longTermItemCount = 2,
                                    promptPreview = "preview",
                                ),
                        ),
                )

            store.save(snapshot)

            assertEquals(
                snapshot.copy(memory = snapshot.memory.withShortTermFromMessages(snapshot.messages)),
                store.load(),
            )
        }

    @Test
    fun loadReturnsDefaultSnapshotForMalformedJson() =
        runTest {
            val historyFile = temporaryFolder.newFile("history.json")
            historyFile.writeText("{")
            val store = createStore(historyFile, StandardTestDispatcher(testScheduler))

            assertEquals(AgentChatHistorySnapshot(), store.load())
        }

    @OptIn(ExperimentalSerializationApi::class)
    private fun createStore(
        historyFile: File,
        dispatcher: CoroutineDispatcher,
    ): JsonAgentChatHistoryStore =
        JsonAgentChatHistoryStore(
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
