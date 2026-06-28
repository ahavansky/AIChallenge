package com.akhavanskii.aichallenge.mcp.dev.project

import com.akhavanskii.aichallenge.mcp.dev.DevProcessResult
import com.akhavanskii.aichallenge.mcp.dev.DevProcessRunner
import kotlinx.serialization.json.JsonObject
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

class ProjectDevToolsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exposesOnlyProjectTools() {
        val registry = projectDevToolRegistry(temporaryFolder.newFolder("project").toPath(), FakeProcessRunner())

        assertEquals(listOf(PROJECT_SNAPSHOT_TOOL, CODE_SEARCH_TOOL), registry.tools.map { it.name })
    }

    @Test
    fun codeSearchBlocksPathTraversalGlob() {
        val registry = projectDevToolRegistry(temporaryFolder.newFolder("project").toPath(), FakeProcessRunner())

        val result =
            registry.call(
                name = CODE_SEARCH_TOOL,
                arguments =
                    buildJsonObject {
                        put("query", "Agent")
                        put("glob", "../*")
                    },
            )

        assertTrue(result!!.isError)
        assertTrue(result.text.contains("traversal"))
    }

    @Test
    fun codeSearchReturnsMatchesInsideProjectRoot() {
        val root = temporaryFolder.newFolder("project").toPath()
        val source = root.resolve("feature/agent-chat/src/main/kotlin/Agent.kt")
        Files.createDirectories(source.parent)
        Files.writeString(source, "class AgentChatScreen\n")
        val registry = projectDevToolRegistry(root, FakeProcessRunner())

        val result =
            registry.call(
                name = CODE_SEARCH_TOOL,
                arguments =
                    buildJsonObject {
                        put("query", "AgentChat")
                        put("glob", "**/*.kt")
                        put("limit", 5)
                    },
            )

        assertFalse(result!!.isError)
        val matches = result.structuredContent!!["matches"]!!.jsonArray
        assertEquals(
            "feature/agent-chat/src/main/kotlin/Agent.kt",
            matches
                .single()
                .jsonObject["file"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun projectSnapshotIncludesGitStatusFromFixedRunner() {
        val root = temporaryFolder.newFolder("project").toPath()
        Files.writeString(root.resolve("settings.gradle.kts"), "include(\":feature:agent-chat\")\n")
        Files.createDirectories(root.resolve("feature/agent-chat"))
        Files.writeString(root.resolve("README.md"), "Agent Chat can call MCP.\n")
        val runner = FakeProcessRunner(output = " M feature/agent-chat/AgentChatViewModel.kt\n")
        val registry = projectDevToolRegistry(root, runner)

        val result = registry.call(PROJECT_SNAPSHOT_TOOL, JsonObject(emptyMap()))

        assertEquals(listOf("rtk", "git", "status", "--short"), runner.commands.single())
        assertFalse(result!!.isError)
        assertTrue(result.text.contains(":feature:agent-chat"))
        assertTrue(result.text.contains("feature/agent-chat/AgentChatViewModel.kt"))
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
