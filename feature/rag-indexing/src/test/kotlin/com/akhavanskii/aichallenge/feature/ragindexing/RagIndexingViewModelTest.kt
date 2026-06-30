package com.akhavanskii.aichallenge.feature.ragindexing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RagIndexingViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun buildSuccessWritesIndexesAndCache() =
        runTest {
            val storage = FakeRagIndexingStorage(corpus = listOf(document(id = "alpha", text = "# Alpha\nalpha content")))
            val client = RecordingEmbeddingClient()
            val viewModel = viewModel(storage = storage, client = client)

            viewModel.onAction(RagIndexingAction.BuildIndex)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(RagIndexingPhase.SUCCESS, state.phase)
            assertEquals(2, state.indexSummaries.size)
            assertEquals(2, storage.indexes.size)
            assertEquals(2, storage.cache.size)
            assertEquals(2, state.progress.embedded)
            assertEquals(2, state.progress.total)
            assertEquals(0, state.progress.cachedCount)
            assertNotNull(storage.indexes[RagIndexingStrategy.FIXED])
            assertNotNull(storage.indexes[RagIndexingStrategy.STRUCTURE])
        }

    @Test
    fun buildFailureMapsEmbeddingErrorToUserFacingState() =
        runTest {
            val storage = FakeRagIndexingStorage(corpus = listOf(document(id = "alpha", text = "# Alpha\nalpha content")))
            val client =
                RecordingEmbeddingClient {
                    EmbeddingResult.Failure(EmbeddingError.Unreachable)
                }
            val viewModel = viewModel(storage = storage, client = client)

            viewModel.onAction(RagIndexingAction.BuildIndex)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(RagIndexingPhase.ERROR, state.phase)
            assertTrue(state.userFacingError.orEmpty().contains("Ollama endpoint is unreachable"))
        }

    @Test
    fun cancelStopsActiveBuildAndMarksCancelled() =
        runTest {
            val storage = FakeRagIndexingStorage(corpus = listOf(document(id = "alpha", text = "# Alpha\nalpha content")))
            val client =
                RecordingEmbeddingClient {
                    awaitCancellation()
                }
            val viewModel = viewModel(storage = storage, client = client)

            viewModel.onAction(RagIndexingAction.BuildIndex)

            assertEquals(RagIndexingPhase.BUILDING, viewModel.uiState.value.phase)
            assertTrue(client.calls.isNotEmpty())

            viewModel.onAction(RagIndexingAction.Cancel)
            advanceUntilIdle()

            assertEquals(RagIndexingPhase.CANCELLED, viewModel.uiState.value.phase)
        }

    @Test
    fun cacheReuseAvoidsDuplicateEmbeddingCalls() =
        runTest {
            val storage = FakeRagIndexingStorage(corpus = listOf(document(id = "alpha", text = "# Alpha\nalpha content")))
            val client = RecordingEmbeddingClient()
            val viewModel = viewModel(storage = storage, client = client)

            viewModel.onAction(RagIndexingAction.BuildIndex)
            advanceUntilIdle()
            val callsAfterFirstBuild = client.calls.size

            viewModel.onAction(RagIndexingAction.BuildIndex)
            advanceUntilIdle()

            assertEquals(callsAfterFirstBuild, client.calls.size)
            assertEquals(2, viewModel.uiState.value.progress.cachedCount)
        }

    @Test
    fun changedModelTextOrStrategyInvalidatesCacheKeys() =
        runTest {
            val storage = FakeRagIndexingStorage(corpus = listOf(document(id = "alpha", text = "# Alpha\nalpha content")))
            val client = RecordingEmbeddingClient()
            val viewModel = viewModel(storage = storage, client = client)

            viewModel.onAction(RagIndexingAction.BuildIndex)
            advanceUntilIdle()
            val inputsAfterFirstBuild = client.embeddedInputCount

            viewModel.onAction(RagIndexingAction.ModelChanged("other-model"))
            viewModel.onAction(RagIndexingAction.BuildIndex)
            advanceUntilIdle()
            assertTrue(client.embeddedInputCount > inputsAfterFirstBuild)
            val inputsAfterModelChange = client.embeddedInputCount

            storage.corpus = listOf(document(id = "alpha", text = "# Alpha\nchanged alpha content"))
            viewModel.onAction(RagIndexingAction.BuildIndex)
            advanceUntilIdle()
            assertTrue(client.embeddedInputCount > inputsAfterModelChange)
        }

    @Test
    fun fixedCacheDoesNotSatisfyStructureChunk() =
        runTest {
            val doc = document(id = "shared", text = "# Shared\nsame text")
            val fixedChunk = RagIndexer().chunk(doc, RagIndexingStrategy.FIXED.chunkingStrategy).single()
            val storage =
                FakeRagIndexingStorage(
                    corpus = listOf(doc),
                    cache =
                        listOf(
                            RagEmbeddingCacheEntry.from(
                                chunk = fixedChunk,
                                model = OllamaEmbeddingClient.DEFAULT_MODEL,
                                embedding = listOf(9.0, 9.0),
                            ),
                        ),
                )
            val client = RecordingEmbeddingClient()
            val viewModel = viewModel(storage = storage, client = client)

            viewModel.onAction(RagIndexingAction.BuildIndex)
            advanceUntilIdle()

            assertEquals(1, client.embeddedInputCount)
            assertEquals(2, storage.cache.size)
            assertEquals(1, viewModel.uiState.value.progress.cachedCount)
        }

    @Test
    fun searchEmbedsQueryUsesSelectedIndexAndRespectsTopK() =
        runTest {
            val storage =
                FakeRagIndexingStorage(
                    corpus =
                        listOf(
                            document(id = "alpha", text = "# Alpha\nalpha only"),
                            document(id = "beta", text = "# Beta\nbeta only"),
                        ),
                )
            val client = RecordingEmbeddingClient()
            val viewModel = viewModel(storage = storage, client = client)

            viewModel.onAction(RagIndexingAction.BuildIndex)
            advanceUntilIdle()

            viewModel.onAction(RagIndexingAction.QueryChanged("alpha question"))
            viewModel.onAction(RagIndexingAction.TopKChanged(1))
            viewModel.onAction(RagIndexingAction.Search)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(RagIndexingPhase.SUCCESS, state.phase)
            assertEquals(1, state.searchResults.size)
            assertEquals("Alpha", state.searchResults.single().title)
            assertTrue(client.calls.any { call -> call.inputs == listOf("alpha question") })
        }

    @Test
    fun compareUsesUserQueryAndTopKForRetrievalExamples() =
        runTest {
            val query = "Who is Captain Ahab searching for?"
            val storage =
                FakeRagIndexingStorage(
                    corpus =
                        listOf(
                            document(id = "ahab", text = "# Ahab\nAhab searches for the white whale."),
                            document(id = "ishmael", text = "# Ishmael\nIshmael goes to sea."),
                            document(id = "pequod", text = "# Pequod\nThe Pequod is the ship."),
                        ),
                )
            val client = RecordingEmbeddingClient()
            val viewModel = viewModel(storage = storage, client = client)

            viewModel.onAction(RagIndexingAction.QueryChanged(query))
            viewModel.onAction(RagIndexingAction.TopKChanged(2))
            viewModel.onAction(RagIndexingAction.CompareStrategies)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            val report = requireNotNull(storage.comparisonReport)
            val comparisonQuery = report.queries.single()
            assertEquals(RagIndexingPhase.SUCCESS, state.phase)
            assertEquals(1, report.queries.size)
            assertEquals(1, state.comparisonSummary?.queryCount)
            assertEquals(query, comparisonQuery.query)
            assertTrue(client.calls.any { call -> call.inputs == listOf(query) })
            assertFalse(
                client.calls.any { call ->
                    call.inputs.any { input ->
                        oldDemoQueries.any { oldQuery -> oldQuery in input }
                    }
                },
            )
            assertTrue(comparisonQuery.fixed.size <= 2)
            assertTrue(comparisonQuery.structure.size <= 2)
            assertEquals(listOf("fixed", "structure"), report.strategies.map { stats -> stats.strategy })
            assertTrue(storage.comparisonMarkdown.orEmpty().contains("Retrieval Examples"))
            assertEquals("/tmp/rag-index/comparison.json", state.comparisonSummary?.jsonPath)
            assertEquals("/tmp/rag-index/comparison.md", state.comparisonSummary?.markdownPath)
        }

    @Test
    fun compareWithBlankQueryShowsErrorAndAvoidsWork() =
        runTest {
            val storage =
                FakeRagIndexingStorage(
                    corpus = listOf(document(id = "alpha", text = "# Alpha\nalpha content")),
                )
            val client = RecordingEmbeddingClient()
            val viewModel = viewModel(storage = storage, client = client)

            viewModel.onAction(RagIndexingAction.CompareStrategies)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(RagIndexingPhase.ERROR, state.phase)
            assertEquals("Enter a query before compare.", state.userFacingError)
            assertEquals(0, client.calls.size)
            assertEquals(0, storage.loadCorpusCount)
            assertTrue(storage.indexes.isEmpty())
            assertEquals(null, storage.comparisonReport)
        }

    private fun viewModel(
        storage: FakeRagIndexingStorage,
        client: RecordingEmbeddingClient,
    ): RagIndexingViewModel =
        RagIndexingViewModel(
            embeddingClient = client,
            storage = storage,
            ioDispatcher = mainDispatcherRule.dispatcher,
            embeddingBatchSize = 2,
        )

    private fun document(
        id: String,
        text: String,
    ): RagDocument =
        RagDocument(
            id = id,
            title = id.replaceFirstChar { char -> char.uppercase() },
            source = "$id.md",
            text = text,
        )

    private class RecordingEmbeddingClient(
        private val responder: suspend (EmbeddingCall) -> EmbeddingResult = { call ->
            EmbeddingResult.Success(call.inputs.map { input -> input.embeddingForText() })
        },
    ) : EmbeddingClient {
        val calls = mutableListOf<EmbeddingCall>()
        val embeddedInputCount: Int
            get() = calls.sumOf { call -> call.inputs.size }

        override suspend fun embed(
            endpoint: String,
            model: String,
            inputs: List<String>,
        ): EmbeddingResult {
            val call = EmbeddingCall(endpoint = endpoint, model = model, inputs = inputs)
            calls += call
            return responder(call)
        }
    }

    private data class EmbeddingCall(
        val endpoint: String,
        val model: String,
        val inputs: List<String>,
    )

    private class FakeRagIndexingStorage(
        var corpus: List<RagDocument>,
        cache: List<RagEmbeddingCacheEntry> = emptyList(),
    ) : RagIndexingStorage {
        override val outputPaths =
            RagIndexingOutputPaths(
                fixedIndex = "/tmp/rag-index/fixed/index.json",
                structureIndex = "/tmp/rag-index/structure/index.json",
                embeddingCache = "/tmp/rag-index/cache/embeddings.json",
                comparisonJson = "/tmp/rag-index/comparison.json",
                comparisonMarkdown = "/tmp/rag-index/comparison.md",
            )
        var cache = cache
        val indexes = mutableMapOf<RagIndexingStrategy, RagIndex>()
        var comparisonReport: RagComparisonReport? = null
        var comparisonMarkdown: String? = null
        var loadCorpusCount = 0

        override suspend fun loadCorpus(): List<RagDocument> {
            loadCorpusCount += 1
            return corpus
        }

        override suspend fun loadEmbeddingCache(): List<RagEmbeddingCacheEntry> = cache

        override suspend fun writeEmbeddingCache(entries: List<RagEmbeddingCacheEntry>): String {
            cache = entries
            return outputPaths.embeddingCache.orEmpty()
        }

        override suspend fun loadIndex(strategy: RagIndexingStrategy): RagIndex? = indexes[strategy]

        override suspend fun writeIndex(
            strategy: RagIndexingStrategy,
            index: RagIndex,
        ): String {
            indexes[strategy] = index
            return when (strategy) {
                RagIndexingStrategy.FIXED -> outputPaths.fixedIndex.orEmpty()
                RagIndexingStrategy.STRUCTURE -> outputPaths.structureIndex.orEmpty()
            }
        }

        override suspend fun writeComparison(
            report: RagComparisonReport,
            markdown: String,
        ): RagComparisonOutputPaths {
            comparisonReport = report
            comparisonMarkdown = markdown
            return RagComparisonOutputPaths(
                jsonPath = outputPaths.comparisonJson.orEmpty(),
                markdownPath = outputPaths.comparisonMarkdown.orEmpty(),
            )
        }
    }
}

private val oldDemoQueries =
    listOf(
        "How does the app provide Gemini API keys?",
        "What modules own network and feature logic?",
        "How does fixed chunking use overlap?",
        "What metadata is needed for RAG chunks?",
        "How should emulator endpoints reach host services?",
    )

private fun String.embeddingForText(): List<Double> {
    val normalized = lowercase()
    return when {
        "alpha" in normalized || "api keys" in normalized -> listOf(1.0, 0.0)
        "beta" in normalized || "modules" in normalized -> listOf(0.0, 1.0)
        "overlap" in normalized -> listOf(0.7, 0.3)
        "metadata" in normalized -> listOf(0.4, 0.6)
        "emulator" in normalized -> listOf(0.2, 0.8)
        else -> listOf(0.5, 0.5)
    }
}
