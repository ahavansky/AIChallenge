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
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

const val DEFAULT_INVARIANTS_FILE_NAME = "agent_chat_invariants.md"

val DEFAULT_INVARIANTS_MARKDOWN: String =
    """
    # Invariants

    Invariant:
    Type:
    Severity: hard
    Rule:
    Reject:
    Reason:
    Alternative:
    """.trimIndent()

data class AgentChatInvariantSet(
    val fileName: String = DEFAULT_INVARIANTS_FILE_NAME,
    val markdown: String = DEFAULT_INVARIANTS_MARKDOWN,
) {
    val parseReport: AgentChatInvariantParseReport
        get() = AgentChatInvariantParser.parseReport(markdown)

    val invariants: List<AgentChatInvariant>
        get() = parseReport.invariants

    val hasMeaningfulContent: Boolean
        get() = invariants.isNotEmpty()

    val hardCount: Int
        get() = invariants.count { it.severity == AgentChatInvariantSeverity.HARD }

    val softCount: Int
        get() = invariants.count { it.severity == AgentChatInvariantSeverity.SOFT }

    val meaningfulCharCount: Int
        get() = if (hasMeaningfulContent) markdown.trim().length else 0

    fun toPromptBlockOrNull(maxChars: Int): String? {
        if (!hasMeaningfulContent) return null
        return buildString {
            appendLine("Active invariants from user-editable $fileName.")
            appendLine("Hard invariants are non-negotiable. If a request or draft conflicts with a hard invariant, refuse or rewrite it.")
            invariants.forEach { invariant ->
                appendLine()
                appendLine("- Invariant: ${invariant.title}")
                appendLine("  Type: ${invariant.type.ifBlank { "general" }}")
                appendLine("  Severity: ${invariant.severity.name.lowercase(Locale.ROOT)}")
                appendLine("  Rule: ${invariant.rule}")
                invariant.reject.forEach { rejected -> appendLine("  Reject: $rejected") }
                appendLine("  Reason: ${invariant.reason}")
                if (invariant.alternative.isNotBlank()) {
                    appendLine("  Alternative: ${invariant.alternative}")
                }
            }
        }.trim().take(maxChars.coerceAtLeast(0))
    }

    fun formatCompactDetails(): String =
        if (hasMeaningfulContent) {
            "Invariants: ${invariants.size} ($hardCount hard, $softCount soft)"
        } else {
            "Invariants: none"
        }
}

data class AgentChatInvariantParseReport(
    val invariants: List<AgentChatInvariant> = emptyList(),
    val ignoredBlocks: List<AgentChatIgnoredInvariantBlock> = emptyList(),
) {
    val ignoredCount: Int
        get() = ignoredBlocks.size
}

data class AgentChatIgnoredInvariantBlock(
    val title: String,
    val missingFields: List<String>,
)

data class AgentChatInvariant(
    val title: String,
    val type: String = "",
    val severity: AgentChatInvariantSeverity = AgentChatInvariantSeverity.HARD,
    val rule: String,
    val reject: List<String> = emptyList(),
    val reason: String = "",
    val alternative: String = "",
) {
    val isMeaningful: Boolean
        get() = title.isNotBlank() && rule.isNotBlank()
}

enum class AgentChatInvariantSeverity {
    HARD,
    SOFT,
}

data class AgentChatInvariantViolation(
    val invariant: AgentChatInvariant,
    val matchedText: String,
) {
    val isHard: Boolean
        get() = invariant.severity == AgentChatInvariantSeverity.HARD
}

data class AgentChatInvariantCheckResult(
    val violations: List<AgentChatInvariantViolation> = emptyList(),
) {
    val hardViolations: List<AgentChatInvariantViolation>
        get() = violations.filter { it.isHard }

    val hasHardViolations: Boolean
        get() = hardViolations.isNotEmpty()

    val hasViolations: Boolean
        get() = violations.isNotEmpty()
}

