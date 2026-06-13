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
    val selectedStrategy: ContextManagementStrategy = ContextManagementStrategy.SLIDING_WINDOW,
    val facts: List<ContextFact> = emptyList(),
    val branchingState: ContextBranchingState = ContextBranchingState(),
    val strategyStats: ContextStrategyStats? = null,
    val comparison: ContextScenarioComparison? = null,
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
    val selectedStrategyName: String = ContextManagementStrategy.SLIDING_WINDOW.name,
    val facts: List<StoredContextFact> = emptyList(),
    val branchingState: StoredContextBranchingState = StoredContextBranchingState(),
    val strategyStats: StoredContextStrategyStats? = null,
    val comparison: StoredContextScenarioComparison? = null,
    val messages: List<StoredContextAgentMessage> = emptyList(),
) {
    fun toSnapshot(): ContextAgentHistorySnapshot =
        ContextAgentHistorySnapshot(
            selectedModel =
                ContextAgentModelOption.entries.firstOrNull { it.modelName == selectedModelName }
                    ?: ContextAgentModelOption.GEMINI_3_5_FLASH,
            selectedStrategy =
                ContextManagementStrategy.entries.firstOrNull { it.name == selectedStrategyName }
                    ?: ContextManagementStrategy.SLIDING_WINDOW,
            facts = facts.map { it.toFact() },
            branchingState = branchingState.toBranchingState(),
            strategyStats = strategyStats?.toStats(),
            comparison = comparison?.toComparison(),
            messages = messages.mapNotNull { it.toMessageOrNull() },
        )

    companion object {
        fun fromSnapshot(snapshot: ContextAgentHistorySnapshot): StoredContextAgentHistory =
            StoredContextAgentHistory(
                selectedModelName = snapshot.selectedModel.modelName,
                selectedStrategyName = snapshot.selectedStrategy.name,
                facts = snapshot.facts.map(StoredContextFact::fromFact),
                branchingState = StoredContextBranchingState.fromBranchingState(snapshot.branchingState),
                strategyStats = snapshot.strategyStats?.let(StoredContextStrategyStats::fromStats),
                comparison = snapshot.comparison?.let(StoredContextScenarioComparison::fromComparison),
                messages = snapshot.messages.filterNot { it.isLoading }.map(StoredContextAgentMessage::fromMessage),
            )
    }
}

@Serializable
private data class StoredContextFact(
    val key: String,
    val value: String,
) {
    fun toFact(): ContextFact = ContextFact(key = key, value = value)

    companion object {
        fun fromFact(fact: ContextFact): StoredContextFact = StoredContextFact(key = fact.key, value = fact.value)
    }
}

@Serializable
private data class StoredContextBranchingState(
    val checkpointMessages: List<StoredContextAgentMessage> = emptyList(),
    val branches: List<StoredContextAgentBranch> = emptyList(),
    val activeBranchId: String = ContextBranchId.A.name,
    val hasCheckpoint: Boolean = false,
) {
    fun toBranchingState(): ContextBranchingState {
        val restoredBranches = branches.mapNotNull { it.toBranchOrNull() }
        val branchIds = restoredBranches.map { it.id }.toSet()
        val completeBranches =
            restoredBranches +
                ContextBranchId.entries
                    .filterNot { it in branchIds }
                    .map { branchId -> ContextAgentBranch(id = branchId) }
        return ContextBranchingState(
            checkpointMessages = checkpointMessages.mapNotNull { it.toMessageOrNull() },
            branches = completeBranches.sortedBy { it.id.ordinal },
            activeBranchId = ContextBranchId.entries.firstOrNull { it.name == activeBranchId } ?: ContextBranchId.A,
            hasCheckpoint = hasCheckpoint,
        )
    }

    companion object {
        fun fromBranchingState(state: ContextBranchingState): StoredContextBranchingState =
            StoredContextBranchingState(
                checkpointMessages =
                    state.checkpointMessages
                        .filterNot { it.isLoading }
                        .map(StoredContextAgentMessage::fromMessage),
                branches = state.branches.map(StoredContextAgentBranch::fromBranch),
                activeBranchId = state.activeBranchId.name,
                hasCheckpoint = state.hasCheckpoint,
            )
    }
}

@Serializable
private data class StoredContextAgentBranch(
    val id: String,
    val title: String,
    val messages: List<StoredContextAgentMessage> = emptyList(),
) {
    fun toBranchOrNull(): ContextAgentBranch? {
        val branchId = ContextBranchId.entries.firstOrNull { it.name == id } ?: return null
        return ContextAgentBranch(
            id = branchId,
            title = title.ifBlank { branchId.title },
            messages = messages.mapNotNull { it.toMessageOrNull() },
        )
    }

    companion object {
        fun fromBranch(branch: ContextAgentBranch): StoredContextAgentBranch =
            StoredContextAgentBranch(
                id = branch.id.name,
                title = branch.title,
                messages = branch.messages.filterNot { it.isLoading }.map(StoredContextAgentMessage::fromMessage),
            )
    }
}

