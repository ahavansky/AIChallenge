package com.akhavanskii.aichallenge.feature.ragindexing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akhavanskii.aichallenge.core.network.AgentMessage
import com.akhavanskii.aichallenge.core.network.GeminiGenerationConfig
import com.akhavanskii.aichallenge.core.network.GeminiNetworkError
import com.akhavanskii.aichallenge.core.network.GeminiResult
import com.akhavanskii.aichallenge.core.network.LlmAgent
import com.akhavanskii.aichallenge.core.utils.normalizedPromptOrNull
import com.akhavanskii.aichallenge.feature.common.ResponsePaneState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class RagIndexingViewModel private constructor(
    private val embeddingClient: EmbeddingClient,
    private val llmAgent: LlmAgent,
    private val storage: RagIndexingStorage,
    private val indexer: RagIndexer,
    private val ioDispatcher: CoroutineDispatcher,
    private val embeddingBatchSize: Int,
    marker: Unit?,
) : ViewModel() {
    @Inject
    constructor(
        embeddingClient: EmbeddingClient,
        llmAgent: LlmAgent,
        storage: RagIndexingStorage,
    ) : this(
        embeddingClient = embeddingClient,
        llmAgent = llmAgent,
        storage = storage,
        indexer = RagIndexer(),
        ioDispatcher = Dispatchers.IO,
        embeddingBatchSize = DEFAULT_EMBEDDING_BATCH_SIZE,
        marker = null,
    )

    internal constructor(
        embeddingClient: EmbeddingClient,
        storage: RagIndexingStorage,
        llmAgent: LlmAgent = UnconfiguredLlmAgent,
        indexer: RagIndexer = RagIndexer(),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        embeddingBatchSize: Int = DEFAULT_EMBEDDING_BATCH_SIZE,
    ) : this(
        embeddingClient = embeddingClient,
        llmAgent = llmAgent,
        storage = storage,
        indexer = indexer,
        ioDispatcher = ioDispatcher,
        embeddingBatchSize = embeddingBatchSize,
        marker = null,
    )

    private val mutableUiState = MutableStateFlow(RagIndexingUiState())
    val uiState: StateFlow<RagIndexingUiState> = mutableUiState.asStateFlow()

    private val loadedIndexes = mutableMapOf<RagIndexingStrategy, RagIndex>()
    private var activeJob: Job? = null

    init {
        loadCorpusDocuments()
    }

    fun onAction(action: RagIndexingAction) {
        when (action) {
            is RagIndexingAction.EndpointChanged -> updateWhenIdle { it.copy(endpoint = action.endpoint.trim(), userFacingError = null) }
            is RagIndexingAction.ModelChanged -> onModelChanged(action.model)
            is RagIndexingAction.StrategyChanged -> updateWhenIdle { it.copy(selectedStrategy = action.strategy, userFacingError = null) }
            is RagIndexingAction.LlmModelChanged -> onLlmModelChanged(action.model)
            is RagIndexingAction.CorpusDocumentToggled -> onCorpusDocumentToggled(action.documentId, action.selected)
            is RagIndexingAction.QueryChanged ->
                updateWhenIdle {
                    it
                        .copy(
                            query = action.query,
                            userFacingError = null,
                        ).resetAgentOutputs()
                }
            is RagIndexingAction.ExpectedAnswerChanged ->
                updateWhenIdle {
                    it
                        .copy(
                            expectedAnswer = action.expectedAnswer,
                            userFacingError = null,
                        ).resetEvaluationOutput()
                }
            is RagIndexingAction.ExpectedSourcesChanged ->
                updateWhenIdle {
                    it
                        .copy(
                            expectedSources = action.expectedSources,
                            userFacingError = null,
                        ).resetEvaluationOutput()
                }
            is RagIndexingAction.TopKBeforeFilterChanged ->
                updateWhenIdle {
                    val topKBeforeFilter = action.topK.coerceIn(MIN_TOP_K, MAX_TOP_K)
                    it
                        .copy(
                            topKBeforeFilter = topKBeforeFilter,
                            topKAfterFilter = it.topKAfterFilter.coerceAtMost(topKBeforeFilter),
                            searchResults = emptyList(),
                            searchRetrievalStats = null,
                            userFacingError = null,
                        ).resetAgentOutputs()
                }
            is RagIndexingAction.TopKAfterFilterChanged ->
                updateWhenIdle {
                    it
                        .copy(
                            topKAfterFilter = action.topK.coerceIn(MIN_TOP_K, it.topKBeforeFilter.coerceAtMost(MAX_TOP_K)),
                            searchResults = emptyList(),
                            searchRetrievalStats = null,
                            userFacingError = null,
                        ).resetAgentOutputs()
                }
            is RagIndexingAction.SimilarityThresholdChanged ->
                updateWhenIdle {
                    it
                        .copy(
                            similarityThreshold = action.threshold.coerceIn(MIN_SIMILARITY_THRESHOLD, MAX_SIMILARITY_THRESHOLD),
                            searchResults = emptyList(),
                            searchRetrievalStats = null,
                            userFacingError = null,
                        ).resetAgentOutputs()
                }
            RagIndexingAction.BuildIndex -> buildIndex()
            RagIndexingAction.CompareStrategies -> compareStrategies()
            RagIndexingAction.Search -> search()
            RagIndexingAction.CompareModes -> compareModes()
            RagIndexingAction.Cancel -> cancelActiveJob()
        }
    }

    private fun loadCorpusDocuments() {
        viewModelScope.launch {
            val result =
                runCatching {
                    withContext(ioDispatcher) { storage.listCorpus() }
                }
            result
                .onSuccess { corpus ->
                    val documents = corpus.map { info -> info.toUi() }
                    val documentIds = documents.map { document -> document.id }.toSet()
                    val defaultSelectedIds =
                        documents
                            .filter { document ->
                                document.selectedByDefault
                            }.map { document -> document.id }
                            .toSet()
                    mutableUiState.update { current ->
                        val retainedSelection = current.selectedCorpusDocumentIds.intersect(documentIds)
                        current.copy(
                            corpusDocuments = documents,
                            selectedCorpusDocumentIds =
                                retainedSelection
                                    .ifEmpty { defaultSelectedIds }
                                    .ifEmpty { documents.firstOrNull()?.let { document -> setOf(document.id) }.orEmpty() },
                        )
                    }
                }.onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    mutableUiState.update { current ->
                        current.copy(userFacingError = throwable.message ?: "Failed to load RAG corpus list.")
                    }
                }
        }
    }

    private fun onModelChanged(model: String) {
        if (mutableUiState.value.isBusy) return
        loadedIndexes.clear()
        mutableUiState.update { current ->
            current
                .copy(
                    model = model.trim(),
                    indexSummaries = emptyList(),
                    comparisonSummary = null,
                    comparisonReport = null,
                    searchResults = emptyList(),
                    searchRetrievalStats = null,
                    userFacingError = null,
                ).resetAgentOutputs()
        }
    }

    private fun onLlmModelChanged(model: RagLlmModelOption) {
        updateWhenIdle { current ->
            current
                .copy(
                    selectedLlmModel = model,
                    userFacingError = null,
                ).resetAgentOutputs()
        }
    }

    private fun onCorpusDocumentToggled(
        documentId: String,
        selected: Boolean,
    ) {
        if (mutableUiState.value.isBusy) return
        loadedIndexes.clear()
        mutableUiState.update { current ->
            val updatedSelection =
                if (selected) {
                    current.selectedCorpusDocumentIds + documentId
                } else {
                    current.selectedCorpusDocumentIds - documentId
                }
            current
                .copy(
                    selectedCorpusDocumentIds = updatedSelection,
                    indexSummaries = emptyList(),
                    comparisonSummary = null,
                    comparisonReport = null,
                    searchResults = emptyList(),
                    searchRetrievalStats = null,
                    userFacingError = null,
                ).resetAgentOutputs()
        }
    }

    private fun buildIndex() {
        val request = mutableUiState.value
        if (request.isBusy) return
        val documentIds = request.selectedDocumentIdsOrShowError() ?: return

        launchActiveWork(RagIndexingPhase.BUILDING) {
            val endpoint = request.endpoint.trim()
            val model = request.model.trim()
            val result =
                withContext(ioDispatcher) {
                    val documents = loadSelectedDocuments(documentIds)
                    buildIndexesInternal(
                        endpoint = endpoint,
                        model = model,
                        documents = documents,
                        strategies = listOf(request.selectedStrategy),
                        phase = RagIndexingPhase.BUILDING,
                    )
                }

            mutableUiState.update { current ->
                current.copy(
                    phase = RagIndexingPhase.SUCCESS,
                    progress = current.progress.copy(outputPaths = result.outputPaths),
                    indexSummaries = result.summaries,
                    comparisonSummary = null,
                    comparisonReport = null,
                    searchResults = emptyList(),
                    searchRetrievalStats = null,
                    userFacingError = null,
                )
            }
        }
    }

    private fun compareStrategies() {
        val request = mutableUiState.value
        if (request.isBusy) return

        val query = request.query.trim()
        if (query.isBlank()) {
            mutableUiState.update { current ->
                current.copy(
                    phase = RagIndexingPhase.ERROR,
                    userFacingError = "Enter a query before compare.",
                )
            }
            return
        }
        val documentIds = request.selectedDocumentIdsOrShowError() ?: return

        launchActiveWork(RagIndexingPhase.COMPARING) {
            val endpoint = request.endpoint.trim()
            val model = request.model.trim()
            val settings = request.retrievalSettings()
            val result =
                withContext(ioDispatcher) {
                    val indexes =
                        loadOrBuildIndexes(
                            endpoint = endpoint,
                            model = model,
                            documentIds = documentIds,
                            strategies = RagIndexingStrategy.entries,
                            phase = RagIndexingPhase.COMPARING,
                        )
                    val report =
                        compareIndexes(
                            endpoint = endpoint,
                            model = model,
                            indexes = indexes,
                            query = query,
                            settings = settings,
                        )
                    val markdown = report.toMarkdown()
                    val paths = storage.writeComparison(report = report, markdown = markdown)
                    val summary = report.toSummary(paths)
                    ComparisonResult(report = report, paths = paths, summary = summary)
                }

            mutableUiState.update { current ->
                current.copy(
                    phase = RagIndexingPhase.SUCCESS,
                    progress =
                        current.progress.copy(
                            outputPaths =
                                current.progress.outputPaths.copy(
                                    comparisonJson = result.paths.jsonPath,
                                    comparisonMarkdown = result.paths.markdownPath,
                                ),
                        ),
                    comparisonSummary = result.summary,
                    comparisonReport = result.report,
                    userFacingError = null,
                )
            }
        }
    }

    private fun search() {
        val request = mutableUiState.value
        if (request.isBusy) return
        val documentIds = request.selectedDocumentIdsOrShowError() ?: return

        launchActiveWork(RagIndexingPhase.SEARCHING) {
            val endpoint = request.endpoint.trim()
            val model = request.model.trim()
            val searchRun =
                withContext(ioDispatcher) {
                    searchIndex(
                        endpoint = endpoint,
                        model = model,
                        strategy = request.selectedStrategy,
                        query = request.query,
                        settings = request.retrievalSettings(),
                        documentIds = documentIds,
                    )
                }

            mutableUiState.update { current ->
                current.copy(
                    phase = RagIndexingPhase.SUCCESS,
                    searchResults = searchRun.results,
                    searchRetrievalStats = searchRun.stats,
                    userFacingError = null,
                )
            }
        }
    }

    private fun compareModes() {
        val request = mutableUiState.value
        if (request.isBusy) return
        val question = request.query.normalizedPromptOrNull()
        if (question == null) {
            mutableUiState.update { current ->
                current.copy(
                    phase = RagIndexingPhase.ERROR,
                    userFacingError = "Enter a question before comparing RAG modes.",
                )
            }
            return
        }
        val documentIds = request.selectedDocumentIdsOrShowError() ?: return

        launchActiveWork(RagIndexingPhase.ANSWERING) {
            val endpoint = request.endpoint.trim()
            val embeddingModel = request.model.trim()
            val llmModelName = request.selectedLlmModel.modelName
            val strategy = request.selectedStrategy
            val settings = request.retrievalSettings()

            mutableUiState.update { current ->
                current.copy(
                    noRagAnswerState = ResponsePaneState.Loading,
                    baselineRagAnswerState = ResponsePaneState.Loading,
                    improvedRagAnswerState = ResponsePaneState.Loading,
                    qualityEvaluationState = ResponsePaneState.Empty("Waiting for all answers before quality comparison."),
                    baselineRagContextResults = emptyList(),
                    improvedRagCandidateResults = emptyList(),
                    improvedRagContextResults = emptyList(),
                    baselineRetrievalStats = null,
                    improvedRetrievalStats = null,
                    rewrittenQuery = null,
                    queryRewriteNote = null,
                    userFacingError = null,
                )
            }

            val noRagResult =
                llmAgent.sendMessage(
                    prompt = RagAgentPrompts.buildNoRagPrompt(question),
                    generationConfig = null,
                    modelName = llmModelName,
                )
            mutableUiState.update { current ->
                current.copy(noRagAnswerState = noRagResult.toPaneState())
            }

            val baselineRun =
                runCatching {
                    runRagAnswer(
                        endpoint = endpoint,
                        embeddingModel = embeddingModel,
                        llmModelName = llmModelName,
                        strategy = strategy,
                        question = question,
                        retrievalQuery = question,
                        settings = settings,
                        useFilter = false,
                        documentIds = documentIds,
                    )
                }.getOrElse { throwable ->
                    mutableUiState.update { current ->
                        current.copy(
                            baselineRagAnswerState = ResponsePaneState.Error(throwable.toUserFacingMessage()),
                        )
                    }
                    throw throwable
                }
            mutableUiState.update { current ->
                current.copy(
                    baselineRagAnswerState = baselineRun.answer.toPaneState(),
                    baselineRagContextResults = baselineRun.retrieved.map { result -> result.toUi() },
                    baselineRetrievalStats = baselineRun.stats,
                )
            }

            val rewrite = rewriteQuery(question = question, llmModelName = llmModelName)
            mutableUiState.update { current ->
                current.copy(
                    rewrittenQuery = rewrite.query,
                    queryRewriteNote = rewrite.note,
                )
            }

            val improvedRun =
                runCatching {
                    runRagAnswer(
                        endpoint = endpoint,
                        embeddingModel = embeddingModel,
                        llmModelName = llmModelName,
                        strategy = strategy,
                        question = question,
                        retrievalQuery = rewrite.query,
                        settings = settings,
                        useFilter = true,
                        documentIds = documentIds,
                    )
                }.getOrElse { throwable ->
                    mutableUiState.update { current ->
                        current.copy(
                            improvedRagAnswerState = ResponsePaneState.Error(throwable.toUserFacingMessage()),
                        )
                    }
                    throw throwable
                }
            mutableUiState.update { current ->
                current.copy(
                    improvedRagAnswerState = improvedRun.answer.toPaneState(),
                    improvedRagCandidateResults = improvedRun.candidates.map { result -> result.toUi() },
                    improvedRagContextResults = improvedRun.retrieved.map { result -> result.toUi() },
                    improvedRetrievalStats = improvedRun.stats,
                )
            }

            val noRagAnswer = noRagResult.successValue()
            val baselineRagAnswer = baselineRun.answer.successValue()
            val improvedRagAnswer = improvedRun.answer.successValue()
            if (noRagAnswer == null || baselineRagAnswer == null || improvedRagAnswer == null) {
                mutableUiState.update { current ->
                    current.copy(
                        phase = RagIndexingPhase.SUCCESS,
                        qualityEvaluationState =
                            ResponsePaneState.Error(
                                "Quality comparison skipped: all three modes must return successful answers.",
                            ),
                    )
                }
                return@launchActiveWork
            }

            mutableUiState.update { current ->
                current.copy(
                    phase = RagIndexingPhase.EVALUATING,
                    qualityEvaluationState = ResponsePaneState.Loading,
                )
            }
            val evaluationPrompt =
                RagAgentPrompts.buildEvaluationPrompt(
                    question = question,
                    rewrittenQuery = rewrite.query,
                    queryRewriteNote = rewrite.note,
                    expectedAnswer = request.expectedAnswer,
                    expectedSources = request.expectedSources,
                    baselineRetrievedResults = baselineRun.retrieved,
                    improvedCandidateResults = improvedRun.candidates,
                    improvedRetrievedResults = improvedRun.retrieved,
                    noRagAnswer = noRagAnswer,
                    baselineRagAnswer = baselineRagAnswer,
                    improvedRagAnswer = improvedRagAnswer,
                )
            val evaluationResult =
                llmAgent.sendMessage(
                    prompt = evaluationPrompt,
                    generationConfig = null,
                    modelName = llmModelName,
                )
            mutableUiState.update { current ->
                current.copy(
                    phase = RagIndexingPhase.SUCCESS,
                    qualityEvaluationState = evaluationResult.toPaneState(),
                    userFacingError = null,
                )
            }
        }
    }

    private suspend fun runRagAnswer(
        endpoint: String,
        embeddingModel: String,
        llmModelName: String,
        strategy: RagIndexingStrategy,
        question: String,
        retrievalQuery: String,
        settings: RagRetrievalSettings,
        useFilter: Boolean,
        documentIds: Set<String>,
    ): RagAnswerRun {
        val index =
            loadOrBuildIndexes(
                endpoint = endpoint,
                model = embeddingModel,
                documentIds = documentIds,
                strategies = listOf(strategy),
                phase = RagIndexingPhase.ANSWERING,
            ).getValue(strategy)
        val queryEmbedding =
            embedBatch(
                endpoint = endpoint,
                model = embeddingModel,
                inputs = listOf(retrievalQuery),
            ).single()
        val search =
            if (useFilter) {
                RagSearch.searchWithFilter(
                    index = index,
                    queryEmbedding = queryEmbedding,
                    topKBeforeFilter = settings.topKBeforeFilter,
                    topKAfterFilter = settings.topKAfterFilter,
                    similarityThreshold = settings.similarityThreshold,
                )
            } else {
                val results =
                    RagSearch.search(
                        index = index,
                        queryEmbedding = queryEmbedding,
                        topK = settings.topKAfterFilter,
                    )
                RagFilteredSearchResult(
                    candidates = results,
                    filtered = results,
                    selected = results,
                )
            }
        val prompt =
            RagAgentPrompts.buildRagPrompt(
                question = question,
                results = search.selected,
            )
        val answer =
            llmAgent.sendMessage(
                prompt = prompt,
                generationConfig = null,
                modelName = llmModelName,
            )

        return RagAnswerRun(
            answer = answer,
            candidates = search.candidates,
            retrieved = search.selected,
            stats =
                search.toStatsUi(
                    settings = settings,
                    similarityThreshold = if (useFilter) settings.similarityThreshold else null,
                ),
        )
    }

    private suspend fun rewriteQuery(
        question: String,
        llmModelName: String,
    ): QueryRewriteRun {
        val result =
            llmAgent.sendMessage(
                prompt = RagAgentPrompts.buildQueryRewritePrompt(question),
                generationConfig = null,
                modelName = llmModelName,
            )

        return when (result) {
            is GeminiResult.Success -> {
                val rewritten = result.value.normalizedRewriteOrNull()
                if (rewritten == null) {
                    QueryRewriteRun(
                        query = question,
                        note = "Query rewrite returned empty text; original query used.",
                    )
                } else {
                    QueryRewriteRun(
                        query = rewritten,
                        note = "Query rewrite applied.",
                    )
                }
            }
            is GeminiResult.Failure ->
                QueryRewriteRun(
                    query = question,
                    note = "Query rewrite failed: ${result.error.userMessage.trim().trimEnd('.')}. Original query used.",
                )
        }
    }

    private fun cancelActiveJob() {
        val job = activeJob
        if (job?.isActive != true) {
            return
        }

        job.cancel()
        activeJob = null
        mutableUiState.update { current ->
            current.copy(
                phase = RagIndexingPhase.CANCELLED,
                userFacingError = null,
            )
        }
    }

    private fun launchActiveWork(
        phase: RagIndexingPhase,
        block: suspend () -> Unit,
    ) {
        activeJob?.cancel()
        mutableUiState.update { current ->
            current.copy(
                phase = phase,
                progress =
                    RagIndexingProgress(
                        outputPaths = current.progress.outputPaths,
                    ),
                userFacingError = null,
                searchResults = if (phase == RagIndexingPhase.SEARCHING) emptyList() else current.searchResults,
                searchRetrievalStats = if (phase == RagIndexingPhase.SEARCHING) null else current.searchRetrievalStats,
            )
        }
        activeJob =
            viewModelScope.launch {
                try {
                    block()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    mutableUiState.update { current ->
                        current.copy(
                            phase = RagIndexingPhase.ERROR,
                            userFacingError = throwable.toUserFacingMessage(),
                        )
                    }
                } finally {
                    activeJob = null
                }
            }
    }

    private suspend fun buildIndexesInternal(
        endpoint: String,
        model: String,
        documents: List<RagDocument>,
        strategies: List<RagIndexingStrategy>,
        phase: RagIndexingPhase,
    ): BuildResult {
        currentCoroutineContext().ensureActive()
        if (documents.isEmpty()) {
            throw UserFacingRagException("No RAG corpus documents selected.")
        }
        if (strategies.isEmpty()) {
            throw UserFacingRagException("Select at least one indexing strategy.")
        }

        var outputPaths = RagIndexingOutputPaths()
        val chunksByStrategy = mutableMapOf<RagIndexingStrategy, List<RagChunk>>()
        strategies.forEach { strategy ->
            currentCoroutineContext().ensureActive()
            mutableUiState.updateProgress(
                phase = phase,
                currentStrategy = strategy,
                total = 0,
                embedded = 0,
                cachedCount = 0,
                outputPaths = outputPaths,
            )
            chunksByStrategy[strategy] =
                documents.flatMap { document ->
                    currentCoroutineContext().ensureActive()
                    indexer.chunk(document, strategy.chunkingStrategy)
                }
        }

        val totalChunks = chunksByStrategy.values.sumOf { chunks -> chunks.size }
        var embeddedCount = 0
        var cachedCount = 0
        mutableUiState.updateProgress(
            phase = phase,
            total = totalChunks,
            embedded = embeddedCount,
            cachedCount = cachedCount,
            outputPaths = outputPaths,
        )

        val cacheByKey =
            storage
                .loadEmbeddingCache()
                .associateBy { entry -> entry.cacheKey }
                .toMutableMap()
        val summaries = mutableListOf<RagIndexSummary>()
        val indexes = mutableMapOf<RagIndexingStrategy, RagIndex>()

        strategies.forEach { strategy ->
            currentCoroutineContext().ensureActive()
            val chunks = chunksByStrategy.getValue(strategy)
            val embeddingsByChunkId = mutableMapOf<String, List<Double>>()
            val missingChunks = mutableListOf<RagChunk>()

            chunks.forEach { chunk ->
                currentCoroutineContext().ensureActive()
                val cacheKey = chunk.cacheKey(model)
                val cachedEntry = cacheByKey[cacheKey]
                if (cachedEntry != null) {
                    embeddingsByChunkId[chunk.chunkId] = cachedEntry.embedding
                    embeddedCount += 1
                    cachedCount += 1
                    mutableUiState.updateProgress(
                        phase = phase,
                        currentStrategy = strategy,
                        total = totalChunks,
                        embedded = embeddedCount,
                        cachedCount = cachedCount,
                        outputPaths = outputPaths,
                    )
                } else {
                    missingChunks += chunk
                }
            }

            missingChunks.chunked(embeddingBatchSize).forEach { batch ->
                currentCoroutineContext().ensureActive()
                val embeddings =
                    embedBatch(
                        endpoint = endpoint,
                        model = model,
                        inputs = batch.map { chunk -> chunk.text },
                    )
                batch.zip(embeddings).forEach { (chunk, embedding) ->
                    val entry =
                        RagEmbeddingCacheEntry.from(
                            chunk = chunk,
                            model = model,
                            embedding = embedding,
                        )
                    cacheByKey[entry.cacheKey] = entry
                    embeddingsByChunkId[chunk.chunkId] = embedding
                    embeddedCount += 1
                }
                mutableUiState.updateProgress(
                    phase = phase,
                    currentStrategy = strategy,
                    total = totalChunks,
                    embedded = embeddedCount,
                    cachedCount = cachedCount,
                    outputPaths = outputPaths,
                )
            }

            val index =
                indexer.buildIndexFromChunks(
                    documents = documents,
                    chunks = chunks,
                    strategy = strategy.chunkingStrategy,
                    model = model,
                    embeddingsByChunkId = embeddingsByChunkId,
                    metadata =
                        mapOf(
                            "cache_reused_count" to cachedCount.toString(),
                            "embedding_model" to model,
                            "selected_document_ids" to documents.joinToString(separator = ",") { document -> document.id },
                        ),
                )
            val indexPath = storage.writeIndex(strategy = strategy, index = index)
            indexes[strategy] = index
            summaries += index.summary(strategy = strategy, outputPath = indexPath)
            outputPaths =
                when (strategy) {
                    RagIndexingStrategy.FIXED -> outputPaths.copy(fixedIndex = indexPath)
                    RagIndexingStrategy.STRUCTURE -> outputPaths.copy(structureIndex = indexPath)
                }
            mutableUiState.updateProgress(
                phase = phase,
                currentStrategy = strategy,
                total = totalChunks,
                embedded = embeddedCount,
                cachedCount = cachedCount,
                outputPaths = outputPaths,
            )
        }

        val cachePath = storage.writeEmbeddingCache(cacheByKey.values.toList())
        outputPaths = outputPaths.copy(embeddingCache = cachePath)
        mutableUiState.updateProgress(
            phase = phase,
            total = totalChunks,
            embedded = embeddedCount,
            cachedCount = cachedCount,
            outputPaths = outputPaths,
        )
        loadedIndexes.clear()
        loadedIndexes.putAll(indexes)

        return BuildResult(
            indexes = indexes,
            summaries = summaries,
            outputPaths = outputPaths,
        )
    }

    private suspend fun loadOrBuildIndexes(
        endpoint: String,
        model: String,
        documentIds: Set<String>,
        strategies: List<RagIndexingStrategy>,
        phase: RagIndexingPhase,
    ): Map<RagIndexingStrategy, RagIndex> {
        val documents = loadSelectedDocuments(documentIds)
        val selectedSourceHash = RagEmbeddingCacheKeys.sourceHash(documents)
        val requestedStrategies = strategies.distinct()
        if (requestedStrategies.isEmpty()) {
            throw UserFacingRagException("Select at least one indexing strategy.")
        }
        val inMemoryIndexes =
            requestedStrategies.associateWith { strategy -> loadedIndexes[strategy] }
        if (inMemoryIndexes.values.all { index -> index?.model == model && index.sourceHash == selectedSourceHash }) {
            return inMemoryIndexes.mapValues { (_, index) -> index ?: error("Missing in-memory index.") }
        }

        val storedIndexes =
            requestedStrategies.associateWith { strategy -> storage.loadIndex(strategy) }
        if (storedIndexes.values.all { index -> index?.model == model && index.sourceHash == selectedSourceHash }) {
            val indexes = storedIndexes.mapValues { (_, index) -> index ?: error("Missing stored index.") }
            loadedIndexes.putAll(indexes)
            mutableUiState.update { current ->
                current.copy(
                    indexSummaries =
                        indexes.map { (strategy, index) ->
                            index.summary(strategy, strategy.outputPathFrom(storage.outputPaths))
                        },
                )
            }
            return indexes
        }

        return buildIndexesInternal(
            endpoint = endpoint,
            model = model,
            documents = documents,
            strategies = requestedStrategies,
            phase = phase,
        ).indexes
    }

    private suspend fun loadSelectedDocuments(documentIds: Set<String>): List<RagDocument> {
        val documents = storage.loadCorpus(documentIds)
        if (documents.isEmpty()) {
            throw UserFacingRagException("No RAG corpus documents selected.")
        }
        return documents
    }

    private suspend fun compareIndexes(
        endpoint: String,
        model: String,
        indexes: Map<RagIndexingStrategy, RagIndex>,
        query: String,
        settings: RagRetrievalSettings,
    ): RagComparisonReport {
        currentCoroutineContext().ensureActive()
        val queryEmbedding =
            embedBatch(
                endpoint = endpoint,
                model = model,
                inputs = listOf(query),
            ).single()

        return RagComparisonReport(
            model = model,
            settings = settings,
            strategies =
                listOf(
                    indexes.getValue(RagIndexingStrategy.FIXED).comparisonStats(RagIndexingStrategy.FIXED),
                    indexes.getValue(RagIndexingStrategy.STRUCTURE).comparisonStats(RagIndexingStrategy.STRUCTURE),
                ),
            queries =
                listOf(
                    RagComparisonQueryReport(
                        originalQuery = query,
                        rewrittenQuery = null,
                        fixed = indexes.getValue(RagIndexingStrategy.FIXED).comparisonRetrieval(queryEmbedding, settings),
                        structure = indexes.getValue(RagIndexingStrategy.STRUCTURE).comparisonRetrieval(queryEmbedding, settings),
                    ),
                ),
        )
    }

    private suspend fun searchIndex(
        endpoint: String,
        model: String,
        strategy: RagIndexingStrategy,
        query: String,
        settings: RagRetrievalSettings,
        documentIds: Set<String>,
    ): SearchRun {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            throw UserFacingRagException("Enter a query before search.")
        }

        val documents = loadSelectedDocuments(documentIds)
        val selectedSourceHash = RagEmbeddingCacheKeys.sourceHash(documents)
        val index =
            loadedIndexes[strategy]
                ?.takeIf { index -> index.model == model.trim() && index.sourceHash == selectedSourceHash }
                ?: storage
                    .loadIndex(strategy)
                    ?.takeIf { index -> index.model == model.trim() && index.sourceHash == selectedSourceHash }
                    ?.also { index -> loadedIndexes[strategy] = index }
                ?: throw UserFacingRagException("Build the selected corpus index before search.")

        val queryEmbedding =
            embedBatch(
                endpoint = endpoint,
                model = model,
                inputs = listOf(normalizedQuery),
            ).single()

        val search =
            RagSearch
                .searchWithFilter(
                    index = index,
                    queryEmbedding = queryEmbedding,
                    topKBeforeFilter = settings.topKBeforeFilter,
                    topKAfterFilter = settings.topKAfterFilter,
                    similarityThreshold = settings.similarityThreshold,
                )

        return SearchRun(
            results = search.selected.map { result -> result.toUi() },
            stats = search.toStatsUi(settings = settings, similarityThreshold = settings.similarityThreshold),
        )
    }

    private suspend fun embedBatch(
        endpoint: String,
        model: String,
        inputs: List<String>,
    ): List<List<Double>> {
        val result =
            embeddingClient.embed(
                endpoint = endpoint,
                model = model.trim(),
                inputs = inputs,
            )
        return when (result) {
            is EmbeddingResult.Success -> {
                validateEmbeddingVectors(result.embeddings, inputs.size)?.let { error ->
                    throw EmbeddingRagException(error)
                }
                result.embeddings
            }
            is EmbeddingResult.Failure -> throw EmbeddingRagException(result.error)
        }
    }

    private fun RagIndexingUiState.selectedDocumentIdsOrShowError(): Set<String>? {
        if (corpusDocuments.isNotEmpty() && selectedCorpusDocumentIds.isEmpty()) {
            mutableUiState.update { current ->
                current.copy(
                    phase = RagIndexingPhase.ERROR,
                    userFacingError = "Select at least one corpus document.",
                )
            }
            return null
        }
        return selectedCorpusDocumentIds
    }

    private fun updateWhenIdle(transform: (RagIndexingUiState) -> RagIndexingUiState) {
        mutableUiState.update { current ->
            if (current.isBusy) {
                current
            } else {
                transform(current)
            }
        }
    }

    private fun Throwable.toUserFacingMessage(): String =
        when (this) {
            is EmbeddingRagException -> error.userMessage()
            is UserFacingRagException -> message.orEmpty()
            else -> message?.takeIf { it.isNotBlank() } ?: "RAG indexing failed."
        }

    private data class BuildResult(
        val indexes: Map<RagIndexingStrategy, RagIndex>,
        val summaries: List<RagIndexSummary>,
        val outputPaths: RagIndexingOutputPaths,
    )

    private data class ComparisonResult(
        val report: RagComparisonReport,
        val paths: RagComparisonOutputPaths,
        val summary: RagComparisonSummary,
    )

    private data class SearchRun(
        val results: List<RagSearchResultUi>,
        val stats: RagRetrievalStatsUi,
    )

    private data class QueryRewriteRun(
        val query: String,
        val note: String,
    )

    private data class RagAnswerRun(
        val answer: GeminiResult<String>,
        val candidates: List<RagSearchResult>,
        val retrieved: List<RagSearchResult>,
        val stats: RagRetrievalStatsUi,
    )

    private class EmbeddingRagException(
        val error: EmbeddingError,
    ) : RuntimeException(error.userMessage())

    private class UserFacingRagException(
        override val message: String,
    ) : RuntimeException(message)

    private companion object {
        const val DEFAULT_EMBEDDING_BATCH_SIZE = 8
        const val MIN_TOP_K = RagIndexingUiState.MIN_TOP_K
        const val MAX_TOP_K = RagIndexingUiState.MAX_TOP_K
        const val MIN_SIMILARITY_THRESHOLD = RagIndexingUiState.MIN_SIMILARITY_THRESHOLD
        const val MAX_SIMILARITY_THRESHOLD = RagIndexingUiState.MAX_SIMILARITY_THRESHOLD
    }
}

