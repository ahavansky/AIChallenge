package com.akhavanskii.aichallenge.mcp.dev.buildserver

import com.akhavanskii.aichallenge.mcp.dev.DevProcessResult
import com.akhavanskii.aichallenge.mcp.dev.DevProcessRunner
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.time.Duration

class BuildDevToolsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exposesOnlyBuildTools() {
        val registry = buildDevToolRegistry(temporaryFolder.newFolder("project").toPath(), FakeProcessRunner())

        assertEquals(listOf(RUN_CHECK_TOOL, TEST_FAILURES_TOOL), registry.tools.map { it.name })
    }

    @Test
    fun runCheckRejectsArbitraryTargetWithoutProcessCall() {
        val runner = FakeProcessRunner()
        val registry = buildDevToolRegistry(temporaryFolder.newFolder("project").toPath(), runner)

        val result =
            registry.call(
                name = RUN_CHECK_TOOL,
                arguments = buildJsonObject { put("target", "rm_rf") },
            )

        assertTrue(result!!.isError)
        assertEquals(emptyList<List<String>>(), runner.commands)
    }

    @Test
    fun runCheckUsesExactAllowlistedRtkCommand() {
        val runner = FakeProcessRunner(output = "BUILD SUCCESSFUL")
        val registry = buildDevToolRegistry(temporaryFolder.newFolder("project").toPath(), runner)

        val result =
            registry.call(
                name = RUN_CHECK_TOOL,
                arguments = buildJsonObject { put("target", "agent_chat_unit") },
            )

        assertFalse(result!!.isError)
        assertEquals(RUN_CHECK_COMMANDS["agent_chat_unit"], runner.commands.single())
    }

    @Test
    fun testFailuresBlocksPathTraversalModule() {
        val registry = buildDevToolRegistry(temporaryFolder.newFolder("project").toPath(), FakeProcessRunner())

        val result =
            registry.call(
                name = TEST_FAILURES_TOOL,
                arguments = buildJsonObject { put("module", "../outside") },
            )

        assertTrue(result!!.isError)
        assertTrue(result.text.contains("traversal"))
    }

    @Test
    fun testFailuresReadsGradleXmlReports() {
        val root = temporaryFolder.newFolder("project").toPath()
        val report = root.resolve("feature/agent-chat/build/test-results/testDebugUnitTest/TEST-AgentChatViewModelTest.xml")
        Files.createDirectories(report.parent)
        Files.writeString(
            report,
            """
            <testsuite>
              <testcase classname="AgentChatViewModelTest" name="runMcpAgent">
                <failure message="expected true">AgentChatViewModelTest.kt:42</failure>
              </testcase>
            </testsuite>
            """.trimIndent(),
        )
        val registry = buildDevToolRegistry(root, FakeProcessRunner())

        val result =
            registry.call(
                name = TEST_FAILURES_TOOL,
                arguments = buildJsonObject { put("module", ":feature:agent-chat") },
            )

        assertFalse(result!!.isError)
        val failure =
            result.structuredContent!!["failures"]!!
                .jsonArray
                .single()
                .jsonObject
        assertEquals("AgentChatViewModelTest", failure["className"]!!.jsonPrimitive.content)
        assertEquals("runMcpAgent", failure["methodName"]!!.jsonPrimitive.content)
        assertEquals("AgentChatViewModelTest.kt:42", failure["fileHint"]!!.jsonPrimitive.content)
    }

    private class FakeProcessRunner(
        private val output: String = "",
    ) : DevProcessRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(
            command: List<String>,
            timeout: Duration,
            outputCapBytes: Int,
            artifactName: String,
        ): DevProcessResult {
            commands += command
            return DevProcessResult(command = command, exitCode = 0, timedOut = false, output = output)
        }
    }
}
