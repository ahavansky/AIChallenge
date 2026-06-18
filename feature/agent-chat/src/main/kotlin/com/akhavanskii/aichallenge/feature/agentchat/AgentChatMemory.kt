package com.akhavanskii.aichallenge.feature.agentchat

import com.akhavanskii.aichallenge.core.network.AgentMessage
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class AgentChatMemorySnapshot(
    val shortTerm: AgentChatShortTermMemory = AgentChatShortTermMemory(),
    val working: AgentChatWorkingMemory = AgentChatWorkingMemory(),
    val longTerm: AgentChatLongTermMemory = AgentChatLongTermMemory(),
    val lastRequest: AgentChatMemoryRequestContext? = null,
) {
    fun recordUserInput(input: String): AgentChatMemorySnapshot =
        copy(
            working = working.recordUserInput(input),
            longTerm = longTerm.recordUserInput(input),
        )

    fun withShortTermFromMessages(messages: List<AgentChatMessage>): AgentChatMemorySnapshot =
        copy(shortTerm = messages.toShortTermMemory())

    fun withLastRequest(context: AgentChatMemoryRequestContext): AgentChatMemorySnapshot = copy(lastRequest = context)

    fun clearTaskMemory(): AgentChatMemorySnapshot =
        copy(
            shortTerm = AgentChatShortTermMemory(),
            working = AgentChatWorkingMemory(),
            lastRequest = null,
        )
}

@Serializable
data class AgentChatShortTermMemory(
    val entries: List<AgentChatMemoryEntry> = emptyList(),
) {
    val messageCount: Int
        get() = entries.size

    fun toAgentMessages(): List<AgentMessage> =
        entries.map { entry ->
            when (entry.role) {
                AgentChatMemoryRole.USER -> AgentMessage.User(entry.text)
                AgentChatMemoryRole.MODEL -> AgentMessage.Model(entry.text)
            }
        }
}

@Serializable
data class AgentChatMemoryEntry(
    val role: AgentChatMemoryRole,
    val text: String,
)

@Serializable
enum class AgentChatMemoryRole {
    USER,
    MODEL,
}

@Serializable
data class AgentChatWorkingMemory(
    val goal: String? = null,
    val stage: String? = null,
    val approvedPlan: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val intermediateResults: List<String> = emptyList(),
) {
    val itemCount: Int
        get() =
            listOfNotNull(goal, stage).size +
                approvedPlan.size +
                constraints.size +
                intermediateResults.size

    fun toPromptBlockOrNull(): String? {
        if (itemCount == 0) return null
        return buildString {
            appendLine("Working memory for the current task. Use it only for this active task.")
            goal?.let { appendLine("- Goal: $it") }
            stage?.let { appendLine("- Stage: $it") }
            appendLines("Approved plan", approvedPlan)
            appendLines("Task constraints", constraints)
            appendLines("Intermediate results", intermediateResults)
        }.trim()
    }

    fun recordUserInput(input: String): AgentChatWorkingMemory {
        var updated = this
        input.memoryStatements().forEach { statement ->
            updated =
                when (statement.key) {
                    "goal", "цель", "task", "задача" -> updated.copy(goal = statement.value)
                    "stage", "этап", "state", "стадия" -> updated.copy(stage = statement.value)
                    "plan", "план", "approved_plan", "утвержденный_план" ->
                        updated.copy(approvedPlan = updated.approvedPlan.appendUnique(statement.value))
                    "constraint", "constraints", "ограничение", "ограничения" ->
                        updated.copy(constraints = updated.constraints.appendUnique(statement.value))
                    "result", "результат", "intermediate_result", "промежуточный_результат" ->
                        updated.copy(intermediateResults = updated.intermediateResults.appendUnique(statement.value))
                    else -> updated
                }
        }
        input.memorySentences().forEach { sentence ->
            val lower = sentence.lowercase(Locale.ROOT)
            updated =
                when {
                    updated.goal == null && lower.containsAny("цель", "goal", "задача", "task") ->
                        updated.copy(goal = sentence)
                    lower.containsAny("огранич", "constraint", "must not", "нельзя") ->
                        updated.copy(constraints = updated.constraints.appendUnique(sentence))
                    lower.containsAny("результат", "result", "готово", "done") ->
                        updated.copy(intermediateResults = updated.intermediateResults.appendUnique(sentence))
                    else -> updated
                }
        }
        return updated
    }
}

