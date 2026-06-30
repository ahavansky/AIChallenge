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
