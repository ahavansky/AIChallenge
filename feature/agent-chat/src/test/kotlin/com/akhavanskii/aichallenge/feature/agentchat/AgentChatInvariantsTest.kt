package com.akhavanskii.aichallenge.feature.agentchat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentChatInvariantsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun parserReadsHardAndSoftInvariantsFromMarkdown() {
        val invariantSet =
            AgentChatInvariantSet(
                markdown =
                    """
                    # Invariants

                    Invariant: Android stack
                    Type: tech_stack
                    Severity: hard
                    Rule: Use Kotlin and Jetpack Compose.
                    Reject: React Native
                    Reject: RxJava
                    Reason: Stack is fixed.
                    Alternative: Use Kotlin/Compose.

                    Invariant: Concise style
                    Type: style
                    Severity: soft
                    Rule: Prefer concise answers.
                    Reject: long tutorial
                    Reason: User prefers short replies.
                    """.trimIndent(),
            )

        assertEquals(2, invariantSet.invariants.size)
        assertEquals(1, invariantSet.hardCount)
        assertEquals(1, invariantSet.softCount)
        assertEquals("Android stack", invariantSet.invariants[0].title)
        assertEquals(AgentChatInvariantSeverity.HARD, invariantSet.invariants[0].severity)
        assertEquals(listOf("React Native", "RxJava"), invariantSet.invariants[0].reject)
        assertEquals(AgentChatInvariantSeverity.SOFT, invariantSet.invariants[1].severity)
    }

    @Test
    fun checkerBlocksUnprotectedHardRejectPhrase() {
        val result =
            AgentChatInvariantChecker.check(
                text = "Please build it with React Native.",
                invariantSet = androidStackInvariants(),
            )

        assertTrue(result.hasHardViolations)
        assertEquals(
            "Android stack",
            result
                .hardViolations
                .first()
                .invariant
                .title,
        )
        assertTrue(
            AgentChatInvariantChecker
                .formatRefusal(result.hardViolations)
                .contains("Kotlin/Compose"),
        )
    }

    @Test
    fun parserReportsIgnoredBlocksWithMissingRule() {
        val report =
            AgentChatInvariantParser.parseReport(
                """
                # Invariants

                Invariant: Missing rule
                Reject: React Native
                Reason: incomplete block
                """.trimIndent(),
            )

        assertEquals(0, report.invariants.size)
        assertEquals(1, report.ignoredCount)
        assertEquals("Missing rule", report.ignoredBlocks.first().title)
        assertEquals(listOf("Rule"), report.ignoredBlocks.first().missingFields)
    }

    @Test
    fun checkerAllowsProtectedRejectMention() {
        val result =
            AgentChatInvariantChecker.check(
                text = "Do not use React Native. Use Kotlin and Jetpack Compose instead.",
                invariantSet = androidStackInvariants(),
            )

        assertFalse(result.hasHardViolations)
    }

    @Test
    fun invariantStorePersistsMarkdownSeparately() =
        runTest {
            val file = File(temporaryFolder.root, DEFAULT_INVARIANTS_FILE_NAME)
            val store =
                MarkdownAgentChatInvariantStore(
                    invariantFile = file,
                    dispatcher = Dispatchers.Unconfined,
                )
            val invariants = androidStackInvariants()

            store.save(invariants)

            assertEquals(invariants.copy(fileName = DEFAULT_INVARIANTS_FILE_NAME), store.load())
            assertEquals(invariants.markdown, file.readText())
        }

    private fun androidStackInvariants(): AgentChatInvariantSet =
        AgentChatInvariantSet(
            markdown =
                """
                # Invariants

                Invariant: Android stack
                Type: tech_stack
                Severity: hard
                Rule: Android implementation must use Kotlin and Jetpack Compose.
                Reject: React Native
                Reason: The project architecture and stack are fixed.
                Alternative: Offer a Kotlin/Compose solution instead.
                """.trimIndent(),
        )
}
