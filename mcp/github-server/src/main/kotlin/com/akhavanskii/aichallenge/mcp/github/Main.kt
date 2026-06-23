package com.akhavanskii.aichallenge.mcp.github

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

fun main() {
    val port = System.getenv("MCP_GITHUB_SERVER_PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val token = System.getenv("GITHUB_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }
    val githubClient = LiveGitHubRepositoryClient(token = token)
    val toolRegistry = McpToolRegistry.github(githubClient)
    val server = GitHubMcpHttpServer(port = port, handler = McpJsonRpcHandler(toolRegistry))
    server.start()
    println("GitHub MCP server listening on http://localhost:$port/mcp")
}

class GitHubMcpHttpServer(
    private val port: Int,
    private val handler: McpJsonRpcHandler,
) {
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress("0.0.0.0", port), 0).apply {
            createContext("/mcp") { exchange -> handle(exchange) }
        }

    fun start() {
        server.start()
    }

    fun stop(delaySeconds: Int = 0) {
        server.stop(delaySeconds)
    }

    private fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            exchange.writeText(statusCode = 405, body = "Only POST is supported.")
            return
        }

        val requestBody = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val response = handler.handle(requestBody)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.add("Mcp-Session-Id", "github-mcp-session")
        exchange.writeText(statusCode = response.statusCode, body = response.body)
    }
}

class McpJsonRpcHandler(
    private val toolRegistry: McpToolRegistry,
    private val json: Json = DEFAULT_JSON,
) {
    fun handle(requestBody: String): HttpResponseBody {
        val request =
            runCatching { json.parseToJsonElement(requestBody).jsonObject }
                .getOrElse {
                    return jsonRpcError(
                        id = JsonNull,
                        code = JSON_RPC_PARSE_ERROR,
                        message = "Invalid JSON-RPC payload.",
                    )
                }
        val id = request["id"] ?: JsonNull
        return when (request.stringOrNull("method")) {
            "initialize" -> initialize(id)
            "notifications/initialized" -> HttpResponseBody(statusCode = 202, body = "")
            "tools/list" -> toolsList(id)
            "tools/call" -> toolsCall(id, request["params"]?.jsonObjectOrNull())
            else ->
                jsonRpcError(
                    id = id,
                    code = JSON_RPC_METHOD_NOT_FOUND,
                    message = "Method not found.",
                )
        }
    }

    private fun initialize(id: JsonElement): HttpResponseBody =
        jsonRpcResult(
            id = id,
            result =
                buildJsonObject {
                    put("protocolVersion", MCP_PROTOCOL_VERSION)
                    putJsonObject("serverInfo") {
                        put("name", "github-repository-mcp")
                        put("version", "1.0.0")
                    }
                    putJsonObject("capabilities") {
                        putJsonObject("tools") {
                            put("listChanged", false)
                        }
                    }
                },
        )

    private fun toolsList(id: JsonElement): HttpResponseBody =
        jsonRpcResult(
            id = id,
            result =
                buildJsonObject {
                    putJsonArray("tools") {
                        toolRegistry.tools.forEach { tool ->
                            add(
                                buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("inputSchema", tool.inputSchema)
                                },
                            )
                        }
                    }
                },
        )

    private fun toolsCall(
        id: JsonElement,
        params: JsonObject?,
    ): HttpResponseBody {
        val toolName = params?.stringOrNull("name")
        val arguments = params?.get("arguments")?.jsonObjectOrNull() ?: JsonObject(emptyMap())
        if (toolName.isNullOrBlank()) {
            return jsonRpcError(
                id = id,
                code = JSON_RPC_INVALID_PARAMS,
                message = "Tool name is required.",
            )
        }

        val result =
            toolRegistry.call(
                name = toolName,
                arguments = arguments,
            ) ?: return jsonRpcError(
                id = id,
                code = JSON_RPC_INVALID_PARAMS,
                message = "Unknown tool: $toolName.",
            )

        return jsonRpcResult(id = id, result = result.toJson())
    }

    private fun jsonRpcResult(
        id: JsonElement,
        result: JsonObject,
    ): HttpResponseBody =
        HttpResponseBody(
            statusCode = 200,
            body =
                buildJsonObject {
                    put("jsonrpc", JSON_RPC_VERSION)
                    put("id", id)
                    put("result", result)
                }.toString(),
        )

    private fun jsonRpcError(
        id: JsonElement,
        code: Int,
        message: String,
    ): HttpResponseBody =
        HttpResponseBody(
            statusCode = 200,
            body =
                buildJsonObject {
                    put("jsonrpc", JSON_RPC_VERSION)
                    put("id", id)
                    putJsonObject("error") {
                        put("code", code)
                        put("message", message)
                    }
                }.toString(),
        )
}

