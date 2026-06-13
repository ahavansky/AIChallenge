package com.akhavanskii.aichallenge.feature.contextagent

import android.content.Context
import com.akhavanskii.aichallenge.core.network.GeminiTokenUsage
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

data class ContextAgentHistorySnapshot(
    val messages: List<ContextAgentMessage> = emptyList(),
    val selectedModel: ContextAgentModelOption = ContextAgentModelOption.GEMINI_3_5_FLASH,
    val contextState: ContextCompressionState = ContextCompressionState(),
    val comparison: ContextQualityComparison? = null,
)

interface ContextAgentHistoryStore {
    suspend fun load(): ContextAgentHistorySnapshot

    suspend fun save(snapshot: ContextAgentHistorySnapshot)
}

class JsonContextAgentHistoryStore internal constructor(
    private val historyFile: File,
    private val json: Json,
    private val dispatcher: CoroutineDispatcher,
) : ContextAgentHistoryStore {
    private val mutex = Mutex()

    @Inject
    constructor(
        @ApplicationContext context: Context,
        json: Json,
        @ContextAgentStorageDispatcher dispatcher: CoroutineDispatcher,
    ) : this(
        historyFile = File(context.filesDir, HISTORY_FILE_NAME),
        json = json,
        dispatcher = dispatcher,
    )

    override suspend fun load(): ContextAgentHistorySnapshot =
        withContext(dispatcher) {
            mutex.withLock {
                if (!historyFile.exists()) {
                    return@withLock ContextAgentHistorySnapshot()
                }

                runCatching {
                    json.decodeFromString<StoredContextAgentHistory>(historyFile.readText()).toSnapshot()
                }.getOrDefault(ContextAgentHistorySnapshot())
            }
        }

    override suspend fun save(snapshot: ContextAgentHistorySnapshot) {
        withContext(dispatcher) {
            mutex.withLock {
                val encoded = json.encodeToString(StoredContextAgentHistory.fromSnapshot(snapshot))
                val parent = historyFile.parentFile
                parent?.mkdirs()

                val tempFile = File(parent, "${historyFile.name}.tmp")
                tempFile.writeText(encoded)
                if (!tempFile.renameTo(historyFile)) {
                    historyFile.writeText(encoded)
                    tempFile.delete()
                }
            }
        }
    }

    private companion object {
        const val HISTORY_FILE_NAME = "context_agent_history.json"
    }
}

@Serializable
private data class StoredContextAgentHistory(
    val selectedModelName: String = ContextAgentModelOption.GEMINI_3_5_FLASH.modelName,
    val summary: String = "",
    val summarizedMessageCount: Int = 0,
    val latestStats: StoredContextCompressionStats? = null,
    val comparison: StoredContextQualityComparison? = null,
    val messages: List<StoredContextAgentMessage> = emptyList(),
) {
    fun toSnapshot(): ContextAgentHistorySnapshot =
        ContextAgentHistorySnapshot(
            selectedModel =
                ContextAgentModelOption.entries.firstOrNull { it.modelName == selectedModelName }
                    ?: ContextAgentModelOption.GEMINI_3_5_FLASH,
            contextState =
                ContextCompressionState(
                    summary = summary,
                    summarizedMessageCount = summarizedMessageCount.coerceAtLeast(0),
                    latestStats = latestStats?.toStats(),
                ),
            comparison = comparison?.toComparison(),
            messages = messages.mapNotNull { it.toMessageOrNull() },
        )

    companion object {
        fun fromSnapshot(snapshot: ContextAgentHistorySnapshot): StoredContextAgentHistory =
            StoredContextAgentHistory(
                selectedModelName = snapshot.selectedModel.modelName,
                summary = snapshot.contextState.summary,
                summarizedMessageCount = snapshot.contextState.summarizedMessageCount,
                latestStats = snapshot.contextState.latestStats?.let(StoredContextCompressionStats::fromStats),
                comparison = snapshot.comparison?.let(StoredContextQualityComparison::fromComparison),
                messages = snapshot.messages.filterNot { it.isLoading }.map(StoredContextAgentMessage::fromMessage),
            )
    }
}

