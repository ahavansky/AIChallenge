package com.akhavanskii.aichallenge.feature.ragindexing

class RagIndexer {
    fun chunk(
        document: RagDocument,
        strategy: ChunkingStrategy,
    ): List<RagChunk> =
        when (strategy) {
            is ChunkingStrategy.Fixed -> fixedChunks(document = document, strategy = strategy)
            is ChunkingStrategy.Structure -> structureChunks(document = document, strategy = strategy)
        }

    fun buildIndex(
        documents: List<RagDocument>,
        strategy: ChunkingStrategy,
        model: String,
        embeddingsByChunkId: Map<String, List<Double>> = emptyMap(),
        metadata: Map<String, String> = emptyMap(),
    ): RagIndex {
        val chunks = documents.flatMap { document -> chunk(document, strategy) }
        return buildIndexFromChunks(
            documents = documents,
            chunks = chunks,
            strategy = strategy,
            model = model,
            embeddingsByChunkId = embeddingsByChunkId,
            metadata = metadata,
        )
    }

    fun buildIndexFromChunks(
        documents: List<RagDocument>,
        chunks: List<RagChunk>,
        strategy: ChunkingStrategy,
        model: String,
        embeddingsByChunkId: Map<String, List<Double>> = emptyMap(),
        metadata: Map<String, String> = emptyMap(),
    ): RagIndex {
        val embeddings =
            chunks.mapNotNull { chunk ->
                embeddingsByChunkId[chunk.chunkId]?.let { embedding ->
                    RagEmbeddingCacheEntry.from(
                        chunk = chunk,
                        model = model,
                        embedding = embedding,
                    )
                }
            }

        return RagIndex(
            model = model,
            strategy = strategy.key,
            sourceHash = RagEmbeddingCacheKeys.sourceHash(documents),
            chunks = chunks,
            embeddings = embeddings,
            metadata =
                metadata +
                    mapOf(
                        "document_count" to documents.size.toString(),
                        "chunk_count" to chunks.size.toString(),
                    ),
        )
    }

    private fun fixedChunks(
        document: RagDocument,
        strategy: ChunkingStrategy.Fixed,
    ): List<RagChunk> =
        fixedTextChunks(
            document = document,
            sourceHash = RagEmbeddingCacheKeys.sourceHash(document),
            strategyKey = strategy.key,
            text = document.text,
            tokenStartOffset = 0,
            ordinalStart = 0,
            maxTokens = strategy.maxTokens,
            overlapTokens = strategy.overlapTokens,
            metadata =
                document.metadata +
                    mapOf(
                        "chunking" to "fixed",
                        "split_method" to "fixed",
                    ),
        ).chunks

    private fun structureChunks(
        document: RagDocument,
        strategy: ChunkingStrategy.Structure,
    ): List<RagChunk> {
        val sourceHash = RagEmbeddingCacheKeys.sourceHash(document)
        val chunks = mutableListOf<RagChunk>()
        var ordinal = 0

        markdownSections(document.text).forEachIndexed { sectionIndex, section ->
            val sectionMetadata =
                document.metadata +
                    mapOf(
                        "chunking" to "structure",
                        "section_index" to sectionIndex.toString(),
                        "section_heading" to section.heading,
                        "heading_level" to section.level.toString(),
                    )

            val sectionTokenCount = tokenize(section.text).size
            if (sectionTokenCount <= strategy.maxTokens) {
                val chunkText = section.text.trim()
                if (chunkText.isNotEmpty()) {
                    chunks +=
                        chunkFromText(
                            document = document,
                            sourceHash = sourceHash,
                            strategyKey = strategy.key,
                            text = chunkText,
                            tokenStart = section.tokenStart,
                            ordinal = ordinal,
                            metadata = sectionMetadata + ("split_method" to "section"),
                        )
                    ordinal += 1
                }
            } else {
                val split =
                    splitOversizedSection(
                        document = document,
                        sourceHash = sourceHash,
                        strategy = strategy,
                        section = section,
                        ordinalStart = ordinal,
                        metadata = sectionMetadata,
                    )
                chunks += split.chunks
                ordinal = split.nextOrdinal
            }
        }

        return chunks
    }

