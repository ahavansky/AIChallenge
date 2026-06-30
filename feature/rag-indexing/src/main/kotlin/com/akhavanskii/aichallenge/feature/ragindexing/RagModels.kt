package com.akhavanskii.aichallenge.feature.ragindexing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RagDocument(
    val id: String,
    val title: String,
    val source: String,
    val text: String,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank()) { "RagDocument.id must not be blank." }
        require(title.isNotBlank()) { "RagDocument.title must not be blank." }
        require(source.isNotBlank()) { "RagDocument.source must not be blank." }
    }
}

@Serializable
data class RagChunk(
    @SerialName("chunk_id")
    val chunkId: String,
    @SerialName("document_id")
    val documentId: String,
    val title: String,
    val source: String,
    @SerialName("source_hash")
    val sourceHash: String,
    val text: String,
    @SerialName("token_start")
    val tokenStart: Int,
    @SerialName("token_end")
    val tokenEnd: Int,
    @SerialName("token_count")
    val tokenCount: Int,
    val strategy: String,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class RagEmbeddingCacheEntry(
    @SerialName("cache_key")
    val cacheKey: String,
    @SerialName("chunk_id")
    val chunkId: String,
    val model: String,
    val strategy: String,
    @SerialName("source_hash")
    val sourceHash: String,
    @SerialName("chunk_text_hash")
    val chunkTextHash: String,
    val embedding: List<Double>,
    val metadata: Map<String, String> = emptyMap(),
) {
    companion object {
        fun from(
            chunk: RagChunk,
            model: String,
            embedding: List<Double>,
        ): RagEmbeddingCacheEntry {
            val chunkTextHash = RagEmbeddingCacheKeys.chunkTextHash(chunk.text)

            return RagEmbeddingCacheEntry(
                cacheKey =
                    RagEmbeddingCacheKeys.key(
                        strategy = chunk.strategy,
                        model = model,
                        sourceHash = chunk.sourceHash,
                        chunkTextHash = chunkTextHash,
                    ),
                chunkId = chunk.chunkId,
                model = model,
                strategy = chunk.strategy,
                sourceHash = chunk.sourceHash,
                chunkTextHash = chunkTextHash,
                embedding = embedding,
                metadata = chunk.metadata,
            )
        }
    }
}

@Serializable
data class RagIndex(
    @SerialName("schema_version")
    val schemaVersion: Int = 1,
    val model: String,
    val strategy: String,
    @SerialName("source_hash")
    val sourceHash: String,
    val chunks: List<RagChunk>,
    val embeddings: List<RagEmbeddingCacheEntry> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    @SerialName("chunk_count")
    val chunkCount: Int = chunks.size,
)

sealed interface ChunkingStrategy {
    val maxTokens: Int
    val overlapTokens: Int
    val key: String

    data class Fixed(
        override val maxTokens: Int = DEFAULT_MAX_TOKENS,
        override val overlapTokens: Int = DEFAULT_OVERLAP_TOKENS,
    ) : ChunkingStrategy {
        init {
            validateTokenWindow(maxTokens, overlapTokens)
        }

        override val key: String = "fixed(tokens=$maxTokens,overlap=$overlapTokens)"
    }

    data class Structure(
        override val maxTokens: Int = DEFAULT_MAX_TOKENS,
        override val overlapTokens: Int = DEFAULT_OVERLAP_TOKENS,
    ) : ChunkingStrategy {
        init {
            validateTokenWindow(maxTokens, overlapTokens)
        }

        override val key: String = "structure(tokens=$maxTokens,overlap=$overlapTokens)"
    }

    companion object {
        const val DEFAULT_MAX_TOKENS = 700
        const val DEFAULT_OVERLAP_TOKENS = 100
    }
}

data class RagSearchResult(
    val chunk: RagChunk,
    val score: Double,
)

private fun validateTokenWindow(
    maxTokens: Int,
    overlapTokens: Int,
) {
    require(maxTokens > 0) { "maxTokens must be positive." }
    require(overlapTokens >= 0) { "overlapTokens must not be negative." }
    require(overlapTokens < maxTokens) { "overlapTokens must be smaller than maxTokens." }
}