object AgentChatInvariantChecker {
    fun check(
        text: String,
        invariantSet: AgentChatInvariantSet,
    ): AgentChatInvariantCheckResult {
        if (text.isBlank() || !invariantSet.hasMeaningfulContent) return AgentChatInvariantCheckResult()

        val normalizedText = text.normalizedInvariantText()
        val violations =
            invariantSet.invariants.flatMap { invariant ->
                invariant.reject.mapNotNull { rejected ->
                    val normalizedRejected = rejected.normalizedInvariantText()
                    if (normalizedRejected.isBlank()) {
                        null
                    } else {
                        val matchIndex = normalizedText.indexOf(normalizedRejected)
                        if (matchIndex >= 0 && !normalizedText.hasProtectedMention(matchIndex, normalizedRejected.length)) {
                            AgentChatInvariantViolation(
                                invariant = invariant,
                                matchedText = rejected,
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        return AgentChatInvariantCheckResult(violations.distinctBy { violation -> violation.invariant.title to violation.matchedText })
    }

    fun formatRefusal(violations: List<AgentChatInvariantViolation>): String {
        val hardViolations = violations.filter { it.isHard }
        if (hardViolations.isEmpty()) return ""
        val firstViolation = hardViolations.first()
        return buildString {
            appendLine("I can't propose this solution because it violates invariant \"${firstViolation.invariant.title}\".")
            if (firstViolation.invariant.reason.isNotBlank()) {
                appendLine()
                appendLine("Reason: ${firstViolation.invariant.reason}")
            }
            appendLine()
            appendLine("Conflict: the request mentions \"${firstViolation.matchedText}\".")
            if (firstViolation.invariant.alternative.isNotBlank()) {
                appendLine()
                appendLine("Alternative: ${firstViolation.invariant.alternative}")
            }
        }.trim()
    }

    fun formatOutputFailure(violations: List<AgentChatInvariantViolation>): String {
        val hardViolations = violations.filter { it.isHard }
        if (hardViolations.isEmpty()) return ""
        return buildString {
            appendLine("Model output violated hard invariants and could not be repaired.")
            hardViolations.forEach { violation ->
                appendLine("- ${violation.invariant.title}: matched \"${violation.matchedText}\"")
            }
        }.trim()
    }

    fun buildRepairPrompt(
        violations: List<AgentChatInvariantViolation>,
        outputLabel: String,
    ): String =
        buildString {
            appendLine("The previous $outputLabel violated hard invariants.")
            appendLine("Rewrite it so it satisfies every active invariant.")
            appendLine("Do not recommend forbidden options as solutions.")
            appendLine("Return the corrected $outputLabel only.")
            appendLine()
            appendLine("Violations:")
            violations.filter { it.isHard }.forEach { violation ->
                appendLine("- ${violation.invariant.title}: matched \"${violation.matchedText}\".")
                if (violation.invariant.reason.isNotBlank()) {
                    appendLine("  Reason: ${violation.invariant.reason}")
                }
                if (violation.invariant.alternative.isNotBlank()) {
                    appendLine("  Alternative: ${violation.invariant.alternative}")
                }
            }
        }.trim()

    private fun String.hasProtectedMention(
        matchIndex: Int,
        matchLength: Int,
    ): Boolean {
        val windowStart = (matchIndex - PROTECTED_MENTION_WINDOW).coerceAtLeast(0)
        val windowEnd = (matchIndex + matchLength + PROTECTED_MENTION_WINDOW).coerceAtMost(length)
        val window = substring(windowStart, windowEnd)
        return PROTECTED_MENTION_MARKERS.any { marker -> window.contains(marker) }
    }

    private const val PROTECTED_MENTION_WINDOW = 48

    private val PROTECTED_MENTION_MARKERS =
        listOf(
            "do not use",
            "don't use",
            "don t use",
            "dont use",
            "avoid",
            "without",
            "not use",
            "no ",
            "forbidden",
            "rejected",
            "violates",
            "instead of",
            "must not",
            "never use",
            "не использ",
            "не нужно",
            "нельзя",
            "запрещ",
            "избег",
            "вместо",
            "наруш",
        )
}

object AgentChatInvariantParser {
    fun parse(markdown: String): List<AgentChatInvariant> = parseReport(markdown).invariants

    fun parseReport(markdown: String): AgentChatInvariantParseReport {
        val invariants = mutableListOf<AgentChatInvariant>()
        val ignoredBlocks = mutableListOf<AgentChatIgnoredInvariantBlock>()
        var builder: AgentChatInvariantBuilder? = null

        markdown
            .lineSequence()
            .mapNotNull { line -> line.toInvariantStatementOrNull() }
            .forEach { statement ->
                if (statement.key in INVARIANT_TITLE_KEYS) {
                    builder?.toParsedResult()?.let { result ->
                        result.invariant?.let { invariants += it }
                        result.ignoredBlock?.let { ignoredBlocks += it }
                    }
                    builder = AgentChatInvariantBuilder(title = statement.value)
                } else {
                    val currentBuilder = builder ?: return@forEach
                    when (statement.key) {
                        "type", "тип" -> currentBuilder.type = statement.value
                        "severity", "строгость", "важность" -> currentBuilder.severity = statement.value.toInvariantSeverity()
                        "rule", "правило" -> currentBuilder.rule = statement.value
                        "reject", "ban", "forbid", "запрет", "запрещено" ->
                            currentBuilder.reject = currentBuilder.reject.appendUniqueInvariantValue(statement.value)
                        "reason", "причина" -> currentBuilder.reason = statement.value
                        "alternative", "альтернатива" -> currentBuilder.alternative = statement.value
                    }
                }
            }

        builder?.toParsedResult()?.let { result ->
            result.invariant?.let { invariants += it }
            result.ignoredBlock?.let { ignoredBlocks += it }
        }
        return AgentChatInvariantParseReport(
            invariants = invariants.distinctBy { it.title.lowercase(Locale.ROOT) }.take(MAX_INVARIANTS),
            ignoredBlocks = ignoredBlocks.take(MAX_INVARIANTS),
        )
    }

    private val INVARIANT_TITLE_KEYS = setOf("invariant", "title", "инвариант", "название")
}

interface AgentChatInvariantStore {
    suspend fun load(): AgentChatInvariantSet

    suspend fun save(invariants: AgentChatInvariantSet)
}

class MarkdownAgentChatInvariantStore internal constructor(
    private val invariantFile: File,
    private val dispatcher: CoroutineDispatcher,
) : AgentChatInvariantStore {
    private val mutex = Mutex()

    @Inject
    constructor(
        @ApplicationContext context: Context,
        @AgentChatStorageDispatcher dispatcher: CoroutineDispatcher,
    ) : this(
        invariantFile = File(context.filesDir, DEFAULT_INVARIANTS_FILE_NAME),
        dispatcher = dispatcher,
    )

    override suspend fun load(): AgentChatInvariantSet =
        withContext(dispatcher) {
            mutex.withLock {
                val markdown =
                    if (invariantFile.exists()) {
                        runCatching { invariantFile.readText() }.getOrDefault(DEFAULT_INVARIANTS_MARKDOWN)
                    } else {
                        DEFAULT_INVARIANTS_MARKDOWN
                    }
                AgentChatInvariantSet(fileName = invariantFile.name, markdown = markdown)
            }
        }

    override suspend fun save(invariants: AgentChatInvariantSet) {
        withContext(dispatcher) {
            mutex.withLock {
                val parent = invariantFile.parentFile
                parent?.mkdirs()

                val tempFile = File(parent, "${invariantFile.name}.tmp")
                tempFile.writeText(invariants.markdown)
                if (!tempFile.renameTo(invariantFile)) {
                    invariantFile.writeText(invariants.markdown)
                    tempFile.delete()
                }
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
interface AgentChatInvariantBindings {
    @Binds
    @Singleton
    fun bindAgentChatInvariantStore(store: MarkdownAgentChatInvariantStore): AgentChatInvariantStore
}

private data class AgentChatInvariantBuilder(
    var title: String,
    var type: String = "",
    var severity: AgentChatInvariantSeverity = AgentChatInvariantSeverity.HARD,
    var rule: String = "",
    var reject: List<String> = emptyList(),
    var reason: String = "",
    var alternative: String = "",
) {
    fun toParsedResult(): AgentChatInvariantParseResult {
        val invariant =
            AgentChatInvariant(
                title = title.cleanInvariantValue(MAX_INVARIANT_TITLE_LENGTH),
                type = type.cleanInvariantValue(MAX_INVARIANT_TYPE_LENGTH),
                severity = severity,
                rule = rule.cleanInvariantValue(MAX_INVARIANT_VALUE_LENGTH),
                reject = reject.map { it.cleanInvariantValue(MAX_INVARIANT_VALUE_LENGTH) }.filter { it.isNotBlank() },
                reason = reason.cleanInvariantValue(MAX_INVARIANT_VALUE_LENGTH),
                alternative = alternative.cleanInvariantValue(MAX_INVARIANT_VALUE_LENGTH),
            )
        if (invariant.isMeaningful) {
            return AgentChatInvariantParseResult(invariant = invariant)
        }
        val missingFields =
            buildList {
                if (invariant.title.isBlank()) add("Invariant")
                if (invariant.rule.isBlank()) add("Rule")
            }
        return AgentChatInvariantParseResult(
            ignoredBlock =
                AgentChatIgnoredInvariantBlock(
                    title = invariant.title.ifBlank { "Untitled invariant" },
                    missingFields = missingFields,
                ),
        )
    }
}

private data class AgentChatInvariantParseResult(
    val invariant: AgentChatInvariant? = null,
    val ignoredBlock: AgentChatIgnoredInvariantBlock? = null,
)

private data class InvariantStatement(
    val key: String,
    val value: String,
)

private fun String.toInvariantStatementOrNull(): InvariantStatement? {
    val separatorIndex = indexOfFirst { it == ':' || it == '=' }
    if (separatorIndex !in 1..40) return null
    val key = take(separatorIndex).normalizedInvariantKey()
    val value = drop(separatorIndex + 1).cleanInvariantValue(MAX_INVARIANT_VALUE_LENGTH)
    return value.takeIf { it.isNotBlank() }?.let { InvariantStatement(key = key, value = it) }
}

private fun String.normalizedInvariantKey(): String =
    trim()
        .removePrefix("-")
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-zа-я0-9]+"), "_")
        .trim('_')

private fun String.toInvariantSeverity(): AgentChatInvariantSeverity =
    when (lowercase(Locale.ROOT).trim()) {
        "soft", "advisory", "warning", "мягкий", "рекомендация" -> AgentChatInvariantSeverity.SOFT
        else -> AgentChatInvariantSeverity.HARD
    }

private fun String.cleanInvariantValue(maxLength: Int): String = trim().replace(Regex("\\s+"), " ").take(maxLength)

private fun String.normalizedInvariantText(): String =
    lowercase(Locale.ROOT)
        .replace(Regex("[\\s\\p{Punct}]+"), " ")
        .trim()

private fun List<String>.appendUniqueInvariantValue(value: String): List<String> {
    val cleaned = value.cleanInvariantValue(MAX_INVARIANT_VALUE_LENGTH)
    if (cleaned.isBlank() || any { it.equals(cleaned, ignoreCase = true) }) return this
    return (this + cleaned).takeLast(MAX_REJECT_ITEMS)
}

private const val MAX_INVARIANTS = 20
private const val MAX_REJECT_ITEMS = 16
private const val MAX_INVARIANT_TITLE_LENGTH = 80
private const val MAX_INVARIANT_TYPE_LENGTH = 60
private const val MAX_INVARIANT_VALUE_LENGTH = 260