data class HttpResponseBody(
    val statusCode: Int,
    val body: String,
)

data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val handler: (JsonObject) -> McpToolCallResult,
)

class McpToolRegistry(
    val tools: List<McpToolDefinition>,
) {
    private val toolsByName = tools.associateBy { it.name }

    fun call(
        name: String,
        arguments: JsonObject,
    ): McpToolCallResult? = toolsByName[name]?.handler?.invoke(arguments)

    companion object {
        fun github(githubClient: GitHubRepositoryClient): McpToolRegistry =
            McpToolRegistry(
                tools =
                    listOf(
                        McpToolDefinition(
                            name = GITHUB_REPOSITORY_SUMMARY_TOOL,
                            description = "Return live public repository metadata from GitHub REST API for owner/repo.",
                            inputSchema = githubRepositorySummarySchema(),
                            handler = { arguments ->
                                val owner = arguments.stringOrNull("owner")
                                val repo = arguments.stringOrNull("repo")
                                val validationError = validateRepositoryPath(owner = owner, repo = repo)
                                if (validationError != null) {
                                    McpToolCallResult.error(validationError)
                                } else {
                                    val summary =
                                        githubClient.repositorySummary(
                                            owner = owner.orEmpty(),
                                            repo = repo.orEmpty(),
                                        )
                                    summary.toToolResult()
                                }
                            },
                        ),
                    ),
            )
    }
}

data class McpToolCallResult(
    val text: String,
    val isError: Boolean = false,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            putJsonArray("content") {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                )
            }
            if (isError) {
                put("isError", true)
            }
        }

    companion object {
        fun error(message: String): McpToolCallResult = McpToolCallResult(text = message, isError = true)
    }
}

interface GitHubRepositoryClient {
    fun repositorySummary(
        owner: String,
        repo: String,
    ): GitHubRepositoryResult
}

class LiveGitHubRepositoryClient(
    private val token: String? = null,
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build(),
    private val json: Json = DEFAULT_JSON,
) : GitHubRepositoryClient {
    override fun repositorySummary(
        owner: String,
        repo: String,
    ): GitHubRepositoryResult {
        val endpoint =
            "https://api.github.com/repos/" +
                owner.urlEncodePathSegment() +
                "/" +
                repo.urlEncodePathSegment()
        val requestBuilder =
            HttpRequest
                .newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "aichallenge-github-mcp")
                .GET()
        token?.let { requestBuilder.header("Authorization", "Bearer $it") }

        val response =
            runCatching {
                httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            }.getOrElse { throwable ->
                return GitHubRepositoryResult.Failure("Network error while contacting GitHub: ${throwable.message.orEmpty()}")
            }

        return when (response.statusCode()) {
            in 200..299 -> parseRepository(response.body())
            404 -> GitHubRepositoryResult.Failure("Repository not found: $owner/$repo.")
            403, 429 -> GitHubRepositoryResult.Failure("GitHub rate limit or abuse protection blocked the request.")
            else -> GitHubRepositoryResult.Failure("GitHub returned HTTP ${response.statusCode()}.")
        }
    }

    private fun parseRepository(body: String): GitHubRepositoryResult =
        runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            GitHubRepositoryResult.Success(
                GitHubRepositorySummary(
                    fullName = root.requiredString("full_name"),
                    htmlUrl = root.requiredString("html_url"),
                    description = root.stringOrNull("description").orEmpty(),
                    stargazersCount = root.intOrZero("stargazers_count"),
                    forksCount = root.intOrZero("forks_count"),
                    openIssuesCount = root.intOrZero("open_issues_count"),
                    defaultBranch = root.stringOrNull("default_branch").orEmpty(),
                    licenseName =
                        root["license"]
                            ?.jsonObjectOrNull()
                            ?.stringOrNull("spdx_id")
                            ?.takeIf { it.isNotBlank() }
                            ?: root["license"]
                                ?.jsonObjectOrNull()
                                ?.stringOrNull("name")
                                .orEmpty(),
                    language = root.stringOrNull("language").orEmpty(),
                    updatedAt = root.stringOrNull("updated_at").orEmpty(),
                ),
            )
        }.getOrElse { throwable ->
            GitHubRepositoryResult.Failure("GitHub response could not be parsed: ${throwable.message.orEmpty()}")
        }
}