private object UnconfiguredLlmAgent : LlmAgent {
    override suspend fun sendMessage(
        messages: List<AgentMessage>,
        systemInstruction: String?,
        generationConfig: GeminiGenerationConfig?,
        modelName: String?,
        totalTokenLimit: Int?,
    ): GeminiResult<String> = GeminiResult.Failure(GeminiNetworkError.MissingApiKey)
}

private fun MutableStateFlow<RagIndexingUiState>.updateProgress(
    phase: RagIndexingPhase,
    currentStrategy: RagIndexingStrategy? = null,
    total: Int,
    embedded: Int,
    cachedCount: Int,
    outputPaths: RagIndexingOutputPaths,
) {
    update { current ->
        current.copy(
            phase = phase,
            progress =
                RagIndexingProgress(
                    currentStrategy = currentStrategy,
                    embedded = embedded,
                    total = total,
                    cachedCount = cachedCount,
                    outputPaths = outputPaths,
                ),
        )
    }
}

private fun RagCorpusDocumentInfo.toUi(): RagCorpusDocumentUi =
    RagCorpusDocumentUi(
        id = id,
        title = title,
        source = source,
        wordCount = wordCount,
        selectedByDefault = selectedByDefault,
    )

private fun RagIndexingUiState.resetAgentOutputs(): RagIndexingUiState =
    copy(
        baselineRagContextResults = emptyList(),
        improvedRagCandidateResults = emptyList(),
        improvedRagContextResults = emptyList(),
        baselineRetrievalStats = null,
        improvedRetrievalStats = null,
        rewrittenQuery = null,
        queryRewriteNote = null,
        noRagAnswerState = ResponsePaneState.Empty("No-RAG answer will appear after compare."),
        baselineRagAnswerState = ResponsePaneState.Empty("Baseline RAG answer will appear after compare."),
        improvedRagAnswerState = ResponsePaneState.Empty("Improved RAG answer will appear after compare."),
        qualityEvaluationState = ResponsePaneState.Empty("Quality comparison will appear after all answers finish."),
    )

