package com.akhavanskii.aichallenge.feature.ragindexing

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

interface RagIndexingStorage {
    val outputPaths: RagIndexingOutputPaths

    suspend fun listCorpus(): List<RagCorpusDocumentInfo>

    suspend fun loadCorpus(documentIds: Set<String>): List<RagDocument>

    suspend fun loadCorpus(): List<RagDocument> = loadCorpus(emptySet())

    suspend fun loadEmbeddingCache(): List<RagEmbeddingCacheEntry>

    suspend fun writeEmbeddingCache(entries: List<RagEmbeddingCacheEntry>): String

    suspend fun loadIndex(strategy: RagIndexingStrategy): RagIndex?

    suspend fun writeIndex(
        strategy: RagIndexingStrategy,
        index: RagIndex,
    ): String

    suspend fun writeComparison(
        report: RagComparisonReport,
        markdown: String,
    ): RagComparisonOutputPaths
}

data class RagCorpusDocumentInfo(
    val id: String,
    val title: String,
    val source: String,
    val wordCount: Int,
    val selectedByDefault: Boolean,
)

data class RagComparisonOutputPaths(
    val jsonPath: String,
    val markdownPath: String,
)

class AndroidRagIndexingStorage(
    context: Context,
    private val json: Json = storageJson,
) : RagIndexingStorage {
    private val appContext = context.applicationContext
    private val baseDir: File
        get() = File(appContext.filesDir, BASE_DIR)

    override val outputPaths: RagIndexingOutputPaths
        get() =
            RagIndexingOutputPaths(
                fixedIndex = indexFile(RagIndexingStrategy.FIXED).absolutePath,
                structureIndex = indexFile(RagIndexingStrategy.STRUCTURE).absolutePath,
                embeddingCache = cacheFile().absolutePath,
                comparisonJson = comparisonJsonFile().absolutePath,
                comparisonMarkdown = comparisonMarkdownFile().absolutePath,
            )

    override suspend fun listCorpus(): List<RagCorpusDocumentInfo> =
        corpusAssetNames().map { assetName ->
            val document = readCorpusDocument(assetName)
            RagCorpusDocumentInfo(
                id = document.id,
                title = document.title,
                source = document.source,
                wordCount = document.text.wordCount(),
                selectedByDefault = assetName != MOBY_DICK_ASSET_NAME,
            )
        }

    override suspend fun loadCorpus(documentIds: Set<String>): List<RagDocument> {
        val selectedIds = documentIds.map { id -> id.trim() }.filter { id -> id.isNotBlank() }.toSet()
        return corpusAssetNames()
            .filter { assetName ->
                selectedIds.isEmpty() || assetName.removeSuffix(".md") in selectedIds
            }.map { assetName -> readCorpusDocument(assetName) }
    }

    private fun corpusAssetNames(): List<String> =
        appContext.assets
            .list(CORPUS_ASSET_DIR)
            .orEmpty()
            .filter { name -> name.endsWith(".md") }
            .sorted()

    private fun readCorpusDocument(assetName: String): RagDocument {
        val assetPath = "$CORPUS_ASSET_DIR/$assetName"
        val text =
            appContext.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText()
            }

        return RagDocument(
            id = assetName.removeSuffix(".md"),
            title = titleFromMarkdown(text = text, fallback = assetName.removeSuffix(".md")),
            source = "assets/$assetPath",
            text = text,
            metadata =
                mapOf(
                    "asset_name" to assetName,
                    "asset_path" to "assets/$assetPath",
                ),
        )
    }

    override suspend fun loadEmbeddingCache(): List<RagEmbeddingCacheEntry> {
        val file = cacheFile()
        if (!file.isFile) {
            return emptyList()
        }

        return json.decodeFromString<RagEmbeddingCacheFile>(file.readText()).entries
    }

    override suspend fun writeEmbeddingCache(entries: List<RagEmbeddingCacheEntry>): String {
        val file = cacheFile()
        file.parentFile?.mkdirs()
        file.writeText(
            json.encodeToString(
                RagEmbeddingCacheFile(
                    entries = entries.sortedBy { entry -> entry.cacheKey },
                ),
            ),
        )
        return file.absolutePath
    }

    override suspend fun loadIndex(strategy: RagIndexingStrategy): RagIndex? {
        val file = indexFile(strategy)
        if (!file.isFile) {
            return null
        }

        return RagIndexJson.decode(file.readText())
    }

    override suspend fun writeIndex(
        strategy: RagIndexingStrategy,
        index: RagIndex,
    ): String {
        val file = indexFile(strategy)
        file.parentFile?.mkdirs()
        file.writeText(RagIndexJson.encode(index))
        return file.absolutePath
    }

    override suspend fun writeComparison(
        report: RagComparisonReport,
        markdown: String,
    ): RagComparisonOutputPaths {
        val jsonFile = comparisonJsonFile()
        val markdownFile = comparisonMarkdownFile()
        jsonFile.parentFile?.mkdirs()
        jsonFile.writeText(json.encodeToString(report))
        markdownFile.writeText(markdown)

        return RagComparisonOutputPaths(
            jsonPath = jsonFile.absolutePath,
            markdownPath = markdownFile.absolutePath,
        )
    }

    private fun indexFile(strategy: RagIndexingStrategy): File = File(File(baseDir, strategy.directoryName), INDEX_FILE_NAME)

    private fun cacheFile(): File = File(File(baseDir, CACHE_DIR), EMBEDDINGS_CACHE_FILE_NAME)

    private fun comparisonJsonFile(): File = File(baseDir, COMPARISON_JSON_FILE_NAME)

    private fun comparisonMarkdownFile(): File = File(baseDir, COMPARISON_MARKDOWN_FILE_NAME)

    private companion object {
        const val BASE_DIR = "rag-index"
        const val CACHE_DIR = "cache"
        const val CORPUS_ASSET_DIR = "rag"
        const val INDEX_FILE_NAME = "index.json"
        const val EMBEDDINGS_CACHE_FILE_NAME = "embeddings.json"
        const val COMPARISON_JSON_FILE_NAME = "comparison.json"
        const val COMPARISON_MARKDOWN_FILE_NAME = "comparison.md"
        const val MOBY_DICK_ASSET_NAME = "moby-dick.md"
    }
}

@Serializable
private data class RagEmbeddingCacheFile(
    @SerialName("schema_version")
    val schemaVersion: Int = 1,
    val entries: List<RagEmbeddingCacheEntry>,
)

internal val storageJson: Json =
    Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

private fun titleFromMarkdown(
    text: String,
    fallback: String,
): String =
    text
        .lineSequence()
        .firstOrNull { line -> line.startsWith("# ") }
        ?.removePrefix("# ")
        ?.trim()
        ?.takeIf { title -> title.isNotBlank() }
        ?: fallback

private fun String.wordCount(): Int =
    trim()
        .split(Regex("\\s+"))
        .count { word -> word.isNotBlank() }
