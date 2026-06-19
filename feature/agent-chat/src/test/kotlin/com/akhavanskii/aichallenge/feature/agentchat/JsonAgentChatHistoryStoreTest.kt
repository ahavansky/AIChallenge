package com.akhavanskii.aichallenge.feature.agentchat

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                    messages =
                        listOf(
                            AgentChatMessage(role = AgentChatRole.USER, text = "Remember this"),
                            AgentChatMessage(role = AgentChatRole.MODEL, text = "Stored answer"),
                            AgentChatMessage(role = AgentChatRole.MODEL, text = "Waiting", isLoading = true),
                        ),
                )

            store.save(snapshot)

            val expectedMessages = snapshot.messages.filterNot { it.isLoading }
            assertEquals(
                snapshot.copy(messages = expectedMessages),
                store.load(),
            )
        }

    @Test
    fun loadIgnoresLegacyModelAndTokenFields() =
        runTest {
            val historyFile = temporaryFolder.newFile("history.json")
            historyFile.writeText(
                """
                {
                  "selectedAgentModelName": "gemini-2.5-flash-lite",
                  "customTotalTokenLimit": 12345,
                  "memory": {},
                  "messages": [
                    {
                      "role": "USER",
                      "text": "Legacy question",
                      "tokenUsage": {
                        "totalTokens": 42
                      }
                    },
                    {
                      "role": "MODEL",
                      "text": "Legacy answer"
                    }
                  ]
                }
                """.trimIndent(),
            )
            val store = createStore(historyFile, StandardTestDispatcher(testScheduler))

            assertEquals(
                AgentChatHistorySnapshot(
                    messages =
                        listOf(
                            AgentChatMessage(role = AgentChatRole.USER, text = "Legacy question"),
                            AgentChatMessage(role = AgentChatRole.MODEL, text = "Legacy answer"),
                        ),
                ),
                store.load(),
            )
        }

    @Test
    fun saveAndLoadTaskContextRoundTrip() =
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
                            taskContext =
                                AgentChatTaskContext(
                                    goal = "build memory",
                                    constraints = listOf("tests stay offline"),
                                ),
                            lastRequest =
                                AgentChatMemoryRequestContext(
                                    includedLayers =
                                        listOf(
                                            AgentChatMemoryLayer.TASK_CONTEXT,
                                            AgentChatMemoryLayer.LONG_TERM_MARKDOWN,
                                        ),
                                    taskContextItemCount = 2,
                                    longTermMarkdownChars = 42,
                                    promptPreview = "preview",
                                ),
                        ),
                )

            store.save(snapshot)

            assertEquals(
                snapshot.copy(
                    memory =
                        snapshot.memory.copy(
                            lastRequest = snapshot.memory.lastRequest?.copy(promptPreview = ""),
                        ),
                ),
                store.load(),
            )
            assertFalse(historyFile.readText().contains("preview"))
        }

    @Test
    fun saveAndLoadLongTermMarkdownRoundTrip() =
        runTest {
            val memoryFile = File(temporaryFolder.root, DEFAULT_LONG_TERM_MEMORY_FILE_NAME)
            val store = createLongTermStore(memoryFile, StandardTestDispatcher(testScheduler))
            val memory =
                AgentChatLongTermMarkdown(
                    fileName = DEFAULT_LONG_TERM_MEMORY_FILE_NAME,
                    markdown =
                        """
                        # Preferences

                        - Answer briefly.
                        """.trimIndent(),
                )

            store.save(memory)

            assertEquals(memory, store.load())
            assertEquals(memory.markdown, memoryFile.readText())
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

    private fun createLongTermStore(
        memoryFile: File,
        dispatcher: CoroutineDispatcher,
    ): MarkdownAgentChatLongTermMemoryStore =
        MarkdownAgentChatLongTermMemoryStore(
            memoryFile = memoryFile,
            dispatcher = dispatcher,
        )
}