private fun RagIndexingUiState.resetEvaluationOutput(): RagIndexingUiState =
    copy(
        qualityEvaluationState = ResponsePaneState.Empty("Quality comparison will appear after all answers finish."),
    )

private fun RagIndexingUiState.retrievalSettings(): RagRetrievalSettings =
    RagRetrievalSettings(
        topKBeforeFilter = topKBeforeFilter.coerceIn(RagIndexingUiState.MIN_TOP_K, RagIndexingUiState.MAX_TOP_K),
        topKAfterFilter =
            topKAfterFilter.coerceIn(
                RagIndexingUiState.MIN_TOP_K,
                topKBeforeFilter.coerceIn(RagIndexingUiState.MIN_TOP_K, RagIndexingUiState.MAX_TOP_K),
            ),
        similarityThreshold =
            similarityThreshold.coerceIn(
                RagIndexingUiState.MIN_SIMILARITY_THRESHOLD,
                RagIndexingUiState.MAX_SIMILARITY_THRESHOLD,
            ),
    )

private fun RagFilteredSearchResult.toStatsUi(
    settings: RagRetrievalSettings,
    similarityThreshold: Double?,
): RagRetrievalStatsUi =
    RagRetrievalStatsUi(
        candidateCount = candidates.size,
        filteredCount = filtered.size,
        usedCount = selected.size,
        topKBeforeFilter = settings.topKBeforeFilter,
        topKAfterFilter = settings.topKAfterFilter,
        similarityThreshold = similarityThreshold,
    )

