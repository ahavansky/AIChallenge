package com.akhavanskii.aichallenge.feature.agentchat

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

const val DEFAULT_PROFILE_FILE_NAME = "agent_chat_profiles.json"

@Serializable
data class AgentChatUserProfile(
    val id: String = CUSTOM_PROFILE_ID,
    val title: String = "Custom user",
    val role: String = "",
    val expertiseLevel: String = "",
    val stylePreferences: List<String> = emptyList(),
    val formatPreferences: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
) {
    val itemCount: Int
        get() =
            listOf(title, role, expertiseLevel).count { it.isNotBlank() } +
                stylePreferences.size +
                formatPreferences.size +
                constraints.size

    val hasMeaningfulContent: Boolean
        get() =
            role.isNotBlank() ||
                expertiseLevel.isNotBlank() ||
                stylePreferences.isNotEmpty() ||
                formatPreferences.isNotEmpty() ||
                constraints.isNotEmpty()

    fun toEditableText(): String =
        buildString {
            appendLine("Title: $title")
            appendLine("Role: $role")
            appendLine("Expertise: $expertiseLevel")
            stylePreferences.ifEmpty { listOf("") }.forEach { appendLine("Style: $it") }
            formatPreferences.ifEmpty { listOf("") }.forEach { appendLine("Format: $it") }
            constraints.ifEmpty { listOf("") }.forEach { appendLine("Constraint: $it") }
        }.trimEnd()

    fun toSystemInstructionBlockOrNull(): String? {
        if (!hasMeaningfulContent) return null
        return buildString {
            appendLine("Active user profile: ${title.trim().ifBlank { id }}")
            appendMemoryLine("Role", role)
            appendMemoryLine("Expertise", expertiseLevel)
            appendLines("Style preference", stylePreferences)
            appendLines("Format preference", formatPreferences)
            appendLines("User constraint", constraints)
        }.trim()
    }

    companion object {
        fun fromEditableText(
            id: String,
            fallbackTitle: String,
            text: String,
        ): AgentChatUserProfile {
            var title = fallbackTitle
            var role = ""
            var expertiseLevel = ""
            var stylePreferences = emptyList<String>()
            var formatPreferences = emptyList<String>()
            var constraints = emptyList<String>()

            text
                .lineSequence()
                .mapNotNull { line -> line.toProfileStatementOrNull() }
                .forEach { statement ->
                    when (statement.key) {
                        "title", "name", "profile", "название", "имя", "профиль" -> title = statement.value
                        "role", "роль" -> role = statement.value
                        "expertise", "level", "уровень", "экспертиза" -> expertiseLevel = statement.value
                        "style", "стиль" -> stylePreferences = stylePreferences.appendUniqueProfileValue(statement.value)
                        "format", "формат" -> formatPreferences = formatPreferences.appendUniqueProfileValue(statement.value)
                        "constraint", "constraints", "ограничение", "ограничения" ->
                            constraints = constraints.appendUniqueProfileValue(statement.value)
                    }
                }

            return AgentChatUserProfile(
                id = id,
                title = title.ifBlank { fallbackTitle },
                role = role,
                expertiseLevel = expertiseLevel,
                stylePreferences = stylePreferences,
                formatPreferences = formatPreferences,
                constraints = constraints,
            )
        }
    }
}

@Serializable
data class AgentChatProfileSnapshot(
    val activeProfileId: String = SENIOR_KOTLIN_PROFILE_ID,
    val profiles: List<AgentChatUserProfile> = AgentChatProfileCatalog.defaults,
) {
    val normalized: AgentChatProfileSnapshot
        get() {
            val mergedProfiles =
                (profiles + AgentChatProfileCatalog.defaults)
                    .distinctBy { it.id }
                    .ifEmpty { AgentChatProfileCatalog.defaults }
            val normalizedActiveId =
                activeProfileId.takeIf { activeId -> mergedProfiles.any { it.id == activeId } }
                    ?: mergedProfiles.first().id
            return copy(activeProfileId = normalizedActiveId, profiles = mergedProfiles)
        }

    val activeProfile: AgentChatUserProfile
        get() = normalized.profiles.first { it.id == normalized.activeProfileId }

    fun withActiveProfile(profileId: String): AgentChatProfileSnapshot {
        val normalized = this.normalized
        val activeProfileId = profileId.takeIf { id -> normalized.profiles.any { it.id == id } } ?: normalized.activeProfileId
        return normalized.copy(activeProfileId = activeProfileId)
    }

    fun withUpdatedActiveProfile(profile: AgentChatUserProfile): AgentChatProfileSnapshot {
        val normalized = this.normalized
        return normalized.copy(
            profiles = normalized.profiles.map { existing -> if (existing.id == profile.id) profile else existing },
            activeProfileId = profile.id,
        )
    }
}

