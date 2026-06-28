package com.akhavanskii.aichallenge.mcp.dev.buildserver

import com.akhavanskii.aichallenge.mcp.dev.DEFAULT_OUTPUT_CAP_BYTES
import com.akhavanskii.aichallenge.mcp.dev.DEFAULT_PROCESS_TIMEOUT
import com.akhavanskii.aichallenge.mcp.dev.DevMcpToolCallResult
import com.akhavanskii.aichallenge.mcp.dev.DevMcpToolDefinition
import com.akhavanskii.aichallenge.mcp.dev.DevMcpToolRegistry
import com.akhavanskii.aichallenge.mcp.dev.DevProcessRunner
import com.akhavanskii.aichallenge.mcp.dev.PathValidationResult
import com.akhavanskii.aichallenge.mcp.dev.QUALITY_GATE_PROCESS_TIMEOUT
import com.akhavanskii.aichallenge.mcp.dev.RealDevProcessRunner
import com.akhavanskii.aichallenge.mcp.dev.SafeProjectPaths
import com.akhavanskii.aichallenge.mcp.dev.objectSchema
import com.akhavanskii.aichallenge.mcp.dev.stringOrNull
import com.akhavanskii.aichallenge.mcp.dev.stringSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo

fun buildDevToolRegistry(
    projectRoot: Path,
    processRunner: DevProcessRunner = RealDevProcessRunner(projectRoot),
): DevMcpToolRegistry {
    val paths = SafeProjectPaths(projectRoot)
    return DevMcpToolRegistry(
        tools =
            listOf(
                DevMcpToolDefinition(
                    name = RUN_CHECK_TOOL,
                    description = "Run one allowlisted Android QA Gradle check target.",
                    inputSchema = runCheckSchema(),
                    handler = { arguments -> runCheck(arguments, processRunner) },
                ),
                DevMcpToolDefinition(
                    name = TEST_FAILURES_TOOL,
                    description = "Read Gradle test XML outputs and summarize failed test cases with file hints.",
                    inputSchema = testFailuresSchema(),
                    handler = { arguments -> testFailures(arguments, paths) },
                ),
            ),
    )
}

private fun runCheck(
    arguments: JsonObject,
    processRunner: DevProcessRunner,
): DevMcpToolCallResult {
    arguments.rejectUnknownArgs(setOf("target"))?.let { return DevMcpToolCallResult.error(it) }
    val target = arguments.stringOrNull("target")?.trim()
    val check =
        RUN_CHECK_TARGETS[target] ?: return DevMcpToolCallResult.error(
            "`target` must be one of: ${RUN_CHECK_TARGETS.keys.joinToString()}.",
        )
    val result =
        processRunner.run(
            command = check.command,
            timeout = check.timeout,
            outputCapBytes = DEFAULT_OUTPUT_CAP_BYTES,
            artifactName = "run-check-$target.txt",
        )

    return DevMcpToolCallResult(
        text =
            buildString {
                appendLine("run_check `$target` ${if (result.isSuccess) "passed" else "failed"}.")
                appendLine("Command: ${result.command.joinToString(" ")}")
                appendLine("Exit code: ${result.exitCode ?: "none"}")
                if (result.timedOut) appendLine("Timed out: true")
                result.artifactPath?.let { appendLine("Artifact: $it") }
                if (result.output.isNotBlank()) {
                    appendLine()
                    append(result.output)
                }
            }.trimEnd(),
        isError = !result.isSuccess,
        structuredContent =
            buildJsonObject {
                put("target", target)
                put("success", result.isSuccess)
                put("timedOut", result.timedOut)
                result.exitCode?.let { put("exitCode", it) }
                result.artifactPath?.let { put("artifact", it) }
                putJsonArray("command") {
                    result.command.forEach { add(JsonPrimitive(it)) }
                }
            },
    )
}

private fun testFailures(
    arguments: JsonObject,
    paths: SafeProjectPaths,
): DevMcpToolCallResult {
    arguments.rejectUnknownArgs(setOf("module"))?.let { return DevMcpToolCallResult.error(it) }
    val moduleRoot =
        when (val module = arguments.stringOrNull("module")?.trim()?.takeIf { it.isNotEmpty() }) {
            null -> paths.root
            else -> {
                val relativeModule = module.trimStart(':').replace(':', '/')
                when (val resolved = paths.resolveProjectRelativePath(relativeModule)) {
                    is PathValidationResult.Failure -> return DevMcpToolCallResult.error(resolved.message)
                    is PathValidationResult.Success -> resolved.path
                }
            }
        }
    if (!moduleRoot.startsWith(paths.root) || !moduleRoot.isDirectory()) {
        return DevMcpToolCallResult.error("`module` must resolve to an existing project module.")
    }

    val failures = readGradleTestFailures(moduleRoot, paths.root)
    return DevMcpToolCallResult(
        text =
            if (failures.isEmpty()) {
                "No failed Gradle test cases found."
            } else {
                buildString {
                    appendLine("Failed Gradle test cases:")
                    failures.forEach { failure ->
                        appendLine("- ${failure.className}.${failure.methodName}: ${failure.message}")
                        failure.fileHint?.let { appendLine("  $it") }
                    }
                }.trimEnd()
            },
        structuredContent =
            buildJsonObject {
                putJsonArray("failures") {
                    failures.forEach { failure ->
                        add(
                            buildJsonObject {
                                put("className", failure.className)
                                put("methodName", failure.methodName)
                                put("message", failure.message)
                                failure.fileHint?.let { put("fileHint", it) }
                                put("reportFile", failure.reportFile)
                            },
                        )
                    }
                }
            },
    )
}