private fun String.normalizedRewriteOrNull(): String? =
    trim()
        .removeSurrounding("```")
        .trim()
        .removePrefix("Rewritten query:")
        .removePrefix("Переписанный запрос:")
        .trim()
        .trim('"')
        .trim()
        .takeIf { value -> value.isNotBlank() }

private fun GeminiResult<String>.toPaneState(): ResponsePaneState =
    when (this) {
        is GeminiResult.Success -> ResponsePaneState.Success(value)
        is GeminiResult.Failure -> ResponsePaneState.Error(error.userMessage)
    }

private fun GeminiResult<String>.successValue(): String? =
    when (this) {
        is GeminiResult.Success -> value
        is GeminiResult.Failure -> null
    }

private fun RagChunk.cacheKey(model: String): String =
    RagEmbeddingCacheKeys.key(
        strategy = strategy,
        model = model.trim(),
        sourceHash = sourceHash,
        chunkTextHash = RagEmbeddingCacheKeys.chunkTextHash(text),
    )

private fun RagIndex.summary(
    strategy: RagIndexingStrategy,
    outputPath: String,
): RagIndexSummary =
    RagIndexSummary(
        strategy = strategy,
        chunkCount = chunks.size,
        embeddingCount = embeddings.size,
        documentCount = metadata["document_count"]?.toIntOrNull() ?: chunks.map { chunk -> chunk.documentId }.distinct().size,
        sourceHash = sourceHash,
        outputPath = outputPath,
    )

