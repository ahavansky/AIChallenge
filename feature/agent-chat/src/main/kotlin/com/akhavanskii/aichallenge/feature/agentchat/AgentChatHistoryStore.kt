package com.akhavanskii.aichallenge.feature.agentchat

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

data class AgentChatHistorySnapshot(
    val messages: List<AgentChatMessage> = emptyList(),
    val memory: AgentChatMemorySnapshot = AgentChatMemorySnapshot(),
    val selectedAgent: AgentChatAgentOption = AgentChatAgentOption.GEMINI_3_5_FLASH,
    val customTotalTokenLimit: Int? = null,
)

interface AgentChatHistoryStore {
    suspend fun load(): AgentChatHistorySnapshot

    suspend fun save(snapshot: AgentChatHistorySnapshot)
}

interface AgentChatLongTermMemoryStore {
    suspend fun load(): AgentChatLongTermMarkdown

    suspend fun save(memory: AgentChatLongTermMarkdown)
}

class JsonAgentChatHistoryStore internal constructor(
    private val historyFile: File,
    private val json: Json,
    private val dispatcher: CoroutineDispatcher,
) : AgentChatHistoryStore {
    private val mutex = Mutex()

    @Inject
    constructor(
        @ApplicationContext context: Context,
        json: Json,
        @AgentChatStorageDispatcher dispatcher: CoroutineDispatcher,
    ) : this(
        historyFile = File(context.filesDir, HISTORY_FILE_NAME),
        json = json,
        dispatcher = dispatcher,
    )

    override suspend fun load(): AgentChatHistorySnapshot =
        withContext(dispatcher) {
            mutex.withLock {
                if (!historyFile.exists()) {
                    return@withLock AgentChatHistorySnapshot()
                }

                runCatching {
                    json.decodeFromString<StoredAgentChatHistory>(historyFile.readText()).toSnapshot()
                }.getOrDefault(AgentChatHistorySnapshot())
            }
        }

    override suspend fun save(snapshot: AgentChatHistorySnapshot) {
        withContext(dispatcher) {
            mutex.withLock {
                val encoded = json.encodeToString(StoredAgentChatHistory.fromSnapshot(snapshot))
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
        const val HISTORY_FILE_NAME = "agent_chat_history.json"
    }
}

class MarkdownAgentChatLongTermMemoryStore internal constructor(
    private val memoryFile: File,
    private val dispatcher: CoroutineDispatcher,
) : AgentChatLongTermMemoryStore {
    private val mutex = Mutex()

    @Inject
    constructor(
        @ApplicationContext context: Context,
        @AgentChatStorageDispatcher dispatcher: CoroutineDispatcher,
    ) : this(
        memoryFile = File(context.filesDir, DEFAULT_LONG_TERM_MEMORY_FILE_NAME),
        dispatcher = dispatcher,
    )

    override suspend fun load(): AgentChatLongTermMarkdown =
        withContext(dispatcher) {
            mutex.withLock {
                val markdown =
                    if (memoryFile.exists()) {
                        runCatching { memoryFile.readText() }.getOrDefault(DEFAULT_LONG_TERM_MEMORY_MARKDOWN)
                    } else {
                        DEFAULT_LONG_TERM_MEMORY_MARKDOWN
                    }
                AgentChatLongTermMarkdown(fileName = memoryFile.name, markdown = markdown)
            }
        }

    override suspend fun save(memory: AgentChatLongTermMarkdown) {
        withContext(dispatcher) {
            mutex.withLock {
                val parent = memoryFile.parentFile
                parent?.mkdirs()

                val tempFile = File(parent, "${memoryFile.name}.tmp")
                tempFile.writeText(memory.markdown)
                if (!tempFile.renameTo(memoryFile)) {
                    memoryFile.writeText(memory.markdown)
                    tempFile.delete()
                }
            }
        }
    }
}

@Serializable
private data class StoredAgentChatHistory(
    val selectedAgentModelName: String = AgentChatAgentOption.GEMINI_3_5_FLASH.modelName,
    val customTotalTokenLimit: Int? = null,
    val memory: AgentChatMemorySnapshot = AgentChatMemorySnapshot(),
    val messages: List<StoredAgentChatMessage> = emptyList(),
) {
    fun toSnapshot(): AgentChatHistorySnapshot {
        val restoredMessages = messages.mapNotNull { it.toMessageOrNull() }
        return AgentChatHistorySnapshot(
            selectedAgent =
                AgentChatAgentOption.entries.firstOrNull { it.modelName == selectedAgentModelName }
                    ?: AgentChatAgentOption.GEMINI_3_5_FLASH,
            customTotalTokenLimit = customTotalTokenLimit,
            memory = memory,
            messages = restoredMessages,
        )
    }

    companion object {
        fun fromSnapshot(snapshot: AgentChatHistorySnapshot): StoredAgentChatHistory =
            StoredAgentChatHistory(
                selectedAgentModelName = snapshot.selectedAgent.modelName,
                customTotalTokenLimit = snapshot.customTotalTokenLimit,
                memory = snapshot.memory,
                messages = snapshot.messages.filterNot { it.isLoading }.map(StoredAgentChatMessage::fromMessage),
            )
    }
}

@Serializable
private data class StoredAgentChatMessage(
    val role: String,
    val text: String,
    val isError: Boolean = false,
    val tokenUsage: GeminiTokenUsage? = null,
) {
    fun toMessageOrNull(): AgentChatMessage? {
        val restoredRole =
            AgentChatRole.entries.firstOrNull { it.name == role }
                ?: return null
        return AgentChatMessage(
            role = restoredRole,
            text = text,
            isError = isError,
            tokenUsage = tokenUsage,
        )
    }

    companion object {
        fun fromMessage(message: AgentChatMessage): StoredAgentChatMessage =
            StoredAgentChatMessage(
                role = message.role.name,
                text = message.text,
                isError = message.isError,
                tokenUsage = message.tokenUsage,
            )
    }
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AgentChatStorageDispatcher

@Module
@InstallIn(SingletonComponent::class)
interface AgentChatHistoryBindings {
    @Binds
    @Singleton
    fun bindAgentChatHistoryStore(store: JsonAgentChatHistoryStore): AgentChatHistoryStore

    @Binds
    @Singleton
    fun bindAgentChatLongTermMemoryStore(store: MarkdownAgentChatLongTermMemoryStore): AgentChatLongTermMemoryStore
}

@Module
@InstallIn(SingletonComponent::class)
object AgentChatHistoryModule {
    @Provides
    @AgentChatStorageDispatcher
    fun provideAgentChatStorageDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
