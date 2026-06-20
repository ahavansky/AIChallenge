package com.akhavanskii.aichallenge.feature.agentchat

import com.akhavanskii.aichallenge.core.network.AgentMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.Locale

const val DEFAULT_LONG_TERM_MEMORY_FILE_NAME = "agent_chat_memory.md"

val DEFAULT_LONG_TERM_MEMORY_MARKDOWN: String =
    """
    # User Profile

    # Preferences

    # Project Decisions

    # Reusable Knowledge

    # Invariants
    """.trimIndent()

@Serializable
data class AgentChatMemorySnapshot(
    val taskContext: AgentChatTaskContext = AgentChatTaskContext(),
    val taskState: AgentTaskState = AgentTaskState(),
    @Transient val longTermMarkdown: AgentChatLongTermMarkdown = AgentChatLongTermMarkdown(),
    val lastRequest: AgentChatMemoryRequestContext? = null,
) {
    fun withTaskContext(taskContext: AgentChatTaskContext): AgentChatMemorySnapshot = copy(taskContext = taskContext, lastRequest = null)

    fun withTaskState(taskState: AgentTaskState): AgentChatMemorySnapshot = copy(taskState = taskState)

    fun withLongTermMarkdown(longTermMarkdown: AgentChatLongTermMarkdown): AgentChatMemorySnapshot =
        copy(longTermMarkdown = longTermMarkdown, lastRequest = null)

    fun withLastRequest(context: AgentChatMemoryRequestContext): AgentChatMemorySnapshot = copy(lastRequest = context)

    fun clearTaskContext(): AgentChatMemorySnapshot =
        copy(
            taskContext = AgentChatTaskContext(),
            lastRequest = null,
        )

    fun restoreInterruptedTask(): AgentChatMemorySnapshot = copy(taskState = taskState.restoreForColdStart())
}

@Serializable
data class AgentChatTaskContext(
    val goal: String = "",
    val stage: String = "",
    val approvedPlan: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val openQuestions: List<String> = emptyList(),
    val intermediateResults: List<String> = emptyList(),
) {
    val itemCount: Int
        get() =
            listOf(goal, stage).count { it.isNotBlank() } +
                approvedPlan.size +
                constraints.size +
                openQuestions.size +
                intermediateResults.size

    fun toPromptBlockOrNull(maxChars: Int): String? {
        if (itemCount == 0) return null
        return buildString {
            appendLine("TaskContext for the current task. Prefer this over older chat history when it conflicts.")
            appendMemoryLine("Goal", goal)
            appendMemoryLine("Stage", stage)
            appendLines("Approved plan", approvedPlan)
            appendLines("Task constraint", constraints)
            appendLines("Open question", openQuestions)
            appendLines("Intermediate result", intermediateResults)
        }.trim().takePromptChars(maxChars)
    }

    fun toEditableText(): String =
        buildString {
            appendLine("Goal: $goal")
            appendLine("Stage: $stage")
            approvedPlan.ifEmpty { listOf("") }.forEach { appendLine("Plan: $it") }
            constraints.ifEmpty { listOf("") }.forEach { appendLine("Constraint: $it") }
            openQuestions.ifEmpty { listOf("") }.forEach { appendLine("Open question: $it") }
            intermediateResults.ifEmpty { listOf("") }.forEach { appendLine("Result: $it") }
        }.trimEnd()

    companion object {
        fun fromEditableText(text: String): AgentChatTaskContext {
            var goal = ""
            var stage = ""
            var approvedPlan = emptyList<String>()
            var constraints = emptyList<String>()
            var openQuestions = emptyList<String>()
            var intermediateResults = emptyList<String>()

            text
                .lineSequence()
                .mapNotNull { line -> line.toMemoryStatementOrNull() }
                .forEach { statement ->
                    when (statement.key) {
                        "goal", "цель", "task", "задача" -> goal = statement.value
                        "stage", "этап", "state", "стадия" -> stage = statement.value
                        "plan", "план", "approved_plan", "утвержденный_план" ->
                            approvedPlan = approvedPlan.appendUnique(statement.value)
                        "constraint", "constraints", "ограничение", "ограничения" ->
                            constraints = constraints.appendUnique(statement.value)
                        "open_question", "question", "вопрос", "открытый_вопрос" ->
                            openQuestions = openQuestions.appendUnique(statement.value)
                        "result", "результат", "intermediate_result", "промежуточный_результат" ->
                            intermediateResults = intermediateResults.appendUnique(statement.value)
                    }
                }

            return AgentChatTaskContext(
                goal = goal,
                stage = stage,
                approvedPlan = approvedPlan,
                constraints = constraints,
                openQuestions = openQuestions,
                intermediateResults = intermediateResults,
            )
        }
    }
}