private fun RagIndexingStrategy.outputPathFrom(paths: RagIndexingOutputPaths): String =
    when (this) {
        RagIndexingStrategy.FIXED -> paths.fixedIndex
        RagIndexingStrategy.STRUCTURE -> paths.structureIndex
    }.orEmpty()

private fun RagIndex.comparisonStats(strategy: RagIndexingStrategy): RagComparisonStrategyStats {
    val tokenCounts = chunks.map { chunk -> chunk.tokenCount }
    return RagComparisonStrategyStats(
        strategy = strategy.directoryName,
        chunkCount = chunks.size,
        embeddingCount = embeddings.size,
        averageTokens = if (tokenCounts.isEmpty()) 0.0 else tokenCounts.sum().toDouble() / tokenCounts.size,
        minTokens = tokenCounts.minOrNull() ?: 0,
        maxTokens = tokenCounts.maxOrNull() ?: 0,
    )
}

private fun RagIndex.comparisonRetrieval(
    queryEmbedding: List<Double>,
    settings: RagRetrievalSettings,
): RagComparisonRetrievalReport {
    val baseline =
        RagSearch.search(
            index = this,
            queryEmbedding = queryEmbedding,
            topK = settings.topKAfterFilter,
        )
    val improved =
        RagSearch.searchWithFilter(
            index = this,
            queryEmbedding = queryEmbedding,
            topKBeforeFilter = settings.topKBeforeFilter,
            topKAfterFilter = settings.topKAfterFilter,
            similarityThreshold = settings.similarityThreshold,
        )

    return RagComparisonRetrievalReport(
        baselineHits = baseline.toComparisonHits(),
        improvedCandidates = improved.candidates.toComparisonHits(),
        filteredHits = improved.selected.toComparisonHits(),
    )
}