@Serializable
data class AgentChatLongTermMemory(
    val profile: List<String> = emptyList(),
    val preferences: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val knowledge: List<String> = emptyList(),
    val invariants: List<String> = emptyList(),
) {
    val itemCount: Int
        get() = profile.size + preferences.size + decisions.size + knowledge.size + invariants.size

    fun toPromptBlockOrNull(): String? {
        if (itemCount == 0) return null
        return buildString {
            appendLine("Long-term memory. Treat these as durable profile, decisions, knowledge, and invariants.")
            appendLines("Profile", profile)
            appendLines("Preferences", preferences)
            appendLines("Decisions", decisions)
            appendLines("Knowledge", knowledge)
            appendLines("Invariants", invariants)
        }.trim()
    }

    fun recordUserInput(input: String): AgentChatLongTermMemory {
        var updated = this
        input.memoryStatements().forEach { statement ->
            updated =
                when (statement.key) {
                    "profile", "профиль" -> updated.copy(profile = updated.profile.appendUnique(statement.value))
                    "preference", "preferences", "предпочтение", "предпочтения" ->
                        updated.copy(preferences = updated.preferences.appendUnique(statement.value))
                    "decision", "decisions", "решение", "решения", "договоренность" ->
                        updated.copy(decisions = updated.decisions.appendUnique(statement.value))
                    "knowledge", "знание", "знания" ->
                        updated.copy(knowledge = updated.knowledge.appendUnique(statement.value))
                    "invariant", "invariants", "инвариант", "инварианты" ->
                        updated.copy(invariants = updated.invariants.appendUnique(statement.value))
                    else -> updated
                }
        }
        input.memorySentences().forEach { sentence ->
            val lower = sentence.lowercase(Locale.ROOT)
            updated =
                when {
                    lower.containsAny("профиль", "profile") ->
                        updated.copy(profile = updated.profile.appendUnique(sentence))
                    lower.containsAny("предпоч", "preference", "важно", "хочу") ->
                        updated.copy(preferences = updated.preferences.appendUnique(sentence))
                    lower.containsAny("решили", "решение", "договор", "decision") ->
                        updated.copy(decisions = updated.decisions.appendUnique(sentence))
                    lower.containsAny("знание", "knowledge", "запомни") ->
                        updated.copy(knowledge = updated.knowledge.appendUnique(sentence))
                    lower.containsAny("инвариант", "always", "всегда", "никогда") ->
                        updated.copy(invariants = updated.invariants.appendUnique(sentence))
                    else -> updated
                }
        }
        return updated
    }
}

@Serializable
data class AgentChatMemorySelection(
    val includeShortTerm: Boolean = true,
    val includeWorking: Boolean = true,
    val includeLongTerm: Boolean = true,
)

@Serializable
data class AgentChatMemoryRequestContext(
    val selection: AgentChatMemorySelection = AgentChatMemorySelection(),
    val includedLayers: List<AgentChatMemoryLayer> = emptyList(),
    val shortTermMessageCount: Int = 0,
    val workingItemCount: Int = 0,
    val longTermItemCount: Int = 0,
    val promptPreview: String = "",
)

@Serializable
enum class AgentChatMemoryLayer(
    val title: String,
) {
    SHORT_TERM("Short-term"),
    WORKING("Working"),
    LONG_TERM("Long-term"),
}

data class AgentChatPreparedPrompt(
    val messages: List<AgentMessage>,
    val requestContext: AgentChatMemoryRequestContext,
)