    private fun splitOversizedSection(
        document: RagDocument,
        sourceHash: String,
        strategy: ChunkingStrategy.Structure,
        section: MarkdownSection,
        ordinalStart: Int,
        metadata: Map<String, String>,
    ): ChunkBatch {
        val chunks = mutableListOf<RagChunk>()
        var ordinal = ordinalStart
        var currentText = mutableListOf<String>()
        var currentTokenStart = section.tokenStart
        var currentTokenCount = 0

        fun flushParagraphChunk() {
            if (currentText.isEmpty()) return
            chunks +=
                chunkFromText(
                    document = document,
                    sourceHash = sourceHash,
                    strategyKey = strategy.key,
                    text = currentText.joinToString(separator = "\n\n"),
                    tokenStart = currentTokenStart,
                    ordinal = ordinal,
                    metadata = metadata + ("split_method" to "paragraph"),
                )
            ordinal += 1
            currentText = mutableListOf()
            currentTokenCount = 0
        }

        paragraphs(section.text, section.tokenStart).forEach { paragraph ->
            if (paragraph.tokenCount > strategy.maxTokens) {
                flushParagraphChunk()
                val fixedSplit =
                    fixedTextChunks(
                        document = document,
                        sourceHash = sourceHash,
                        strategyKey = strategy.key,
                        text = paragraph.text,
                        tokenStartOffset = paragraph.tokenStart,
                        ordinalStart = ordinal,
                        maxTokens = strategy.maxTokens,
                        overlapTokens = strategy.overlapTokens,
                        metadata = metadata + ("split_method" to "fixed"),
                    )
                chunks += fixedSplit.chunks
                ordinal = fixedSplit.nextOrdinal
            } else if (currentTokenCount + paragraph.tokenCount <= strategy.maxTokens) {
                if (currentText.isEmpty()) {
                    currentTokenStart = paragraph.tokenStart
                }
                currentText += paragraph.text
                currentTokenCount += paragraph.tokenCount
            } else {
                flushParagraphChunk()
                currentTokenStart = paragraph.tokenStart
                currentText += paragraph.text
                currentTokenCount = paragraph.tokenCount
            }
        }

        flushParagraphChunk()

        return ChunkBatch(
            chunks = chunks,
            nextOrdinal = ordinal,
        )
    }

    private fun fixedTextChunks(
        document: RagDocument,
        sourceHash: String,
        strategyKey: String,
        text: String,
        tokenStartOffset: Int,
        ordinalStart: Int,
        maxTokens: Int,
        overlapTokens: Int,
        metadata: Map<String, String>,
    ): ChunkBatch {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) {
            return ChunkBatch(
                chunks = emptyList(),
                nextOrdinal = ordinalStart,
            )
        }

        val chunks = mutableListOf<RagChunk>()
        var start = 0
        var ordinal = ordinalStart

        while (start < tokens.size) {
            val end = minOf(start + maxTokens, tokens.size)
            val chunkText = text.substring(tokens[start].start, tokens[end - 1].end).trim()

            chunks +=
                chunkFromText(
                    document = document,
                    sourceHash = sourceHash,
                    strategyKey = strategyKey,
                    text = chunkText,
                    tokenStart = tokenStartOffset + start,
                    ordinal = ordinal,
                    metadata = metadata,
                )
            ordinal += 1

            if (end == tokens.size) {
                break
            }

            val nextStart = end - overlapTokens
            start = if (nextStart <= start) end else nextStart
        }

        return ChunkBatch(
            chunks = chunks,
            nextOrdinal = ordinal,
        )
    }

    private fun chunkFromText(
        document: RagDocument,
        sourceHash: String,
        strategyKey: String,
        text: String,
        tokenStart: Int,
        ordinal: Int,
        metadata: Map<String, String>,
    ): RagChunk {
        val tokenCount = tokenize(text).size
        val chunkText = text.trim()

        return RagChunk(
            chunkId =
                stableChunkId(
                    strategy = strategyKey,
                    document = document,
                    sourceHash = sourceHash,
                    ordinal = ordinal,
                    chunkText = chunkText,
                ),
            documentId = document.id,
            title = document.title,
            source = document.source,
            sourceHash = sourceHash,
            text = chunkText,
            tokenStart = tokenStart,
            tokenEnd = tokenStart + tokenCount,
            tokenCount = tokenCount,
            strategy = strategyKey,
            metadata = metadata + ("chunk_index" to ordinal.toString()),
        )
    }
}

