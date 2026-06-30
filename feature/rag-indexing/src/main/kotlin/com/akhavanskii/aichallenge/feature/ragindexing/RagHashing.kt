package com.akhavanskii.aichallenge.feature.ragindexing

import java.security.MessageDigest

object RagEmbeddingCacheKeys {
    fun sourceHash(document: RagDocument): String =
        sha256Hex(
            listOf(
                document.id,
                document.title,
                document.source,
                document.text,
                document.metadata.toSortedFingerprint(),
            ).joinToString(separator = FIELD_SEPARATOR),
        )

    fun sourceHash(documents: List<RagDocument>): String =
        sha256Hex(
            documents.joinToString(separator = RECORD_SEPARATOR) { document ->
                listOf(
                    document.id,
                    document.title,
                    document.source,
                    document.text,
                    document.metadata.toSortedFingerprint(),
                ).joinToString(separator = FIELD_SEPARATOR)
            },
        )

    fun chunkTextHash(text: String): String = sha256Hex("chunk-text$FIELD_SEPARATOR$text")

    fun key(
        strategy: ChunkingStrategy,
        model: String,
        sourceHash: String,
        chunkTextHash: String,
    ): String =
        key(
            strategy = strategy.key,
            model = model,
            sourceHash = sourceHash,
            chunkTextHash = chunkTextHash,
        )

    fun key(
        strategy: String,
        model: String,
        sourceHash: String,
        chunkTextHash: String,
    ): String =
        "rag-cache-v1-" +
            sha256Hex(
                listOf(
                    strategy,
                    model,
                    sourceHash,
                    chunkTextHash,
                ).joinToString(separator = FIELD_SEPARATOR),
            ).take(32)
}

internal fun stableChunkId(
    strategy: String,
    document: RagDocument,
    sourceHash: String,
    ordinal: Int,
    chunkText: String,
): String =
    "chunk-" +
        sha256Hex(
            listOf(
                strategy,
                document.id,
                document.source,
                sourceHash,
                ordinal.toString(),
                RagEmbeddingCacheKeys.chunkTextHash(chunkText),
            ).joinToString(separator = FIELD_SEPARATOR),
        ).take(24)

private const val FIELD_SEPARATOR = "\u001F"
private const val RECORD_SEPARATOR = "\u001E"
private val HEX_CHARS = "0123456789abcdef".toCharArray()

private fun Map<String, String>.toSortedFingerprint(): String =
    entries
        .sortedBy { it.key }
        .joinToString(separator = FIELD_SEPARATOR) { (key, value) -> "$key=$value" }

private fun sha256Hex(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val chars = CharArray(bytes.size * 2)
    var index = 0
    bytes.forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        chars[index++] = HEX_CHARS[unsigned ushr 4]
        chars[index++] = HEX_CHARS[unsigned and 0x0f]
    }
    return String(chars)
}