object AgentChatMemoryPromptBuilder {
    fun build(
        latestUserMessage: String,
        memory: AgentChatMemorySnapshot,
        selection: AgentChatMemorySelection = AgentChatMemorySelection(),
    ): AgentChatPreparedPrompt {
        val messages = mutableListOf<AgentMessage>()
        val includedLayers = mutableListOf<AgentChatMemoryLayer>()

        if (selection.includeLongTerm) {
            memory.longTerm.toPromptBlockOrNull()?.let { promptBlock ->
                messages += AgentMessage.User(promptBlock)
                includedLayers += AgentChatMemoryLayer.LONG_TERM
            }
        }

        if (selection.includeWorking) {
            memory.working.toPromptBlockOrNull()?.let { promptBlock ->
                messages += AgentMessage.User(promptBlock)
                includedLayers += AgentChatMemoryLayer.WORKING
            }
        }

        if (selection.includeShortTerm) {
            val shortTermMessages = memory.shortTerm.toAgentMessages()
            if (shortTermMessages.isNotEmpty()) {
                includedLayers += AgentChatMemoryLayer.SHORT_TERM
                messages += shortTermMessages
            }
        }

        messages += AgentMessage.User(latestUserMessage)

        return AgentChatPreparedPrompt(
            messages = messages,
            requestContext =
                AgentChatMemoryRequestContext(
                    selection = selection,
                    includedLayers = includedLayers,
                    shortTermMessageCount = if (selection.includeShortTerm) memory.shortTerm.messageCount else 0,
                    workingItemCount = if (selection.includeWorking) memory.working.itemCount else 0,
                    longTermItemCount = if (selection.includeLongTerm) memory.longTerm.itemCount else 0,
                    promptPreview = messages.toPromptPreview(),
                ),
        )
    }
}

fun List<AgentChatMessage>.toShortTermMemory(): AgentChatShortTermMemory {
    val entries = mutableListOf<AgentChatMemoryEntry>()
    var pendingUserMessage: AgentChatMessage? = null

    for (message in this) {
        if (message.isLoading || message.isError) {
            pendingUserMessage = null
            continue
        }

        when (message.role) {
            AgentChatRole.USER -> pendingUserMessage = message
            AgentChatRole.MODEL -> {
                val userMessage = pendingUserMessage ?: continue
                entries += AgentChatMemoryEntry(role = AgentChatMemoryRole.USER, text = userMessage.text)
                entries += AgentChatMemoryEntry(role = AgentChatMemoryRole.MODEL, text = message.text)
                pendingUserMessage = null
            }
        }
    }

    return AgentChatShortTermMemory(entries = entries.takeLast(MAX_SHORT_TERM_MESSAGES))
}

fun AgentChatMemorySnapshot.formatDebugSummary(): String =
    buildString {
        append("Short-term: ${shortTerm.messageCount} messages")
        append(" | Working: ${working.itemCount} items")
        append(" | Long-term: ${longTerm.itemCount} items")
        lastRequest?.let { request ->
            val layers =
                request.includedLayers
                    .joinToString(separator = ", ") { it.title }
                    .ifBlank { "latest user message only" }
            append("\nPrompt context: $layers")
            append(
                " | counts ${request.shortTermMessageCount}/" +
                    "${request.workingItemCount}/${request.longTermItemCount}",
            )
        }
    }

fun AgentChatMemorySnapshot.formatDebugDetails(): String =
    buildString {
        appendLayerHeader("Short-term", shortTerm.messageCount, "messages")
        appendMemoryEntries(shortTerm.entries)
        appendLayerHeader("Working", working.itemCount, "items")
        if (working.itemCount == 0) {
            appendLine("- Empty")
        }
        appendMemoryLine("Goal", working.goal)
        appendMemoryLine("Stage", working.stage)
        appendMemoryLines("Plan", working.approvedPlan)
        appendMemoryLines("Constraint", working.constraints)
        appendMemoryLines("Result", working.intermediateResults)
        appendLayerHeader("Long-term", longTerm.itemCount, "items")
        if (longTerm.itemCount == 0) {
            appendLine("- Empty")
        }
        appendMemoryLines("Profile", longTerm.profile)
        appendMemoryLines("Preference", longTerm.preferences)
        appendMemoryLines("Decision", longTerm.decisions)
        appendMemoryLines("Knowledge", longTerm.knowledge)
        appendMemoryLines("Invariant", longTerm.invariants)
        lastRequest?.let { request ->
            val layers =
                request.includedLayers
                    .joinToString(separator = ", ") { it.title }
                    .ifBlank { "latest user message only" }
            appendLine("Prompt context: $layers")
            append(
                "Counts: short=${request.shortTermMessageCount}, " +
                    "working=${request.workingItemCount}, long=${request.longTermItemCount}",
            )
        }
    }.trim()

private data class MemoryStatement(
    val key: String,
    val value: String,
)

private fun StringBuilder.appendLayerHeader(
    title: String,
    count: Int,
    unit: String,
) {
    appendLine("$title ($count $unit)")
}