@Serializable
data class AgentChatLongTermMarkdown(
    val fileName: String = DEFAULT_LONG_TERM_MEMORY_FILE_NAME,
    val markdown: String = DEFAULT_LONG_TERM_MEMORY_MARKDOWN,
) {
    val meaningfulMarkdown: String
        get() = markdown.trim()

    val meaningfulCharCount: Int
        get() = if (hasMeaningfulContent) meaningfulMarkdown.length else 0

    val hasMeaningfulContent: Boolean
        get() =
            markdown
                .lineSequence()
                .map { it.trim() }
                .any { line -> line.isNotBlank() && !line.startsWith("#") }

    fun toPromptBlockOrNull(maxChars: Int): String? {
        if (!hasMeaningfulContent) return null
        return buildString {
            appendLine("Long-term memory from user-editable $fileName.")
            appendLine("Use it as durable profile, preferences, decisions, reusable knowledge, and invariants.")
            appendLine(meaningfulMarkdown)
        }.trim()
            .takePromptChars(maxChars)
    }
}

@Serializable
data class AgentChatMemorySelection(
    val includeChatHistory: Boolean = true,
    val includeInvariants: Boolean = true,
    val includeTaskState: Boolean = true,
    val includeTaskContext: Boolean = true,
    val includeLongTermMarkdown: Boolean = true,
)

@Serializable
data class AgentChatMemoryBudget(
    val chatHistoryMaxMessages: Int = DEFAULT_CHAT_HISTORY_MAX_MESSAGES,
    val invariantsMaxChars: Int = DEFAULT_INVARIANTS_MAX_CHARS,
    val taskStateMaxChars: Int = DEFAULT_TASK_STATE_MAX_CHARS,
    val taskContextMaxChars: Int = DEFAULT_TASK_CONTEXT_MAX_CHARS,
    val longTermMarkdownMaxChars: Int = DEFAULT_LONG_TERM_MARKDOWN_MAX_CHARS,
)

@Serializable
data class AgentChatMemoryRequestContext(
    val selection: AgentChatMemorySelection = AgentChatMemorySelection(),
    val budget: AgentChatMemoryBudget = AgentChatMemoryBudget(),
    val includedLayers: List<AgentChatMemoryLayer> = emptyList(),
    val chatHistoryMessageCount: Int = 0,
    val invariantCount: Int = 0,
    val hardInvariantCount: Int = 0,
    val taskStateArtifactCount: Int = 0,
    val taskContextItemCount: Int = 0,
    val longTermMarkdownChars: Int = 0,
    val systemInstructionChars: Int = 0,
    val activeProfileTitle: String = "",
    @Transient val promptPreview: String = "",
)

@Serializable
enum class AgentChatMemoryLayer(
    val title: String,
) {
    USER_PROFILE("User profile"),
    INVARIANTS("Invariants"),
    SHORT_TERM("Short-term"),
    TASK_STATE("Task state"),
    TASK_CONTEXT("TaskContext"),
    LONG_TERM_MARKDOWN("Long-term markdown"),
}

data class AgentChatPreparedPrompt(
    val systemInstruction: String? = null,
    val messages: List<AgentMessage>,
    val requestContext: AgentChatMemoryRequestContext,
)