@Serializable
private data class StoredContextAgentMessage(
    val role: String,
    val text: String,
    val isError: Boolean = false,
    val tokenUsage: GeminiTokenUsage? = null,
) {
    fun toMessageOrNull(): ContextAgentMessage? {
        val restoredRole = ContextAgentRole.entries.firstOrNull { it.name == role } ?: return null
        return ContextAgentMessage(
            role = restoredRole,
            text = text,
            isError = isError,
            tokenUsage = tokenUsage,
        )
    }

    companion object {
        fun fromMessage(message: ContextAgentMessage): StoredContextAgentMessage =
            StoredContextAgentMessage(
                role = message.role.name,
                text = message.text,
                isError = message.isError,
                tokenUsage = message.tokenUsage,
            )
    }
}

@Serializable
private data class StoredContextStrategyStats(
    val strategyName: String,
    val fullPromptTokens: Int? = null,
    val strategyPromptTokens: Int? = null,
    val savedPromptTokens: Int? = null,
    val savedPromptPercent: Int? = null,
    val storedMessageCount: Int = 0,
    val requestMessageCount: Int = 0,
    val droppedMessageCount: Int = 0,
    val factsCount: Int = 0,
    val activeBranchTitle: String? = null,
) {
    fun toStats(): ContextStrategyStats =
        ContextStrategyStats(
            strategy =
                ContextManagementStrategy.entries.firstOrNull { it.name == strategyName }
                    ?: ContextManagementStrategy.SLIDING_WINDOW,
            fullPromptTokens = fullPromptTokens,
            strategyPromptTokens = strategyPromptTokens,
            savedPromptTokens = savedPromptTokens,
            savedPromptPercent = savedPromptPercent,
            storedMessageCount = storedMessageCount,
            requestMessageCount = requestMessageCount,
            droppedMessageCount = droppedMessageCount,
            factsCount = factsCount,
            activeBranchTitle = activeBranchTitle,
        )

    companion object {
        fun fromStats(stats: ContextStrategyStats): StoredContextStrategyStats =
            StoredContextStrategyStats(
                strategyName = stats.strategy.name,
                fullPromptTokens = stats.fullPromptTokens,
                strategyPromptTokens = stats.strategyPromptTokens,
                savedPromptTokens = stats.savedPromptTokens,
                savedPromptPercent = stats.savedPromptPercent,
                storedMessageCount = stats.storedMessageCount,
                requestMessageCount = stats.requestMessageCount,
                droppedMessageCount = stats.droppedMessageCount,
                factsCount = stats.factsCount,
                activeBranchTitle = stats.activeBranchTitle,
            )
    }
}

@Serializable
private data class StoredContextScenarioComparison(
    val reports: List<StoredContextScenarioStrategyReport> = emptyList(),
    val evaluation: String = "",
) {
    fun toComparison(): ContextScenarioComparison =
        ContextScenarioComparison(
            reports = reports.mapNotNull { it.toReportOrNull() },
            evaluation = evaluation,
        )

    companion object {
        fun fromComparison(comparison: ContextScenarioComparison): StoredContextScenarioComparison =
            StoredContextScenarioComparison(
                reports = comparison.reports.map(StoredContextScenarioStrategyReport::fromReport),
                evaluation = comparison.evaluation,
            )
    }
}

@Serializable
private data class StoredContextScenarioStrategyReport(
    val strategyName: String,
    val branchTitle: String? = null,
    val answer: String,
    val promptTokens: Int? = null,
    val requestMessageCount: Int = 0,
    val quality: String,
    val stability: String,
    val tokenUse: String,
    val userConvenience: String,
) {
    fun toReportOrNull(): ContextScenarioStrategyReport? {
        val strategy = ContextManagementStrategy.entries.firstOrNull { it.name == strategyName } ?: return null
        return ContextScenarioStrategyReport(
            strategy = strategy,
            branchTitle = branchTitle,
            answer = answer,
            promptTokens = promptTokens,
            requestMessageCount = requestMessageCount,
            quality = quality,
            stability = stability,
            tokenUse = tokenUse,
            userConvenience = userConvenience,
        )
    }

    companion object {
        fun fromReport(report: ContextScenarioStrategyReport): StoredContextScenarioStrategyReport =
            StoredContextScenarioStrategyReport(
                strategyName = report.strategy.name,
                branchTitle = report.branchTitle,
                answer = report.answer,
                promptTokens = report.promptTokens,
                requestMessageCount = report.requestMessageCount,
                quality = report.quality,
                stability = report.stability,
                tokenUse = report.tokenUse,
                userConvenience = report.userConvenience,
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