private data class ChunkBatch(
    val chunks: List<RagChunk>,
    val nextOrdinal: Int,
)

private data class MarkdownSection(
    val heading: String,
    val level: Int,
    val text: String,
    val tokenStart: Int,
)

private data class Paragraph(
    val text: String,
    val tokenStart: Int,
    val tokenCount: Int,
)

private data class Token(
    val start: Int,
    val end: Int,
)

private val headingRegex = Regex("(?m)^(?:(#{1,6})[ \\t]+(.+?)|((?:CHAPTER|Chapter)[ \\t]+\\d+\\..+?))[ \\t\\r]*$")

private fun tokenize(text: String): List<Token> {
    val tokens = mutableListOf<Token>()
    var index = 0

    while (index < text.length) {
        while (index < text.length && text[index].isWhitespace()) {
            index += 1
        }

        val start = index
        while (index < text.length && !text[index].isWhitespace()) {
            index += 1
        }

        if (start < index) {
            tokens += Token(start = start, end = index)
        }
    }

    return tokens
}

private fun markdownSections(text: String): List<MarkdownSection> {
    val headings = headingRegex.findAll(text).toList()
    if (headings.isEmpty()) {
        return listOf(
            MarkdownSection(
                heading = "Preamble",
                level = 0,
                text = text,
                tokenStart = 0,
            ),
        )
    }

    val tokens = tokenize(text)
    val sections = mutableListOf<MarkdownSection>()
    val firstHeadingStart = headings.first().range.first
    if (firstHeadingStart > 0) {
        sections +=
            MarkdownSection(
                heading = "Preamble",
                level = 0,
                text = text.substring(0, firstHeadingStart),
                tokenStart = 0,
            )
    }

    headings.forEachIndexed { index, heading ->
        val start = heading.range.first
        val end = headings.getOrNull(index + 1)?.range?.first ?: text.length
        sections +=
            MarkdownSection(
                heading = heading.sectionHeading(),
                level = heading.sectionLevel(),
                text = text.substring(start, end),
                tokenStart = tokenIndexAt(tokens, start),
            )
    }

    return sections.filter { section -> section.text.isNotBlank() }
}

private fun MatchResult.sectionHeading(): String = groupValues[2].ifBlank { groupValues[3] }.trim()

private fun MatchResult.sectionLevel(): Int = groupValues[1].takeIf { it.isNotBlank() }?.length ?: 2

private fun paragraphs(
    text: String,
    tokenStart: Int,
): List<Paragraph> {
    var cursorToken = tokenStart
    val paragraphs = mutableListOf<Paragraph>()
    val currentText = StringBuilder()

    fun flushParagraph() {
        val paragraphText = currentText.toString().trim()
        currentText.clear()
        if (paragraphText.isEmpty()) {
            return
        }

        val paragraphTokenCount = tokenize(paragraphText).size
        paragraphs +=
            Paragraph(
                text = paragraphText,
                tokenStart = cursorToken,
                tokenCount = paragraphTokenCount,
            )
        cursorToken += paragraphTokenCount
    }

    text.lineSequence().forEach { line ->
        if (line.isBlank()) {
            flushParagraph()
        } else {
            if (currentText.isNotEmpty()) {
                currentText.append('\n')
            }
            currentText.append(line)
        }
    }
    flushParagraph()

    return paragraphs
}

private fun tokenIndexAt(
    tokens: List<Token>,
    charOffset: Int,
): Int {
    var low = 0
    var high = tokens.size

    while (low < high) {
        val mid = (low + high) / 2
        if (tokens[mid].start < charOffset) {
            low = mid + 1
        } else {
            high = mid
        }
    }

    return low
}