private fun List<RagSearchResult>.toComparisonHits(): List<RagComparisonHit> =
    map { result ->
        RagComparisonHit(
            chunkId = result.chunk.chunkId,
            score = result.score,
            title = result.chunk.title,
            section = result.chunk.metadata["section_heading"],
            source = result.chunk.source,
            preview = result.chunk.preview(),
        )
    }

private fun RagSearchResult.toUi(): RagSearchResultUi =
    RagSearchResultUi(
        chunkId = chunk.chunkId,
        score = score,
        title = chunk.title,
        section = chunk.metadata["section_heading"],
        source = chunk.source,
        preview = chunk.preview(),
    )

private fun RagChunk.preview(): String =
    text
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(220)

private fun RagComparisonReport.toSummary(paths: RagComparisonOutputPaths): RagComparisonSummary {
    val fixed = strategies.single { stats -> stats.strategy == RagIndexingStrategy.FIXED.directoryName }
    val structure = strategies.single { stats -> stats.strategy == RagIndexingStrategy.STRUCTURE.directoryName }
    return RagComparisonSummary(
        queryCount = queries.size,
        fixedChunkCount = fixed.chunkCount,
        structureChunkCount = structure.chunkCount,
        fixedAverageTokens = fixed.averageTokens,
        structureAverageTokens = structure.averageTokens,
        jsonPath = paths.jsonPath,
        markdownPath = paths.markdownPath,
    )
}

