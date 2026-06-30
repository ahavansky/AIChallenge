package com.akhavanskii.aichallenge.feature.ragindexing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagIndexerTest {
    private val indexer = RagIndexer()

    @Test
    fun fixedChunkingAppliesSizeCapOverlapAndStableIds() {
        val document =
            RagDocument(
                id = "fixed",
                title = "Fixed",
                source = "fixed.md",
                text = tokens(count = 1_500, prefix = "fixed"),
            )
        val strategy = ChunkingStrategy.Fixed()

        val chunks = indexer.chunk(document, strategy)
        val repeatedChunks = indexer.chunk(document, strategy)

        assertEquals(listOf(700, 700, 300), chunks.map { chunk -> chunk.tokenCount })
        assertTrue(chunks.all { chunk -> chunk.tokenCount <= 700 })
        assertEquals(
            chunks
                .first()
                .text
                .toTokens()
                .takeLast(100),
            chunks[1]
                .text
                .toTokens()
                .take(100),
        )
        assertEquals(
            chunks.map { chunk -> chunk.chunkId },
            repeatedChunks.map { chunk -> chunk.chunkId },
        )
    }

    @Test
    fun structureChunkingKeepsHeadingsAndSplitsOversizedSections() {
        val document =
            RagDocument(
                id = "structure",
                title = "Structure",
                source = "structure.md",
                text =
                    """
                    # Intro
                    Short intro.

                    ## Install
                    ${tokens(count = 350, prefix = "installA")}

                    ${tokens(count = 360, prefix = "installB")}

                    ## Long Paragraph
                    ${tokens(count = 760, prefix = "long")}
                    """.trimIndent(),
            )

        val chunks = indexer.chunk(document, ChunkingStrategy.Structure())

        assertTrue(chunks.all { chunk -> chunk.tokenCount <= 700 })
        assertTrue(
            chunks.any { chunk ->
                chunk.metadata["section_heading"] == "Intro" &&
                    chunk.metadata["split_method"] == "section"
            },
        )
        assertTrue(
            chunks.any { chunk ->
                chunk.metadata["section_heading"] == "Install" &&
                    chunk.metadata["split_method"] == "paragraph"
            },
        )
        assertTrue(
            chunks.any { chunk ->
                chunk.metadata["section_heading"] == "Long Paragraph" &&
                    chunk.metadata["split_method"] == "fixed"
            },
        )
    }

    @Test
    fun structureChunkingTreatsBookChapterLinesAsHeadings() {
        val document =
            RagDocument(
                id = "moby",
                title = "Moby-Dick",
                source = "moby-dick.md",
                text =
                    """
                    # Moby-Dick
                    Intro note for the book.

                    CHAPTER 1. Loomings.
                    Call me Ishmael. Some years ago never mind how long precisely.

                    CHAPTER 2. The Carpet-Bag.
                    I stuffed a shirt or two into my old carpet-bag.
                    """.trimIndent(),
            )

        val chunks =
            indexer.chunk(
                document = document,
                strategy = ChunkingStrategy.Structure(maxTokens = 80, overlapTokens = 0),
            )

        assertTrue(
            chunks.any { chunk ->
                chunk.metadata["section_heading"] == "CHAPTER 1. Loomings." &&
                    chunk.metadata["heading_level"] == "2"
            },
        )
        assertTrue(
            chunks.any { chunk ->
                chunk.metadata["section_heading"] == "CHAPTER 2. The Carpet-Bag."
            },
        )
    }

    @Test
    fun jsonSchemaSerializesRequiredMetadataAndEmbeddings() {
        val document =
            RagDocument(
                id = "json",
                title = "Json",
                source = "json.md",
                text = "alpha beta gamma delta",
                metadata = mapOf("kind" to "test"),
            )
        val strategy = ChunkingStrategy.Fixed(maxTokens = 10, overlapTokens = 0)
        val chunks = indexer.chunk(document, strategy)
        val index =
            indexer.buildIndex(
                documents = listOf(document),
                strategy = strategy,
                model = "test-embedding",
                embeddingsByChunkId =
                    chunks.associate { chunk ->
                        chunk.chunkId to listOf(0.25, 0.75)
                    },
                metadata = mapOf("created_for" to "unit-test"),
            )

        val encoded = RagIndexJson.encode(index)
        val decoded = RagIndexJson.decode(encoded)

        assertTrue(encoded.contains("\"schema_version\""))
        assertTrue(encoded.contains("\"chunk_id\""))
        assertTrue(encoded.contains("\"metadata\""))
        assertTrue(encoded.contains("\"embeddings\""))
        assertEquals(index, decoded)
    }

    @Test
    fun cacheKeyChangesWhenTextModelOrStrategyChanges() {
        val sourceHash = RagEmbeddingCacheKeys.sourceHash(document(text = "same source"))
        val chunkTextHash = RagEmbeddingCacheKeys.chunkTextHash("same chunk")
        val base =
            RagEmbeddingCacheKeys.key(
                strategy = ChunkingStrategy.Fixed(),
                model = "model-a",
                sourceHash = sourceHash,
                chunkTextHash = chunkTextHash,
            )

        assertNotEquals(
            base,
            RagEmbeddingCacheKeys.key(
                strategy = ChunkingStrategy.Fixed(),
                model = "model-b",
                sourceHash = sourceHash,
                chunkTextHash = chunkTextHash,
            ),
        )
        assertNotEquals(
            base,
            RagEmbeddingCacheKeys.key(
                strategy = ChunkingStrategy.Fixed(),
                model = "model-a",
                sourceHash = sourceHash,
                chunkTextHash = RagEmbeddingCacheKeys.chunkTextHash("changed chunk"),
            ),
        )
        assertNotEquals(
            base,
            RagEmbeddingCacheKeys.key(
                strategy = ChunkingStrategy.Structure(),
                model = "model-a",
                sourceHash = sourceHash,
                chunkTextHash = chunkTextHash,
            ),
        )
    }

    @Test
    fun cosineSearchSortsDescendingAndLimitsTopK() {
        val documents =
            listOf(
                document(id = "best", text = "best match"),
                document(id = "second", text = "second match"),
                document(id = "worst", text = "worst match"),
            )
        val strategy = ChunkingStrategy.Fixed(maxTokens = 10, overlapTokens = 0)
        val chunks = documents.flatMap { document -> indexer.chunk(document, strategy) }
        val embeddingsByChunkId =
            mapOf(
                chunks[0].chunkId to listOf(1.0, 0.0),
                chunks[1].chunkId to listOf(0.8, 0.2),
                chunks[2].chunkId to listOf(0.0, 1.0),
            )
        val index =
            indexer.buildIndex(
                documents = documents,
                strategy = strategy,
                model = "test-embedding",
                embeddingsByChunkId = embeddingsByChunkId,
            )

        val results =
            RagSearch.search(
                index = index,
                queryEmbedding = listOf(1.0, 0.0),
                topK = 2,
            )

        assertEquals(2, results.size)
        assertEquals(chunks[0].chunkId, results[0].chunk.chunkId)
        assertEquals(chunks[1].chunkId, results[1].chunk.chunkId)
        assertTrue(results[0].score >= results[1].score)
    }

    private fun document(
        id: String = "doc",
        text: String,
    ): RagDocument =
        RagDocument(
            id = id,
            title = id,
            source = "$id.md",
            text = text,
        )

    private fun tokens(
        count: Int,
        prefix: String,
    ): String = (0 until count).joinToString(separator = " ") { index -> "$prefix$index" }

    private fun String.toTokens(): List<String> = trim().split(Regex("\\s+"))
}
