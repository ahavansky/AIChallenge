package com.akhavanskii.aichallenge.mcp.dev.project

import com.akhavanskii.aichallenge.mcp.dev.DEFAULT_OUTPUT_CAP_BYTES
import com.akhavanskii.aichallenge.mcp.dev.DevMcpToolCallResult
import com.akhavanskii.aichallenge.mcp.dev.DevMcpToolDefinition
import com.akhavanskii.aichallenge.mcp.dev.DevMcpToolRegistry
import com.akhavanskii.aichallenge.mcp.dev.DevProcessRunner
import com.akhavanskii.aichallenge.mcp.dev.RealDevProcessRunner
import com.akhavanskii.aichallenge.mcp.dev.SafeProjectPaths
import com.akhavanskii.aichallenge.mcp.dev.intOrNull
import com.akhavanskii.aichallenge.mcp.dev.integerSchema
import com.akhavanskii.aichallenge.mcp.dev.objectSchema
import com.akhavanskii.aichallenge.mcp.dev.stringOrNull
import com.akhavanskii.aichallenge.mcp.dev.stringSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo

fun projectDevToolRegistry(
    projectRoot: Path,
    processRunner: DevProcessRunner = RealDevProcessRunner(projectRoot),
): DevMcpToolRegistry {
    val paths = SafeProjectPaths(projectRoot)
    return DevMcpToolRegistry(
        tools =
            listOf(
                DevMcpToolDefinition(
                    name = PROJECT_SNAPSHOT_TOOL,
                    description = "Read project modules, feature list, README snippets, and changed git files.",
                    inputSchema = objectSchema(properties = emptyMap()),
                    handler = { projectSnapshot(paths, processRunner) },
                ),
                DevMcpToolDefinition(
                    name = CODE_SEARCH_TOOL,
                    description = "Search source text under the Android project root with optional safe glob and result limit.",
                    inputSchema = codeSearchSchema(),
                    handler = { arguments -> codeSearch(arguments, paths) },
                ),
            ),
    )
}

private fun projectSnapshot(
    paths: SafeProjectPaths,
    processRunner: DevProcessRunner,
): DevMcpToolCallResult {
    val modules = readModules(paths.root)
    val features = readFeatureDirs(paths.root)
    val readmeSnippets = readReadmeSnippets(paths.root)
    val gitStatus =
        processRunner.run(
            command = listOf("rtk", "git", "status", "--short"),
            timeout = Duration.ofSeconds(15),
            outputCapBytes = DEFAULT_OUTPUT_CAP_BYTES,
            artifactName = "project-snapshot-git-status.txt",
        )
    val changedFiles =
        if (gitStatus.isSuccess) {
            gitStatus.output
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .take(80)
                .toList()
        } else {
            emptyList()
        }

    return DevMcpToolCallResult(
        text =
            buildString {
                appendLine("Project snapshot")
                appendLine("Modules: ${modules.joinToString()}")
                appendLine("Features: ${features.joinToString()}")
                appendLine("Changed files: ${changedFiles.ifEmpty { listOf("none") }.joinToString()}")
                if (readmeSnippets.isNotEmpty()) {
                    appendLine()
                    appendLine("README snippets:")
                    readmeSnippets.forEach { appendLine("- $it") }
                }
                if (!gitStatus.isSuccess && gitStatus.output.isNotBlank()) {
                    appendLine()
                    appendLine("Git status unavailable: ${gitStatus.output.take(240)}")
                }
            }.trimEnd(),
        structuredContent =
            buildJsonObject {
                putJsonArray("modules") {
                    modules.forEach { add(JsonPrimitive(it)) }
                }
                putJsonArray("features") {
                    features.forEach { add(JsonPrimitive(it)) }
                }
                putJsonArray("readmeSnippets") {
                    readmeSnippets.forEach { add(JsonPrimitive(it)) }
                }
                putJsonArray("changedFiles") {
                    changedFiles.forEach { add(JsonPrimitive(it)) }
                }
                gitStatus.artifactPath?.let { put("artifact", it) }
            },
    )
}

