package com.akhavanskii.aichallenge.feature.ragindexing

import com.akhavanskii.aichallenge.feature.common.ResponsePaneState

data class RagIndexingUiState(
    val endpoint: String = OllamaEmbeddingClient.DEFAULT_ENDPOINT,
    val model: String = OllamaEmbeddingClient.DEFAULT_MODEL,
    val corpusDocuments: List<RagCorpusDocumentUi> = emptyList(),
    val selectedCorpusDocumentIds: Set<String> = emptySet(),
    val selectedStrategy: RagIndexingStrategy = RagIndexingStrategy.FIXED,
    val selectedLlmModel: RagLlmModelOption = RagLlmModelOption.DEFAULT,
    val query: String = "",
    val expectedAnswer: String = "",
    val expectedSources: String = "",
    val topKBeforeFilter: Int = DEFAULT_TOP_K_BEFORE_FILTER,
    val topKAfterFilter: Int = DEFAULT_TOP_K_AFTER_FILTER,
    val similarityThreshold: Double = DEFAULT_SIMILARITY_THRESHOLD,
    val phase: RagIndexingPhase = RagIndexingPhase.IDLE,
    val progress: RagIndexingProgress = RagIndexingProgress(),
    val indexSummaries: List<RagIndexSummary> = emptyList(),
    val comparisonSummary: RagComparisonSummary? = null,
    val comparisonReport: RagComparisonReport? = null,
    val searchResults: List<RagSearchResultUi> = emptyList(),
    val searchRetrievalStats: RagRetrievalStatsUi? = null,
    val baselineRagContextResults: List<RagSearchResultUi> = emptyList(),
    val improvedRagCandidateResults: List<RagSearchResultUi> = emptyList(),
    val improvedRagContextResults: List<RagSearchResultUi> = emptyList(),
    val baselineRetrievalStats: RagRetrievalStatsUi? = null,
    val improvedRetrievalStats: RagRetrievalStatsUi? = null,
    val rewrittenQuery: String? = null,
    val queryRewriteNote: String? = null,
    val noRagAnswerState: ResponsePaneState =
        ResponsePaneState.Empty("No-RAG answer will appear after compare."),
    val baselineRagAnswerState: ResponsePaneState =
        ResponsePaneState.Empty("Baseline RAG answer will appear after compare."),
    val improvedRagAnswerState: ResponsePaneState =
        ResponsePaneState.Empty("Improved RAG answer will appear after compare."),
    val qualityEvaluationState: ResponsePaneState =
        ResponsePaneState.Empty("Quality comparison will appear after all answers finish."),
    val userFacingError: String? = null,
) {
    val isBusy: Boolean
        get() =
            phase == RagIndexingPhase.LOADING_CORPUS ||
                phase == RagIndexingPhase.BUILDING ||
                phase == RagIndexingPhase.COMPARING ||
                phase == RagIndexingPhase.SEARCHING ||
                phase == RagIndexingPhase.ANSWERING ||
                phase == RagIndexingPhase.EVALUATING

    companion object {
        const val DEFAULT_TOP_K_BEFORE_FILTER = 20
        const val DEFAULT_TOP_K_AFTER_FILTER = 5
        const val DEFAULT_SIMILARITY_THRESHOLD = 0.35
        const val MIN_TOP_K = 1
        const val MAX_TOP_K = 20
        const val MIN_SIMILARITY_THRESHOLD = 0.0
        const val MAX_SIMILARITY_THRESHOLD = 1.0
    }
}

enum class RagIndexingPhase {
    IDLE,
    LOADING_CORPUS,
    BUILDING,
    COMPARING,
    SEARCHING,
    ANSWERING,
    EVALUATING,
    SUCCESS,
    ERROR,
    CANCELLED,
}

enum class RagLlmProvider(
    val displayName: String,
) {
    GEMINI(displayName = "Gemini API"),
    DEEPSEEK(displayName = "DeepSeek API"),
}