sealed interface GitHubRepositoryResult {
    data class Success(
        val summary: GitHubRepositorySummary,
    ) : GitHubRepositoryResult

    data class Failure(
        val message: String,
    ) : GitHubRepositoryResult
}

data class GitHubRepositorySummary(
    val fullName: String,
    val htmlUrl: String,
    val description: String,
    val stargazersCount: Int,
    val forksCount: Int,
    val openIssuesCount: Int,
    val defaultBranch: String,
    val licenseName: String,
    val language: String,
    val updatedAt: String,
)

fun GitHubRepositoryResult.toToolResult(): McpToolCallResult =
    when (this) {
        is GitHubRepositoryResult.Success ->
            McpToolCallResult(
                text =
                    buildString {
                        appendLine("GitHub repository summary")
                        appendLine("Full name: ${summary.fullName}")
                        appendLine("URL: ${summary.htmlUrl}")
                        appendLine("Description: ${summary.description.ifBlank { "No description" }}")
                        appendLine("Stars: ${summary.stargazersCount}")
                        appendLine("Forks: ${summary.forksCount}")
                        appendLine("Open issues: ${summary.openIssuesCount}")
                        appendLine("Default branch: ${summary.defaultBranch.ifBlank { "Unknown" }}")
                        appendLine("License: ${summary.licenseName.ifBlank { "Not declared" }}")
                        appendLine("Language: ${summary.language.ifBlank { "Unknown" }}")
                        append("Updated at: ${summary.updatedAt.ifBlank { "Unknown" }}")
                    },
            )
        is GitHubRepositoryResult.Failure -> McpToolCallResult.error(message)
    }

private fun githubRepositorySummarySchema(): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("owner") {
                put("type", "string")
                put("description", "GitHub repository owner, for example square.")
                put("minLength", 1)
                put("pattern", GITHUB_PATH_SEGMENT_PATTERN.pattern)
            }
            putJsonObject("repo") {
                put("type", "string")
                put("description", "GitHub repository name, for example okhttp.")
                put("minLength", 1)
                put("pattern", GITHUB_PATH_SEGMENT_PATTERN.pattern)
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("owner"), JsonPrimitive("repo"))))
        put("additionalProperties", false)
    }

private fun validateRepositoryPath(
    owner: String?,
    repo: String?,
): String? =
    when {
        owner.isNullOrBlank() -> "`owner` is required."
        repo.isNullOrBlank() -> "`repo` is required."
        !GITHUB_PATH_SEGMENT_PATTERN.matches(owner) -> "`owner` must be a valid GitHub path segment."
        !GITHUB_PATH_SEGMENT_PATTERN.matches(repo) -> "`repo` must be a valid GitHub path segment."
        else -> null
    }

private fun HttpExchange.writeText(
    statusCode: Int,
    body: String,
) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    sendResponseHeaders(statusCode, bytes.size.toLong())
    responseBody.use { output -> output.write(bytes) }
}

private fun JsonObject.stringOrNull(key: String): String? =
    this[key]
        ?.jsonPrimitiveOrNull()
        ?.contentOrNull

private fun JsonObject.requiredString(key: String): String =
    stringOrNull(key)?.takeIf { it.isNotBlank() } ?: error("Missing string field: $key")

private fun JsonObject.intOrZero(key: String): Int =
    this[key]
        ?.jsonPrimitiveOrNull()
        ?.intOrNull
        ?: 0

private fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = runCatching { jsonPrimitive }.getOrNull()

private fun String.urlEncodePathSegment(): String {
    val encoded = URLEncoder.encode(this, StandardCharsets.UTF_8)
    return encoded.replace("+", "%20")
}

private const val DEFAULT_PORT = 8765
const val GITHUB_REPOSITORY_SUMMARY_TOOL = "github_repository_summary"
private const val JSON_RPC_VERSION = "2.0"
private const val MCP_PROTOCOL_VERSION = "2025-06-18"
private const val JSON_RPC_PARSE_ERROR = -32700
private const val JSON_RPC_METHOD_NOT_FOUND = -32601
private const val JSON_RPC_INVALID_PARAMS = -32602
private val GITHUB_PATH_SEGMENT_PATTERN = Regex("[A-Za-z0-9_.-]+")
private val DEFAULT_JSON =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
