package com.akhavanskii.aichallenge.feature.ragindexing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RagComparisonReport(
    @SerialName("schema_version")
    val schemaVersion: Int = 2,
    val model: String,
    val settings: RagRetrievalSettings,
    val strategies: List<RagComparisonStrategyStats>,
    val queries: List<RagComparisonQueryReport>,
)

@Serializable
data class RagRetrievalSettings(
    @SerialName("top_k_before_filter")
    val topKBeforeFilter: Int,
    @SerialName("top_k_after_filter")
    val topKAfterFilter: Int,
    @SerialName("similarity_threshold")
    val similarityThreshold: Double,
)

@Serializable
data class RagComparisonStrategyStats(
    val strategy: String,
    @SerialName("chunk_count")
    val chunkCount: Int,
    @SerialName("embedding_count")
    val embeddingCount: Int,
    @SerialName("average_tokens")
    val averageTokens: Double,
    @SerialName("min_tokens")
    val minTokens: Int,
    @SerialName("max_tokens")
    val maxTokens: Int,
)

@Serializable
data class RagComparisonQueryReport(
    @SerialName("original_query")
    val originalQuery: String,
    @SerialName("rewritten_query")
    val rewrittenQuery: String? = null,
    val fixed: RagComparisonRetrievalReport,
    val structure: RagComparisonRetrievalReport,
)

@Serializable
data class RagComparisonRetrievalReport(
    @SerialName("baseline_hits")
    val baselineHits: List<RagComparisonHit>,
    @SerialName("improved_candidates")
    val improvedCandidates: List<RagComparisonHit>,
    @SerialName("filtered_hits")
    val filteredHits: List<RagComparisonHit>,
)

@Serializable
data class RagComparisonHit(
    @SerialName("chunk_id")
    val chunkId: String,
    val score: Double,
    val title: String,
    val section: String? = null,
    val source: String,
    val preview: String,
)