enum class RagLlmModelOption(
    val provider: RagLlmProvider,
    val modelName: String,
    val title: String,
    val description: String,
) {
    GEMINI_3_5_FLASH(
        provider = RagLlmProvider.GEMINI,
        modelName = "gemini-3.5-flash",
        title = "Gemini 3.5 Flash",
        description = "Default project Gemini model for fast generation and evaluation.",
    ),
    GEMINI_2_5_FLASH(
        provider = RagLlmProvider.GEMINI,
        modelName = "gemini-2.5-flash",
        title = "Gemini 2.5 Flash",
        description = "Balanced Gemini model for everyday RAG answers.",
    ),
    GEMINI_2_5_FLASH_LITE(
        provider = RagLlmProvider.GEMINI,
        modelName = "gemini-2.5-flash-lite",
        title = "Gemini 2.5 Flash-Lite",
        description = "Lower-latency Gemini model for quick RAG iterations.",
    ),
    GEMMA_4_31B_IT(
        provider = RagLlmProvider.GEMINI,
        modelName = "gemma-4-31b-it",
        title = "Gemma 4 31B IT",
        description = "Gemma open model served through the Gemini-compatible transport.",
    ),
    DEEPSEEK_V4_FLASH(
        provider = RagLlmProvider.DEEPSEEK,
        modelName = "deepseek-v4-flash",
        title = "DeepSeek V4 Flash",
        description = "Lower-latency DeepSeek model for iterative RAG comparisons.",
    ),
    DEEPSEEK_V4_PRO(
        provider = RagLlmProvider.DEEPSEEK,
        modelName = "deepseek-v4-pro",
        title = "DeepSeek V4 Pro",
        description = "Higher-capability DeepSeek model for harder RAG questions.",
    ),
    ;

    companion object {
        val DEFAULT: RagLlmModelOption = GEMINI_2_5_FLASH
    }
}

enum class RagIndexingStrategy(
    val directoryName: String,
    val displayName: String,
) {
    FIXED(directoryName = "fixed", displayName = "Fixed"),
    STRUCTURE(directoryName = "structure", displayName = "Structure"),
    ;

    val chunkingStrategy: ChunkingStrategy
        get() =
            when (this) {
                FIXED -> ChunkingStrategy.Fixed()
                STRUCTURE -> ChunkingStrategy.Structure()
            }
}

sealed interface RagIndexingAction {
    data class EndpointChanged(
        val endpoint: String,
    ) : RagIndexingAction

    data class ModelChanged(
        val model: String,
    ) : RagIndexingAction

    data class StrategyChanged(
        val strategy: RagIndexingStrategy,
    ) : RagIndexingAction

    data class LlmModelChanged(
        val model: RagLlmModelOption,
    ) : RagIndexingAction

    data class CorpusDocumentToggled(
        val documentId: String,
        val selected: Boolean,
    ) : RagIndexingAction

    data class QueryChanged(
        val query: String,
    ) : RagIndexingAction

    data class ExpectedAnswerChanged(
        val expectedAnswer: String,
    ) : RagIndexingAction

    data class ExpectedSourcesChanged(
        val expectedSources: String,
    ) : RagIndexingAction

    data class TopKBeforeFilterChanged(
        val topK: Int,
    ) : RagIndexingAction

    data class TopKAfterFilterChanged(
        val topK: Int,
    ) : RagIndexingAction

    data class SimilarityThresholdChanged(
        val threshold: Double,
    ) : RagIndexingAction

    data object BuildIndex : RagIndexingAction

    data object CompareStrategies : RagIndexingAction

    data object Search : RagIndexingAction

    data object CompareModes : RagIndexingAction

    data object Cancel : RagIndexingAction
}

data class RagCorpusDocumentUi(
    val id: String,
    val title: String,
    val source: String,
    val wordCount: Int,
    val selectedByDefault: Boolean,
)

data class RagIndexingProgress(
    val currentStrategy: RagIndexingStrategy? = null,
    val embedded: Int = 0,
    val total: Int = 0,
    val cachedCount: Int = 0,
    val outputPaths: RagIndexingOutputPaths = RagIndexingOutputPaths(),
)

data class RagIndexingOutputPaths(
    val fixedIndex: String? = null,
    val structureIndex: String? = null,
    val embeddingCache: String? = null,
    val comparisonJson: String? = null,
    val comparisonMarkdown: String? = null,
)

data class RagIndexSummary(
    val strategy: RagIndexingStrategy,
    val chunkCount: Int,
    val embeddingCount: Int,
    val documentCount: Int,
    val sourceHash: String,
    val outputPath: String,
)

data class RagSearchResultUi(
    val chunkId: String,
    val score: Double,
    val title: String,
    val section: String?,
    val source: String,
    val preview: String,
)

data class RagRetrievalStatsUi(
    val candidateCount: Int,
    val filteredCount: Int,
    val usedCount: Int,
    val topKBeforeFilter: Int,
    val topKAfterFilter: Int,
    val similarityThreshold: Double?,
)

data class RagComparisonSummary(
    val queryCount: Int,
    val fixedChunkCount: Int,
    val structureChunkCount: Int,
    val fixedAverageTokens: Double,
    val structureAverageTokens: Double,
    val jsonPath: String,
    val markdownPath: String,
)
