package com.akhavanskii.aichallenge.feature.agentchat

import com.akhavanskii.aichallenge.core.network.AgentMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChatMemoryTest {
    @Test
    fun shortTermMemoryKeepsOnlySuccessfulConversationTurns() {
        val memory =
            listOf(
                AgentChatMessage(role = AgentChatRole.USER, text = "First"),
                AgentChatMessage(role = AgentChatRole.MODEL, text = "Answer"),
                AgentChatMessage(role = AgentChatRole.USER, text = "Failed"),
                AgentChatMessage(role = AgentChatRole.MODEL, text = "Network error", isError = true),
                AgentChatMessage(role = AgentChatRole.USER, text = "Pending"),
                AgentChatMessage(role = AgentChatRole.MODEL, text = "Waiting", isLoading = true),
            ).toShortTermMemory()

        assertEquals(
            listOf(
                AgentChatMemoryEntry(role = AgentChatMemoryRole.USER, text = "First"),
                AgentChatMemoryEntry(role = AgentChatMemoryRole.MODEL, text = "Answer"),
            ),
            memory.entries,
        )
    }

    @Test
    fun userInputIsSavedIntoWorkingAndLongTermLayersSeparately() {
        val memory =
            AgentChatMemorySnapshot()
                .recordUserInput(
                    """
                    Goal: build a stateful assistant
                    Stage: planning
                    Constraint: no real network in tests
                    Result: memory model drafted
                    Profile: Android Kotlin developer
                    Preference: concise answers
                    Decision: keep memory in feature agent-chat
                    Knowledge: Gemini requests accept AgentMessage lists
                    Invariant: always keep secrets out of commits
                    """.trimIndent(),
                )

        assertEquals("build a stateful assistant", memory.working.goal)
        assertEquals("planning", memory.working.stage)
        assertEquals(listOf("no real network in tests"), memory.working.constraints)
        assertEquals(listOf("memory model drafted"), memory.working.intermediateResults)
        assertEquals(listOf("Android Kotlin developer"), memory.longTerm.profile)
        assertEquals(listOf("concise answers"), memory.longTerm.preferences)
        assertEquals(listOf("keep memory in feature agent-chat"), memory.longTerm.decisions)
        assertEquals(listOf("Gemini requests accept AgentMessage lists"), memory.longTerm.knowledge)
        assertEquals(listOf("always keep secrets out of commits"), memory.longTerm.invariants)
    }

    @Test
    fun promptBuilderIncludesOnlySelectedLayers() {
        val memory =
            AgentChatMemorySnapshot(
                shortTerm =
                    AgentChatShortTermMemory(
                        entries =
                            listOf(
                                AgentChatMemoryEntry(role = AgentChatMemoryRole.USER, text = "Earlier question"),
                                AgentChatMemoryEntry(role = AgentChatMemoryRole.MODEL, text = "Earlier answer"),
                            ),
                    ),
                working = AgentChatWorkingMemory(goal = "Ship memory layers"),
                longTerm = AgentChatLongTermMemory(preferences = listOf("Answer in Russian")),
            )

        val prepared =
            AgentChatMemoryPromptBuilder.build(
                latestUserMessage = "Continue",
                memory = memory,
                selection =
                    AgentChatMemorySelection(
                        includeShortTerm = false,
                        includeWorking = true,
                        includeLongTerm = true,
                    ),
            )

        assertEquals(
            listOf(
                AgentChatMemoryLayer.LONG_TERM,
                AgentChatMemoryLayer.WORKING,
            ),
            prepared.requestContext.includedLayers,
        )
        assertEquals(0, prepared.requestContext.shortTermMessageCount)
        assertEquals(1, prepared.requestContext.workingItemCount)
        assertEquals(1, prepared.requestContext.longTermItemCount)
        assertTrue((prepared.messages[0] as AgentMessage.User).text.contains("Long-term memory"))
        assertTrue((prepared.messages[1] as AgentMessage.User).text.contains("Working memory"))
        assertEquals(AgentMessage.User("Continue"), prepared.messages.last())
        assertFalse(prepared.messages.contains(AgentMessage.User("Earlier question")))
    }

    @Test
    fun promptBuilderShowsHowMemoryChangesPrompt() {
        val basePrompt =
            AgentChatMemoryPromptBuilder.build(
                latestUserMessage = "How should I answer?",
                memory = AgentChatMemorySnapshot(),
            )
        val personalizedPrompt =
            AgentChatMemoryPromptBuilder.build(
                latestUserMessage = "How should I answer?",
                memory =
                    AgentChatMemorySnapshot(
                        longTerm =
                            AgentChatLongTermMemory(
                                preferences = listOf("Prefer concise Kotlin-oriented answers"),
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
}