object AgentChatProfileCatalog {
    val defaults: List<AgentChatUserProfile> =
        listOf(
            AgentChatUserProfile(
                id = CUSTOM_PROFILE_ID,
                title = "Custom user",
                role = "App user configuring a personal AI assistant.",
                expertiseLevel = "Adjust explanations to the user's current request.",
                stylePreferences = listOf("Ask concise clarifying questions only when needed."),
                formatPreferences = listOf("Use a direct answer first, then short supporting details."),
                constraints = listOf("Respect project, task, and safety constraints over personal style."),
            ),
            AgentChatUserProfile(
                id = ANDROID_BEGINNER_PROFILE_ID,
                title = "Android beginner",
                role = "Junior Android learner.",
                expertiseLevel = "Beginner.",
                stylePreferences =
                    listOf(
                        "Explain step by step with minimal jargon.",
                        "Prefer practical examples over abstract architecture discussion.",
                    ),
                formatPreferences = listOf("Use short numbered steps and simple Kotlin snippets when code helps."),
                constraints = listOf("Do not assume deep knowledge of Gradle, Hilt, or Compose internals."),
            ),
            AgentChatUserProfile(
                id = SENIOR_KOTLIN_PROFILE_ID,
                title = "Senior Kotlin developer",
                role = "Experienced Android/Kotlin engineer.",
                expertiseLevel = "Senior.",
                stylePreferences =
                    listOf(
                        "Be concise and direct.",
                        "Call out trade-offs, failure modes, and test impact.",
                    ),
                formatPreferences = listOf("Prefer implementation-oriented bullets and Kotlin terminology."),
                constraints = listOf("Avoid generic architecture layers unless they solve real complexity."),
            ),
            AgentChatUserProfile(
                id = PRODUCT_MANAGER_PROFILE_ID,
                title = "Product manager",
                role = "Product manager evaluating assistant behavior.",
                expertiseLevel = "Non-implementation stakeholder.",
                stylePreferences =
                    listOf(
                        "Focus on user value, scope, risks, and acceptance criteria.",
                        "Avoid code unless explicitly requested.",
                    ),
                formatPreferences = listOf("Use concise product language and decision-ready summaries."),
                constraints = listOf("Translate technical details into user-facing impact."),
            ),
        )
}

interface AgentChatUserProfileStore {
    suspend fun load(): AgentChatProfileSnapshot

    suspend fun save(snapshot: AgentChatProfileSnapshot)
}

class JsonAgentChatUserProfileStore internal constructor(
    private val profileFile: File,
    private val json: Json,
    private val dispatcher: CoroutineDispatcher,
) : AgentChatUserProfileStore {
    private val mutex = Mutex()

    @Inject
    constructor(
        @ApplicationContext context: Context,
        json: Json,
        @AgentChatStorageDispatcher dispatcher: CoroutineDispatcher,
    ) : this(
        profileFile = File(context.filesDir, DEFAULT_PROFILE_FILE_NAME),
        json = json,
        dispatcher = dispatcher,
    )

    override suspend fun load(): AgentChatProfileSnapshot =
        withContext(dispatcher) {
            mutex.withLock {
                if (!profileFile.exists()) {
                    return@withLock AgentChatProfileSnapshot().normalized
                }

                runCatching {
                    json.decodeFromString<AgentChatProfileSnapshot>(profileFile.readText()).normalized
                }.getOrDefault(AgentChatProfileSnapshot().normalized)
            }
        }

    override suspend fun save(snapshot: AgentChatProfileSnapshot) {
        withContext(dispatcher) {
            mutex.withLock {
                val encoded = json.encodeToString(snapshot.normalized)
                val parent = profileFile.parentFile
                parent?.mkdirs()

                val tempFile = File(parent, "${profileFile.name}.tmp")
                tempFile.writeText(encoded)
                if (!tempFile.renameTo(profileFile)) {
                    profileFile.writeText(encoded)
                    tempFile.delete()
                }
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AgentChatUserProfileModule {
    @Binds
    @Singleton
    abstract fun bindAgentChatUserProfileStore(store: JsonAgentChatUserProfileStore): AgentChatUserProfileStore
}

private data class ProfileStatement(
    val key: String,
    val value: String,
)

private fun StringBuilder.appendMemoryLine(
    label: String,
    value: String,
) {
    if (value.isNotBlank()) {
        appendLine("- $label: ${value.trim()}")
    }
}

private fun StringBuilder.appendLines(
    title: String,
    values: List<String>,
) {
    values.forEach { value ->
        appendLine("- $title: $value")
    }
}

private fun String.toProfileStatementOrNull(): ProfileStatement? {
    val separatorIndex = indexOfFirst { it == ':' || it == '=' }
    if (separatorIndex !in 1..40) return null
    val key =
        take(separatorIndex)
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-zа-я0-9]+"), "_")
            .trim('_')
    val value = drop(separatorIndex + 1).cleanProfileValue()
    return value.takeIf { it.isNotBlank() }?.let { ProfileStatement(key = key, value = it) }
}

private fun String.cleanProfileValue(): String = trim().replace(Regex("\\s+"), " ").take(MAX_PROFILE_VALUE_LENGTH)

private fun List<String>.appendUniqueProfileValue(value: String): List<String> {
    val cleaned = value.cleanProfileValue()
    if (cleaned.isBlank() || any { it.equals(cleaned, ignoreCase = true) }) return this
    return (this + cleaned).takeLast(MAX_PROFILE_ITEMS)
}

const val CUSTOM_PROFILE_ID = "custom_user"
const val ANDROID_BEGINNER_PROFILE_ID = "android_beginner"
const val SENIOR_KOTLIN_PROFILE_ID = "senior_kotlin"
const val PRODUCT_MANAGER_PROFILE_ID = "product_manager"

private const val MAX_PROFILE_ITEMS = 12
private const val MAX_PROFILE_VALUE_LENGTH = 220