object AgentChatMemoryPromptBuilder {
    fun build(
        latestUserMessage: String,
        chatMessages: List<AgentChatMessage>,
        memory: AgentChatMemorySnapshot,
        invariants: AgentChatInvariantSet = AgentChatInvariantSet(),
        userProfile: AgentChatUserProfile? = null,
        selection: AgentChatMemorySelection = AgentChatMemorySelection(),
        budget: AgentChatMemoryBudget = AgentChatMemoryBudget(),
        taskStage: AgentTaskStage? = null,
    ): AgentChatPreparedPrompt {
        val messages = mutableListOf<AgentMessage>()
        val includedLayers = mutableListOf<AgentChatMemoryLayer>()
        val systemInstruction =
            userProfile?.let { profile ->
                AgentChatInstructionBuilder.buildSystemInstruction(
                    profile = profile,
                    invariants = invariants,
                    taskStage = taskStage,
                )
            }
        if (userProfile?.hasMeaningfulContent == true) {
            includedLayers += AgentChatMemoryLayer.USER_PROFILE
        }

        if (selection.includeInvariants) {
            invariants.toPromptBlockOrNull(budget.invariantsMaxChars)?.let { promptBlock ->
                messages += AgentMessage.User(promptBlock)
                includedLayers += AgentChatMemoryLayer.INVARIANTS
            }
        }

        if (selection.includeTaskState) {
            memory.taskState.toPromptBlockOrNull(budget.taskStateMaxChars)?.let { promptBlock ->
                messages += AgentMessage.User(promptBlock)
                includedLayers += AgentChatMemoryLayer.TASK_STATE
            }
        }

        var longTermMarkdownChars = 0
        if (selection.includeLongTermMarkdown) {
            memory.longTermMarkdown.toPromptBlockOrNull(budget.longTermMarkdownMaxChars)?.let { promptBlock ->
                messages += AgentMessage.User(promptBlock)
                includedLayers += AgentChatMemoryLayer.LONG_TERM_MARKDOWN
                longTermMarkdownChars = promptBlock.length
            }
        }

        if (selection.includeTaskContext) {
            memory.taskContext.toPromptBlockOrNull(budget.taskContextMaxChars)?.let { promptBlock ->
                messages += AgentMessage.User(promptBlock)
                includedLayers += AgentChatMemoryLayer.TASK_CONTEXT
            }
        }

        val shortTermMessages =
            if (selection.includeChatHistory) {
                chatMessages.toShortTermPromptMessages(budget.chatHistoryMaxMessages)
            } else {
                emptyList()
            }
        if (shortTermMessages.isNotEmpty()) {
            includedLayers += AgentChatMemoryLayer.SHORT_TERM
            messages += shortTermMessages
        }

        messages += AgentMessage.User(latestUserMessage)

        return AgentChatPreparedPrompt(
            systemInstruction = systemInstruction,
            messages = messages,
            requestContext =
                AgentChatMemoryRequestContext(
                    selection = selection,
                    budget = budget,
                    includedLayers = includedLayers,
                    chatHistoryMessageCount = shortTermMessages.size,
                    invariantCount = if (selection.includeInvariants) invariants.invariants.size else 0,
                    hardInvariantCount = if (selection.includeInvariants) invariants.hardCount else 0,
                    taskStateArtifactCount = if (selection.includeTaskState) memory.taskState.artifacts.size else 0,
                    taskContextItemCount = if (selection.includeTaskContext) memory.taskContext.itemCount else 0,
                    longTermMarkdownChars = longTermMarkdownChars,
                    systemInstructionChars = systemInstruction?.length ?: 0,
                    activeProfileTitle = userProfile?.title.orEmpty(),
                    promptPreview = messages.toPromptPreview(systemInstruction = systemInstruction),
                ),
        )
    }
}

fun List<AgentChatMessage>.toShortTermPromptMessages(maxMessages: Int = DEFAULT_CHAT_HISTORY_MAX_MESSAGES): List<AgentMessage> {
    val agentMessages = mutableListOf<AgentMessage>()
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
                agentMessages += AgentMessage.User(userMessage.text)
                agentMessages += AgentMessage.Model(message.text)
                pendingUserMessage = null
            }
        }
    }

    return agentMessages.takeLast(maxMessages.coerceAtLeast(0))
}

