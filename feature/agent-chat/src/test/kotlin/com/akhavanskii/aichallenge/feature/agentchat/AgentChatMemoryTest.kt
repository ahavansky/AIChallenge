package com.akhavanskii.aichallenge.feature.agentchat

import com.akhavanskii.aichallenge.core.network.AgentMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChatMemoryTest {
    @Test
    fun shortTermMemoryIsSelectedFromSuccessfulChatHistory() {
        val messages =
            listOf(
                AgentChatMessage(role = AgentChatRole.USER, text = "First"),
                AgentChatMessage(role = AgentChatRole.MODEL, text = "Answer"),
                AgentChatMessage(role = AgentChatRole.USER, text = "Failed"),
                AgentChatMessage(role = AgentChatRole.MODEL, text = "Network error", isError = true),
                AgentChatMessage(role = AgentChatRole.USER, text = "Pending"),
                AgentChatMessage(role = AgentChatRole.MODEL, text = "Waiting", isLoading = true),
                AgentChatMessage(role = AgentChatRole.USER, text = "Second"),
                AgentChatMessage(role = AgentChatRole.MODEL, text = "Answer 2"),
            )

        assertEquals(
            listOf(
                AgentMessage.User("First"),
                AgentMessage.Model("Answer"),
                AgentMessage.User("Second"),
                AgentMessage.Model("Answer 2"),
            ),
            messages.toShortTermPromptMessages(),
        )
        assertEquals(
            listOf(
                AgentMessage.User("Second"),
                AgentMessage.Model("Answer 2"),
            ),
            messages.toShortTermPromptMessages(maxMessages = 2),
        )
    }

    @Test
    fun taskContextIsParsedFromExplicitEditableText() {
        val taskContext =
            AgentChatTaskContext.fromEditableText(
                """
                Goal: build a memory layer demo
                Stage: implementation
                Plan: replace short-term snapshot with chat history
                Constraint: tests must stay offline
                Open question: how much markdown should fit into prompt
                Result: prompt builder has memory selection
                Preference: this line is not TaskContext
                """.trimIndent(),
            )

        assertEquals("build a memory layer demo", taskContext.goal)
        assertEquals("implementation", taskContext.stage)
        assertEquals(listOf("replace short-term snapshot with chat history"), taskContext.approvedPlan)
        assertEquals(listOf("tests must stay offline"), taskContext.constraints)
        assertEquals(listOf("how much markdown should fit into prompt"), taskContext.openQuestions)
        assertEquals(listOf("prompt builder has memory selection"), taskContext.intermediateResults)
        assertEquals(6, taskContext.itemCount)
    }

    @Test
    fun promptBuilderIncludesOnlySelectedSources() {
        val prepared =
            AgentChatMemoryPromptBuilder.build(
                latestUserMessage = "Continue",
                chatMessages =
                    listOf(
                        AgentChatMessage(role = AgentChatRole.USER, text = "Earlier question"),
                        AgentChatMessage(role = AgentChatRole.MODEL, text = "Earlier answer"),
                    ),
                memory =
                    AgentChatMemorySnapshot(
                        taskContext = AgentChatTaskContext(goal = "Ship memory layers"),
                        longTermMarkdown =
                            AgentChatLongTermMarkdown(
                                markdown =
                                    """
                                    # Preferences

                                    - Answer in Russian
                                    """.trimIndent(),
                            ),
                    ),
                selection =
                    AgentChatMemorySelection(
                        includeChatHistory = false,
                        includeTaskContext = true,
                        includeLongTermMarkdown = true,
                    ),
            )

        assertEquals(
            listOf(
                AgentChatMemoryLayer.LONG_TERM_MARKDOWN,
                AgentChatMemoryLayer.TASK_CONTEXT,
            ),
            prepared.requestContext.includedLayers,
        )
        assertEquals(0, prepared.requestContext.chatHistoryMessageCount)
        assertEquals(1, prepared.requestContext.taskContextItemCount)
        assertTrue(prepared.requestContext.longTermMarkdownChars > 0)
        assertTrue((prepared.messages[0] as AgentMessage.User).text.contains("memory.md"))
        assertTrue((prepared.messages[1] as AgentMessage.User).text.contains("TaskContext"))
        assertEquals(AgentMessage.User("Continue"), prepared.messages.last())
        assertFalse(prepared.messages.contains(AgentMessage.User("Earlier question")))
    }

    @Test
    fun promptBuilderKeepsUserProfileInSystemInstruction() {
        val prepared =
            AgentChatMemoryPromptBuilder.build(
                latestUserMessage = "Explain the architecture",
                chatMessages = emptyList(),
                memory = AgentChatMemorySnapshot(),
                userProfile = AgentChatProfileCatalog.defaults.first { it.id == PRODUCT_MANAGER_PROFILE_ID },
            )

        assertEquals(listOf(AgentChatMemoryLayer.USER_PROFILE), prepared.requestContext.includedLayers)
        assertTrue(prepared.systemInstruction?.contains("Product manager") == true)
        assertTrue(prepared.systemInstruction?.contains("Avoid code unless explicitly requested") == true)
        assertEquals(listOf(AgentMessage.User("Explain the architecture")), prepared.messages)
        assertTrue(prepared.requestContext.promptPreview.contains("[system]"))
    }

    @Test
    fun promptBuilderIncludesFormalTaskStateBeforeTaskContext() {
        val taskState =
            AgentTaskStateMachine
                .reduce(
                    state = AgentTaskState(),
                    event = AgentTaskEvent.Start(taskId = "task-1", prompt = "Build feature"),
                ).state
        val prepared =
            AgentChatMemoryPromptBuilder.build(
                latestUserMessage = "Continue",
                chatMessages = emptyList(),
                memory =
                    AgentChatMemorySnapshot(
                        taskState = taskState,
                        taskContext = AgentChatTaskContext(stage = "legacy editable stage"),
                    ),
            )

        assertEquals(
            listOf(
                AgentChatMemoryLayer.TASK_STATE,
                AgentChatMemoryLayer.TASK_CONTEXT,
            ),
            prepared.requestContext.includedLayers,
        )
        assertTrue((prepared.messages[0] as AgentMessage.User).text.contains("Formal task state"))
        assertTrue((prepared.messages[1] as AgentMessage.User).text.contains("TaskContext"))
    }

    @Test
    fun promptBuilderShowsHowSourcesChangePrompt() {
        val basePrompt =
            AgentChatMemoryPromptBuilder.build(
                latestUserMessage = "How should I answer?",
                chatMessages = emptyList(),
                memory = AgentChatMemorySnapshot(),
            )
        val personalizedPrompt =
            AgentChatMemoryPromptBuilder.build(
                latestUserMessage = "How should I answer?",
                chatMessages = emptyList(),
                memory =
                    AgentChatMemorySnapshot(
                        longTermMarkdown =
                            AgentChatLongTermMarkdown(
                                markdown =
                                    """
                                    # Preferences

                                    - Prefer concise Kotlin-oriented answers.
                                    """.trimIndent(),
                            ),
                    ),
            )

        assertEquals(listOf(AgentMessage.User("How should I answer?")), basePrompt.messages)
        assertTrue(
            personalizedPrompt.messages.any { message ->
                message is AgentMessage.User && message.text.contains("Prefer concise Kotlin-oriented answers")
            },
        )
        assertTrue(personalizedPrompt.requestContext.promptPreview.contains("Long-term memory"))
    }

    @Test
    fun defaultMarkdownTemplateIsVisibleButNotSentToPrompt() {
        val prepared =
            AgentChatMemoryPromptBuilder.build(
                latestUserMessage = "Continue",
                chatMessages = emptyList(),
                memory = AgentChatMemorySnapshot(),
            )

        assertFalse(AgentChatLongTermMarkdown().hasMeaningfulContent)
        assertEquals(listOf(AgentMessage.User("Continue")), prepared.messages)
    }
}
