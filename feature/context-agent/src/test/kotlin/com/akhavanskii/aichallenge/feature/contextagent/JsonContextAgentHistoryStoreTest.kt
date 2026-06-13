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
                    contextState =
                        ContextCompressionState(
                            summary = "Stored summary",
                            summarizedMessageCount = 10,
                            latestStats =
                                ContextCompressionStats(
                                    fullPromptTokens = 1_000,
                                    compressedPromptTokens = 400,
                                    savedPromptTokens = 600,
                                    savedPromptPercent = 60,
                                    summarizedMessageCount = 10,
                                    rawMessageCount = 8,
                                    requestMessageCount = 9,
                                ),
                        ),
                    comparison =
                        ContextQualityComparison(
                            fullHistoryAnswer = "Full",
                            compressedHistoryAnswer = "Compressed",
                            evaluation = "Similar quality with fewer tokens.",
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
                            ContextAgentMessage(
                                role = ContextAgentRole.MODEL,
                                text = "Quality comparison",
                                includeInContext = false,
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
