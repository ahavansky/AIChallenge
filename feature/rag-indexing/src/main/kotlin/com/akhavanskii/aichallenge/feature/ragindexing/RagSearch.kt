package com.akhavanskii.aichallenge.feature.ragindexing

import kotlin.math.sqrt

object RagSearch {
    fun search(
        index: RagIndex,
        queryEmbedding: List<Double>,
        topK: Int,
    ): List<RagSearchResult> {
        if (topK <= 0) {
            return emptyList()
        }

        val chunksById = index.chunks.associateBy { chunk -> chunk.chunkId }

        return index.embeddings
            .mapNotNull { entry ->
                val chunk = chunksById[entry.chunkId] ?: return@mapNotNull null
                RagSearchResult(
                    chunk = chunk,
                    score = cosineSimilarity(queryEmbedding, entry.embedding),
                )
            }.sortedWith(
                compareByDescending<RagSearchResult> { result -> result.score }
                    .thenBy { result -> result.chunk.chunkId },
            ).take(topK)
    }

    fun searchWithFilter(
        index: RagIndex,
        queryEmbedding: List<Double>,
        topKBeforeFilter: Int,
        topKAfterFilter: Int,
        similarityThreshold: Double,
    ): RagFilteredSearchResult {
        val candidates =
            search(
                index = index,
                queryEmbedding = queryEmbedding,
                topK = topKBeforeFilter,
            )
        val filtered = candidates.filter { result -> result.score >= similarityThreshold }

        return RagFilteredSearchResult(
            candidates = candidates,
            filtered = filtered,
            selected = filtered.take(topKAfterFilter.coerceAtLeast(0)),
        )
    }

    fun cosineSimilarity(
        left: List<Double>,
        right: List<Double>,
    ): Double {
        require(left.isNotEmpty()) { "Embedding must not be empty." }
        require(left.size == right.size) { "Embedding dimensions must match." }

        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0

        left.indices.forEach { index ->
            dot += left[index] * right[index]
            leftNorm += left[index] * left[index]
            rightNorm += right[index] * right[index]
        }

        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0
        }

        return dot / (sqrt(leftNorm) * sqrt(rightNorm))
    }
}

data class RagFilteredSearchResult(
    val candidates: List<RagSearchResult>,
    val filtered: List<RagSearchResult>,
    val selected: List<RagSearchResult>,
)