private fun RagComparisonReport.toMarkdown(): String =
    buildString {
        appendLine("# RAG Strategy Comparison")
        appendLine()
        appendLine("- Schema: `$schemaVersion`")
        appendLine("- Model: `$model`")
        appendLine(
            "- Retrieval: top_k_before_filter=${settings.topKBeforeFilter}, " +
                "top_k_after_filter=${settings.topKAfterFilter}, " +
                "similarity_threshold=${"%.2f".format(settings.similarityThreshold)}",
        )
        strategies.forEach { stats ->
            appendLine(
                "- ${stats.strategy}: chunks=${stats.chunkCount}, embeddings=${stats.embeddingCount}, " +
                    "avg_tokens=${"%.1f".format(stats.averageTokens)}, min=${stats.minTokens}, max=${stats.maxTokens}",
            )
        }
        appendLine()
        appendLine("## Retrieval Examples")
        queries.forEach { query ->
            appendLine()
            appendLine("### ${query.originalQuery}")
            query.rewrittenQuery?.takeIf { value -> value.isNotBlank() }?.let { rewritten ->
                appendLine()
                appendLine("- Rewritten query: `$rewritten`")
            }
            appendLine()
            appendLine("Fixed:")
            appendMarkdown(query.fixed)
            appendLine()
            appendLine("Structure:")
            appendMarkdown(query.structure)
        }
    }

private fun StringBuilder.appendMarkdown(report: RagComparisonRetrievalReport) {
    appendLine("- baseline_hits=${report.baselineHits.size}")
    report.baselineHits.forEach { hit ->
        appendLine(hit.toMarkdownLine())
    }
    appendLine("- improved_candidates=${report.improvedCandidates.size}")
    report.improvedCandidates.forEach { hit ->
        appendLine(hit.toMarkdownLine())
    }
    appendLine("- filtered_hits=${report.filteredHits.size}")
    report.filteredHits.forEach { hit ->
        appendLine(hit.toMarkdownLine())
    }
}

private fun RagComparisonHit.toMarkdownLine(): String {
    val section = section?.let { value -> " / $value" }.orEmpty()
    return "  - ${"%.4f".format(score)} `$chunkId` $title$section - $preview"
}
