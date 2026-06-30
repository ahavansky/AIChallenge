package com.akhavanskii.aichallenge.feature.ragindexing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RagComparisonReport(
    @SerialName("schema_version")
    val schemaVersion: Int = 1,
    val model: String,
    val strategies: List<RagComparisonStrategyStats>,
    val queries: List<RagComparisonQueryReport>,
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
    val query: String,
    val fixed: List<RagComparisonHit>,
    val structure: List<RagComparisonHit>,
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
