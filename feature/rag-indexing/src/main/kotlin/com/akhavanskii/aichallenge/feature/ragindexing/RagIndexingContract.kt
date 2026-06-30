package com.akhavanskii.aichallenge.feature.ragindexing

data class RagIndexingUiState(
    val endpoint: String = OllamaEmbeddingClient.DEFAULT_ENDPOINT,
    val model: String = OllamaEmbeddingClient.DEFAULT_MODEL,
    val selectedStrategy: RagIndexingStrategy = RagIndexingStrategy.FIXED,
    val query: String = "",
    val topK: Int = DEFAULT_TOP_K,
    val phase: RagIndexingPhase = RagIndexingPhase.IDLE,
    val progress: RagIndexingProgress = RagIndexingProgress(),
    val indexSummaries: List<RagIndexSummary> = emptyList(),
    val comparisonSummary: RagComparisonSummary? = null,
    val comparisonReport: RagComparisonReport? = null,
    val searchResults: List<RagSearchResultUi> = emptyList(),
    val userFacingError: String? = null,
) {
    val isBusy: Boolean
        get() =
            phase == RagIndexingPhase.BUILDING ||
                phase == RagIndexingPhase.COMPARING ||
                phase == RagIndexingPhase.SEARCHING

    companion object {
        const val DEFAULT_TOP_K = 5
    }
}

enum class RagIndexingPhase {
    IDLE,
    BUILDING,
    COMPARING,
    SEARCHING,
    SUCCESS,
    ERROR,
    CANCELLED,
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

    data class QueryChanged(
        val query: String,
    ) : RagIndexingAction

    data class TopKChanged(
        val topK: Int,
    ) : RagIndexingAction

    data object BuildIndex : RagIndexingAction

    data object CompareStrategies : RagIndexingAction

    data object Search : RagIndexingAction

    data object Cancel : RagIndexingAction
}

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

data class RagComparisonSummary(
    val queryCount: Int,
    val fixedChunkCount: Int,
    val structureChunkCount: Int,
    val fixedAverageTokens: Double,
    val structureAverageTokens: Double,
    val jsonPath: String,
    val markdownPath: String,
)
