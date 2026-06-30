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
            is RagIndexingAction.TopKChanged ->
                updateWhenIdle {
                    it.copy(
                        topK = action.topK.coerceIn(MIN_TOP_K, MAX_TOP_K),
                        userFacingError = null,
                    )
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
            val topK = request.topK.coerceIn(MIN_TOP_K, MAX_TOP_K)
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
                            topK = topK,
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
            val results =
                withContext(ioDispatcher) {
                    searchIndex(
                        endpoint = endpoint,
                        model = model,
                        strategy = request.selectedStrategy,
                        query = request.query,
                        topK = request.topK,
                        documentIds = documentIds,
                    )
                }

            mutableUiState.update { current ->
                current.copy(
                    phase = RagIndexingPhase.SUCCESS,
                    searchResults = results,
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
            val topK = request.topK.coerceIn(MIN_TOP_K, MAX_TOP_K)

            mutableUiState.update { current ->
                current.copy(
                    noRagAnswerState = ResponsePaneState.Loading,
                    ragAnswerState = ResponsePaneState.Loading,
                    qualityEvaluationState = ResponsePaneState.Empty("Waiting for both answers before quality comparison."),
                    ragContextResults = emptyList(),
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

            val ragRun =
                runCatching {
                    runRagAnswer(
                        endpoint = endpoint,
                        embeddingModel = embeddingModel,
                        llmModelName = llmModelName,
                        strategy = strategy,
                        question = question,
                        topK = topK,
                        documentIds = documentIds,
                    )
                }.getOrElse { throwable ->
                    mutableUiState.update { current ->
                        current.copy(
                            ragAnswerState = ResponsePaneState.Error(throwable.toUserFacingMessage()),
                        )
                    }
                    throw throwable
                }
            mutableUiState.update { current ->
                current.copy(
                    ragAnswerState = ragRun.answer.toPaneState(),
                    ragContextResults = ragRun.retrieved.map { result -> result.toUi() },
                )
            }

            val noRagAnswer = noRagResult.successValue()
            val ragAnswer = ragRun.answer.successValue()
            if (noRagAnswer == null || ragAnswer == null) {
                mutableUiState.update { current ->
                    current.copy(
                        phase = RagIndexingPhase.SUCCESS,
                        qualityEvaluationState =
                            ResponsePaneState.Error(
                                "Quality comparison skipped: both modes must return successful answers.",
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
                    expectedAnswer = request.expectedAnswer,
                    expectedSources = request.expectedSources,
                    retrievedResults = ragRun.retrieved,
                    noRagAnswer = noRagAnswer,
                    ragAnswer = ragAnswer,
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
        topK: Int,
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
                inputs = listOf(question),
            ).single()
        val retrieved =
            RagSearch.search(
                index = index,
                queryEmbedding = queryEmbedding,
                topK = topK,
            )
        val prompt =
            RagAgentPrompts.buildRagPrompt(
                question = question,
                results = retrieved,
            )
        val answer =
            llmAgent.sendMessage(
                prompt = prompt,
                generationConfig = null,
                modelName = llmModelName,
            )

        return RagAnswerRun(
            answer = answer,
            retrieved = retrieved,
        )
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
        topK: Int,
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
            strategies =
                listOf(
                    indexes.getValue(RagIndexingStrategy.FIXED).comparisonStats(RagIndexingStrategy.FIXED),
                    indexes.getValue(RagIndexingStrategy.STRUCTURE).comparisonStats(RagIndexingStrategy.STRUCTURE),
                ),
            queries =
                listOf(
                    RagComparisonQueryReport(
                        query = query,
                        fixed = indexes.getValue(RagIndexingStrategy.FIXED).comparisonHits(queryEmbedding, topK),
                        structure = indexes.getValue(RagIndexingStrategy.STRUCTURE).comparisonHits(queryEmbedding, topK),
                    ),
                ),
        )
    }

    private suspend fun searchIndex(
        endpoint: String,
        model: String,
        strategy: RagIndexingStrategy,
        query: String,
        topK: Int,
        documentIds: Set<String>,
    ): List<RagSearchResultUi> {
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

        return RagSearch
            .search(
                index = index,
                queryEmbedding = queryEmbedding,
                topK = topK,
            ).map { result -> result.toUi() }
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

    private data class RagAnswerRun(
        val answer: GeminiResult<String>,
        val retrieved: List<RagSearchResult>,
    )

    private class EmbeddingRagException(
        val error: EmbeddingError,
    ) : RuntimeException(error.userMessage())

    private class UserFacingRagException(
        override val message: String,
    ) : RuntimeException(message)

    private companion object {
        const val DEFAULT_EMBEDDING_BATCH_SIZE = 8
        const val MIN_TOP_K = 1
        const val MAX_TOP_K = 20
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
        ragContextResults = emptyList(),
        noRagAnswerState = ResponsePaneState.Empty("No-RAG answer will appear after compare."),
        ragAnswerState = ResponsePaneState.Empty("RAG answer will appear after compare."),
        qualityEvaluationState = ResponsePaneState.Empty("Quality comparison will appear after both answers finish."),
    )

private fun RagIndexingUiState.resetEvaluationOutput(): RagIndexingUiState =
    copy(
        qualityEvaluationState = ResponsePaneState.Empty("Quality comparison will appear after both answers finish."),
    )

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

private fun RagIndex.comparisonHits(
    queryEmbedding: List<Double>,
    topK: Int,
): List<RagComparisonHit> =
    RagSearch
        .search(
            index = this,
            queryEmbedding = queryEmbedding,
            topK = topK,
        ).map { result ->
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
        appendLine("- Model: `$model`")
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
            appendLine("### ${query.query}")
            appendLine()
            appendLine("Fixed:")
            query.fixed.forEach { hit ->
                appendLine("- ${"%.4f".format(hit.score)} `${hit.chunkId}` ${hit.title} - ${hit.preview}")
            }
            appendLine()
            appendLine("Structure:")
            query.structure.forEach { hit ->
                val section = hit.section?.let { value -> " / $value" }.orEmpty()
                appendLine("- ${"%.4f".format(hit.score)} `${hit.chunkId}` ${hit.title}$section - ${hit.preview}")
            }
        }
    }