fun AgentChatMemorySnapshot.formatDebugDetails(
    chatMessages: List<AgentChatMessage>,
    activeProfile: AgentChatUserProfile,
    budget: AgentChatMemoryBudget = AgentChatMemoryBudget(),
): String =
    buildString {
        if (activeProfile.hasMeaningfulContent) {
            appendLayerHeader("User profile", activeProfile.itemCount, "items")
            appendLine("- Source: ${activeProfile.title}")
            appendMemoryLine("Role", activeProfile.role)
            appendMemoryLine("Expertise", activeProfile.expertiseLevel)
            appendMemoryLines("Style", activeProfile.stylePreferences.take(1))
            appendMemoryLines("Format", activeProfile.formatPreferences.take(1))
            appendMemoryLines("Constraint", activeProfile.constraints.take(1))
        }

        if (taskState.hasActiveTask) {
            appendLayerHeader("Task state", taskState.artifacts.size, "artifacts")
            appendLine("- Source: formal state machine")
            appendMemoryLine("Stage", taskState.stage.title)
            appendMemoryLine("Step", taskState.step.title)
            appendMemoryLine("Status", taskState.status.title)
            appendMemoryLine("Expected", taskState.expectedActionTitle)
            appendMemoryLines(
                "Branch",
                taskState.branches.map { branch -> "${branch.id.title}: ${branch.status.title}" },
            )
        }

        val shortTermMessages = chatMessages.toShortTermPromptMessages(budget.chatHistoryMaxMessages)
        if (shortTermMessages.isNotEmpty()) {
            appendLayerHeader("Short-term", shortTermMessages.size, "messages")
            appendLine("- Source: chat history DB")
            appendAgentMessages(shortTermMessages)
        }

        if (taskContext.itemCount > 0) {
            appendLayerHeader("Working", taskContext.itemCount, "items")
            appendLine("- Source: TaskContext")
            appendMemoryLine("Goal", taskContext.goal)
            appendMemoryLine("Stage", taskContext.stage)
            appendMemoryLines("Plan", taskContext.approvedPlan)
            appendMemoryLines("Constraint", taskContext.constraints)
            appendMemoryLines("Open question", taskContext.openQuestions)
            appendMemoryLines("Result", taskContext.intermediateResults)
        }

        if (longTermMarkdown.hasMeaningfulContent) {
            appendLayerHeader("Long-term", longTermMarkdown.meaningfulCharCount, "chars")
            appendLine("- Source: ${longTermMarkdown.fileName}")
            appendMemoryLine("Markdown", longTermMarkdown.meaningfulMarkdown)
        }

        lastRequest?.let { request ->
            val layers =
                request.includedLayers
                    .joinToString(separator = ", ") { it.title }
                    .ifBlank { "latest user message only" }
            appendLine("Prompt context: $layers")
            append(
                "Counts: short=${request.chatHistoryMessageCount}, " +
                    "invariants=${request.invariantCount}/${request.hardInvariantCount} hard, " +
                    "taskState=${request.taskStateArtifactCount}, task=${request.taskContextItemCount}, " +
                    "longChars=${request.longTermMarkdownChars}, " +
                    "systemChars=${request.systemInstructionChars}",
            )
        }

        if (isBlank()) {
            append("Prompt context: latest user message only")
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

private fun StringBuilder.appendAgentMessages(messages: List<AgentMessage>) {
    if (messages.isEmpty()) {
        appendLine("- Empty")
        return
    }
    messages.take(DEBUG_LAYER_ITEM_LIMIT).forEach { message ->
        val role =
            when (message) {
                is AgentMessage.User -> "User"
                is AgentMessage.Model -> "Model"
            }
        appendLine("- $role: ${message.text.compactDebugValue()}")
    }
    appendOverflowCount(messages.size)
}

private fun StringBuilder.appendMemoryLine(
    label: String,
    value: String,
) {
    if (value.isNotBlank()) {
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

private fun StringBuilder.appendLines(
    title: String,
    values: List<String>,
) {
    values.forEach { value ->
        appendLine("- $title: $value")
    }
}

private fun String.toMemoryStatementOrNull(): MemoryStatement? {
    val separatorIndex = indexOfFirst { it == ':' || it == '=' }
    if (separatorIndex !in 1..40) return null
    val key = take(separatorIndex).normalizedMemoryKey()
    val value = drop(separatorIndex + 1).cleanMemoryValue()
    return value.takeIf { it.isNotBlank() }?.let { MemoryStatement(key = key, value = it) }
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

private fun List<AgentMessage>.toPromptPreview(systemInstruction: String?): String =
    buildString {
        systemInstruction?.let { instruction ->
            appendLine("[system]")
            appendLine(instruction)
            appendLine()
        }
        append(
            joinToString(separator = "\n\n") { message ->
                val role =
                    when (message) {
                        is AgentMessage.User -> "user"
                        is AgentMessage.Model -> "model"
                    }
                "[$role]\n${message.text}"
            },
        )
    }.take(MAX_PROMPT_PREVIEW_LENGTH)

private fun String.takePromptChars(maxChars: Int): String = take(maxChars.coerceAtLeast(0))

private const val DEFAULT_CHAT_HISTORY_MAX_MESSAGES = 12
private const val DEFAULT_INVARIANTS_MAX_CHARS = 2_000
private const val DEFAULT_TASK_STATE_MAX_CHARS = 2_000
private const val DEFAULT_TASK_CONTEXT_MAX_CHARS = 1_200
private const val DEFAULT_LONG_TERM_MARKDOWN_MAX_CHARS = 2_000
private const val MAX_MEMORY_ITEMS = 12
private const val MAX_MEMORY_VALUE_LENGTH = 220
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