private fun readGradleTestFailures(
    moduleRoot: Path,
    projectRoot: Path,
): List<TestFailure> {
    val candidates = mutableListOf<Path>()
    Files.walk(moduleRoot, TEST_RESULTS_WALK_DEPTH).use { stream ->
        stream
            .filter { it.isDirectory() && it.name == "test-results" }
            .forEach { candidates.add(it) }
    }
    return candidates
        .flatMap { root ->
            Files.walk(root, 4).use { stream ->
                stream
                    .filter { it.isRegularFile() && it.name.startsWith("TEST-") && it.name.endsWith(".xml") }
                    .flatMap { parseTestFailures(it, projectRoot).stream() }
                    .toList()
            }
        }.take(MAX_FAILURES)
}

private fun parseTestFailures(
    report: Path,
    projectRoot: Path,
): List<TestFailure> =
    runCatching {
        val factory =
            DocumentBuilderFactory
                .newInstance()
                .apply {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    isExpandEntityReferences = false
                }
        val document = factory.newDocumentBuilder().parse(report.toFile())
        val testCases = document.getElementsByTagName("testcase")
        buildList {
            for (index in 0 until testCases.length) {
                val testCase = testCases.item(index) as? Element ?: continue
                val failureElement =
                    testCase.getElementsByTagName("failure").item(0) as? Element
                        ?: testCase.getElementsByTagName("error").item(0) as? Element
                        ?: continue
                val stack = failureElement.textContent.orEmpty()
                add(
                    TestFailure(
                        className = testCase.getAttribute("classname").ifBlank { "unknown" },
                        methodName = testCase.getAttribute("name").ifBlank { "unknown" },
                        message = failureElement.getAttribute("message").ifBlank { stack.lineSequence().firstOrNull().orEmpty() },
                        fileHint = FILE_HINT_PATTERN.find(stack)?.value,
                        reportFile = report.relativeTo(projectRoot).toString(),
                    ),
                )
            }
        }
    }.getOrElse {
        emptyList()
    }

private data class RunCheckTarget(
    val command: List<String>,
    val timeout: Duration = DEFAULT_PROCESS_TIMEOUT,
)

private data class TestFailure(
    val className: String,
    val methodName: String,
    val message: String,
    val fileHint: String?,
    val reportFile: String,
)

private fun JsonObject.rejectUnknownArgs(allowed: Set<String>): String? {
    val unknown = keys.filterNot { it in allowed }
    return unknown.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Unknown argument(s): ")
}

private fun runCheckSchema(): JsonObject =
    objectSchema(
        properties =
            mapOf(
                "target" to stringSchema("Allowlisted Gradle check target.", enumValues = RUN_CHECK_TARGETS.keys.toList()),
            ),
        required = listOf("target"),
    )

private fun testFailuresSchema(): JsonObject =
    objectSchema(
        properties =
            mapOf(
                "module" to stringSchema("Optional module path, for example `:feature:agent-chat` or `feature/agent-chat`."),
            ),
    )

const val RUN_CHECK_TOOL = "run_check"
const val TEST_FAILURES_TOOL = "test_failures"
const val DEFAULT_BUILD_SERVER_PORT = 8772

val RUN_CHECK_COMMANDS: Map<String, List<String>> =
    mapOf(
        "agent_chat_unit" to listOf("rtk", "./gradlew", ":feature:agent-chat:testDebugUnitTest", "--console=plain"),
        "network_unit" to listOf("rtk", "./gradlew", ":core:network:testDebugUnitTest", "--console=plain"),
        "mcp_dev_servers" to
            listOf(
                "rtk",
                "./gradlew",
                ":mcp:dev-common:test",
                ":mcp:dev-project-server:test",
                ":mcp:dev-build-server:test",
                ":mcp:dev-device-server:test",
                "--console=plain",
            ),
        "app_debug" to listOf("rtk", "./gradlew", ":app:assembleDebug", "--console=plain"),
        "quality_gate" to
            listOf(
                "rtk",
                "./gradlew",
                ":core:network:testDebugUnitTest",
                ":feature:agent-chat:testDebugUnitTest",
                ":mcp:dev-common:test",
                ":mcp:dev-project-server:test",
                ":mcp:dev-build-server:test",
                ":mcp:dev-device-server:test",
                "ktlintCheck",
                "lintDebug",
                "--console=plain",
            ),
    )

private val RUN_CHECK_TARGET_DETAILS: Map<String, RunCheckTarget> =
    RUN_CHECK_COMMANDS.mapValues { (target, command) ->
        RunCheckTarget(
            command = command,
            timeout = if (target == "quality_gate") QUALITY_GATE_PROCESS_TIMEOUT else DEFAULT_PROCESS_TIMEOUT,
        )
    }

private val RUN_CHECK_TARGETS: Map<String, RunCheckTarget>
    get() = RUN_CHECK_TARGET_DETAILS

private const val TEST_RESULTS_WALK_DEPTH = 8
private const val MAX_FAILURES = 40
private val FILE_HINT_PATTERN = Regex("""[A-Za-z0-9_./-]+\.kt:\d+""")