private fun codeSearch(
    arguments: JsonObject,
    paths: SafeProjectPaths,
): DevMcpToolCallResult {
    arguments.rejectUnknownArgs(setOf("query", "glob", "limit"))?.let { return DevMcpToolCallResult.error(it) }
    val query = arguments.stringOrNull("query")?.trim()
    if (query.isNullOrBlank()) return DevMcpToolCallResult.error("`query` is required.")
    if (query.length > MAX_QUERY_LENGTH) return DevMcpToolCallResult.error("`query` must be $MAX_QUERY_LENGTH characters or fewer.")
    val glob =
        arguments.stringOrNull("glob")?.let { raw ->
            paths.validateGlob(raw) ?: return DevMcpToolCallResult.error("`glob` must be relative and must not contain traversal.")
        }
    val limit = arguments.intOrNull("limit") ?: DEFAULT_SEARCH_LIMIT
    if (limit !in SEARCH_LIMIT_RANGE) {
        return DevMcpToolCallResult.error("`limit` must be between ${SEARCH_LIMIT_RANGE.first} and ${SEARCH_LIMIT_RANGE.last}.")
    }

    val matcher = glob?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }
    val matches = mutableListOf<CodeSearchMatch>()
    Files.walk(paths.root).use { stream ->
        val iterator = stream.iterator()
        while (iterator.hasNext() && matches.size < limit) {
            val path = iterator.next()
            if (!path.isRegularFile() || !path.isSearchablePath(paths.root)) continue
            val relative = path.relativeTo(paths.root)
            if (matcher != null && !matcher.matches(relative)) continue
            if (Files.size(path) > MAX_SEARCH_FILE_BYTES) continue

            Files.newBufferedReader(path, StandardCharsets.UTF_8).useLines { lines ->
                lines.forEachIndexed { index, line ->
                    if (matches.size >= limit) return@useLines
                    if (line.contains(query, ignoreCase = true)) {
                        matches +=
                            CodeSearchMatch(
                                file = relative.toString(),
                                line = index + 1,
                                snippet = line.trim().take(MAX_SNIPPET_CHARS),
                            )
                    }
                }
            }
        }
    }

    return DevMcpToolCallResult(
        text =
            if (matches.isEmpty()) {
                "No matches for `$query`."
            } else {
                buildString {
                    appendLine("Matches for `$query`:")
                    matches.forEach { match ->
                        appendLine("${match.file}:${match.line}: ${match.snippet}")
                    }
                }.trimEnd()
            },
        structuredContent =
            buildJsonObject {
                put("query", query)
                glob?.let { put("glob", it) }
                putJsonArray("matches") {
                    matches.forEach { match ->
                        add(
                            buildJsonObject {
                                put("file", match.file)
                                put("line", match.line)
                                put("snippet", match.snippet)
                            },
                        )
                    }
                }
            },
    )
}

private data class CodeSearchMatch(
    val file: String,
    val line: Int,
    val snippet: String,
)

private fun readModules(root: Path): List<String> {
    val settings = root.resolve("settings.gradle.kts")
    if (!settings.isRegularFile()) return emptyList()
    return Files
        .readAllLines(settings, StandardCharsets.UTF_8)
        .mapNotNull { line ->
            INCLUDE_PATTERN.find(line)?.groupValues?.getOrNull(1)
        }.sorted()
}

private fun readFeatureDirs(root: Path): List<String> {
    val featureRoot = root.resolve("feature")
    if (!featureRoot.isDirectory()) return emptyList()
    return Files.list(featureRoot).use { stream ->
        stream
            .filter { it.isDirectory() }
            .map { it.name }
            .sorted()
            .toList()
    }
}

private fun readReadmeSnippets(root: Path): List<String> {
    val readme = root.resolve("README.md")
    if (!readme.isRegularFile()) return emptyList()
    return Files
        .readAllLines(readme, StandardCharsets.UTF_8)
        .asSequence()
        .map { it.trim() }
        .filter { line ->
            line.contains("Agent Chat", ignoreCase = true) ||
                line.contains("MCP", ignoreCase = true) ||
                line.contains(":mcp:", ignoreCase = true)
        }.filter { it.length in 8..220 }
        .take(12)
        .toList()
}

private fun Path.isSearchablePath(root: Path): Boolean {
    val relative = relativeTo(root)
    if (relative.any { it.toString() in SKIPPED_PATH_SEGMENTS }) return false
    val fileName = name
    return SEARCHABLE_EXTENSIONS.any { fileName.endsWith(it) }
}

private fun JsonObject.rejectUnknownArgs(allowed: Set<String>): String? {
    val unknown = keys.filterNot { it in allowed }
    return unknown.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Unknown argument(s): ")
}

private fun codeSearchSchema(): JsonObject =
    objectSchema(
        properties =
            mapOf(
                "query" to stringSchema("Text query to search for.", minLength = 1, maxLength = MAX_QUERY_LENGTH),
                "glob" to stringSchema("Optional relative glob, for example `**/*.kt`.", maxLength = 120),
                "limit" to integerSchema("Maximum match count.", SEARCH_LIMIT_RANGE.first, SEARCH_LIMIT_RANGE.last, DEFAULT_SEARCH_LIMIT),
            ),
        required = listOf("query"),
    )

const val PROJECT_SNAPSHOT_TOOL = "project_snapshot"
const val CODE_SEARCH_TOOL = "code_search"
const val DEFAULT_PROJECT_SERVER_PORT = 8771

private const val MAX_QUERY_LENGTH = 200
private const val DEFAULT_SEARCH_LIMIT = 20
private const val MAX_SEARCH_FILE_BYTES = 512 * 1024L
private const val MAX_SNIPPET_CHARS = 240
private val SEARCH_LIMIT_RANGE = 1..50
private val INCLUDE_PATTERN = Regex("""include\("([^"]+)"\)""")
private val SKIPPED_PATH_SEGMENTS = setOf(".git", ".gradle", ".idea", "build")
private val SEARCHABLE_EXTENSIONS =
    setOf(
        ".kt",
        ".kts",
        ".java",
        ".xml",
        ".md",
        ".toml",
        ".properties",
        ".json",
        ".yaml",
        ".yml",
    )