@Serializable
private data class StoredContextAgentMessage(
    val role: String,
    val text: String,
    val isError: Boolean = false,
    val tokenUsage: GeminiTokenUsage? = null,
    val includeInContext: Boolean = true,
) {
    fun toMessageOrNull(): ContextAgentMessage? {
        val restoredRole = ContextAgentRole.entries.firstOrNull { it.name == role } ?: return null
        return ContextAgentMessage(
            role = restoredRole,
            text = text,
            isError = isError,
            tokenUsage = tokenUsage,
            includeInContext = includeInContext,
        )
    }

    companion object {
        fun fromMessage(message: ContextAgentMessage): StoredContextAgentMessage =
            StoredContextAgentMessage(
                role = message.role.name,
                text = message.text,
                isError = message.isError,
                tokenUsage = message.tokenUsage,
                includeInContext = message.includeInContext,
            )
    }
}

@Serializable
private data class StoredContextCompressionStats(
    val fullPromptTokens: Int? = null,
    val compressedPromptTokens: Int? = null,
    val savedPromptTokens: Int? = null,
    val savedPromptPercent: Int? = null,
    val summarizedMessageCount: Int = 0,
    val rawMessageCount: Int = 0,
    val requestMessageCount: Int = 0,
    val recentMessageLimit: Int = CONTEXT_AGENT_RECENT_MESSAGE_COUNT,
    val summaryBatchSize: Int = CONTEXT_AGENT_SUMMARY_BATCH_SIZE,
) {
    fun toStats(): ContextCompressionStats =
        ContextCompressionStats(
            fullPromptTokens = fullPromptTokens,
            compressedPromptTokens = compressedPromptTokens,
            savedPromptTokens = savedPromptTokens,
            savedPromptPercent = savedPromptPercent,
            summarizedMessageCount = summarizedMessageCount,
            rawMessageCount = rawMessageCount,
            requestMessageCount = requestMessageCount,
            recentMessageLimit = recentMessageLimit,
            summaryBatchSize = summaryBatchSize,
        )

    companion object {
        fun fromStats(stats: ContextCompressionStats): StoredContextCompressionStats =
            StoredContextCompressionStats(
                fullPromptTokens = stats.fullPromptTokens,
                compressedPromptTokens = stats.compressedPromptTokens,
                savedPromptTokens = stats.savedPromptTokens,
                savedPromptPercent = stats.savedPromptPercent,
                summarizedMessageCount = stats.summarizedMessageCount,
                rawMessageCount = stats.rawMessageCount,
                requestMessageCount = stats.requestMessageCount,
                recentMessageLimit = stats.recentMessageLimit,
                summaryBatchSize = stats.summaryBatchSize,
            )
    }
}

@Serializable
private data class StoredContextQualityComparison(
    val fullHistoryAnswer: String,
    val compressedHistoryAnswer: String,
    val evaluation: String,
) {
    fun toComparison(): ContextQualityComparison =
        ContextQualityComparison(
            fullHistoryAnswer = fullHistoryAnswer,
            compressedHistoryAnswer = compressedHistoryAnswer,
            evaluation = evaluation,
        )

    companion object {
        fun fromComparison(comparison: ContextQualityComparison): StoredContextQualityComparison =
            StoredContextQualityComparison(
                fullHistoryAnswer = comparison.fullHistoryAnswer,
                compressedHistoryAnswer = comparison.compressedHistoryAnswer,
                evaluation = comparison.evaluation,
            )
    }
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ContextAgentStorageDispatcher

@Module
@InstallIn(SingletonComponent::class)
interface ContextAgentHistoryBindings {
    @Binds
    @Singleton
    fun bindContextAgentHistoryStore(store: JsonContextAgentHistoryStore): ContextAgentHistoryStore
}

@Module
@InstallIn(SingletonComponent::class)
object ContextAgentHistoryModule {
    @Provides
    @ContextAgentStorageDispatcher
    fun provideContextAgentStorageDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