private fun StringBuilder.appendMemoryEntries(entries: List<AgentChatMemoryEntry>) {
    if (entries.isEmpty()) {
        appendLine("- Empty")
        return
    }
    entries.take(DEBUG_LAYER_ITEM_LIMIT).forEach { entry ->
        val role =
            when (entry.role) {
                AgentChatMemoryRole.USER -> "User"
                AgentChatMemoryRole.MODEL -> "Model"
            }
        appendLine("- $role: ${entry.text.compactDebugValue()}")
    }
    appendOverflowCount(entries.size)
}

private fun StringBuilder.appendMemoryLine(
    label: String,
    value: String?,
) {
    if (!value.isNullOrBlank()) {
        appendLine("- $label: ${value.compactDebugValue()}")
    }
}

private fun StringBuilder.appendMemoryLines(
    label: String,
    values: List<String>,
) {
    values.take(DEBUG_LAYER_ITEM_LIMIT).forEach { value ->
        appendLine("- $label: ${value.compactDebugValue()}")
    }
    appendOverflowCount(values.size)
}

private fun StringBuilder.appendOverflowCount(size: Int) {
    val hidden = size - DEBUG_LAYER_ITEM_LIMIT
    if (hidden > 0) {
        appendLine("- +$hidden more")
    }
}

private fun String.memoryStatements(): List<MemoryStatement> =
    lineSequence()
        .mapNotNull { line ->
            val separatorIndex = line.indexOfFirst { it == ':' || it == '=' }
            if (separatorIndex !in 1..40) return@mapNotNull null
            val key = line.take(separatorIndex).normalizedMemoryKey()
            val value = line.drop(separatorIndex + 1).cleanMemoryValue()
            value.takeIf { it.isNotBlank() }?.let { MemoryStatement(key = key, value = it) }
        }.toList()

private fun String.memorySentences(): List<String> =
    lineSequence()
        .filterNot { it.hasMemoryStatementPrefix() }
        .flatMap { line -> line.split('.', ';') }
        .map { it.cleanMemoryValue() }
        .filter { it.length >= MIN_MEMORY_SENTENCE_LENGTH }
        .toList()

private fun String.hasMemoryStatementPrefix(): Boolean {
    val separatorIndex = indexOfFirst { it == ':' || it == '=' }
    return separatorIndex in 1..40
}

private fun String.normalizedMemoryKey(): String =
    trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-zа-я0-9]+"), "_")
        .trim('_')

private fun String.cleanMemoryValue(): String = trim().replace(Regex("\\s+"), " ").take(MAX_MEMORY_VALUE_LENGTH)

private fun List<String>.appendUnique(value: String): List<String> {
    val cleaned = value.cleanMemoryValue()
    if (cleaned.isBlank() || any { it.equals(cleaned, ignoreCase = true) }) return this
    return (this + cleaned).takeLast(MAX_MEMORY_ITEMS)
}

private fun StringBuilder.appendLines(
    title: String,
    values: List<String>,
) {
    values.forEach { value ->
        appendLine("- $title: $value")
    }
}

private fun List<AgentMessage>.toPromptPreview(): String =
    joinToString(separator = "\n\n") { message ->
        val role =
            when (message) {
                is AgentMessage.User -> "user"
                is AgentMessage.Model -> "model"
            }
        "[$role]\n${message.text}"
    }.take(MAX_PROMPT_PREVIEW_LENGTH)

private fun String.containsAny(vararg needles: String): Boolean = needles.any { needle -> contains(needle) }

private const val MAX_SHORT_TERM_MESSAGES = 12
private const val MAX_MEMORY_ITEMS = 12
private const val MAX_MEMORY_VALUE_LENGTH = 220
private const val MIN_MEMORY_SENTENCE_LENGTH = 8
private const val MAX_PROMPT_PREVIEW_LENGTH = 1_200
private const val MAX_DEBUG_VALUE_LENGTH = 96
private const val DEBUG_LAYER_ITEM_LIMIT = 4

private fun String.compactDebugValue(): String {
    val cleaned = trim().replace(Regex("\\s+"), " ")
    return if (cleaned.length <= MAX_DEBUG_VALUE_LENGTH) {
        cleaned
    } else {
        cleaned.take(MAX_DEBUG_VALUE_LENGTH - 3) + "..."
    }
}
