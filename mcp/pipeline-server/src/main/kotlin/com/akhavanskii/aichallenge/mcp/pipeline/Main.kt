package com.akhavanskii.aichallenge.mcp.pipeline

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
import kotlinx.serialization.json.jsonArray
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration

fun main() {
    val port = System.getenv("MCP_PIPELINE_SERVER_PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val outputDir =
        System
            .getenv("MCP_PIPELINE_OUTPUT_DIR")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(Path::of)
            ?: Path.of("build", "mcp-pipeline")
    val toolRegistry =
        McpToolRegistry.pipeline(
            searchClient = LiveWikipediaSearchClient(),
            summaryWriter = FileSummaryWriter(outputDir),
        )
    val server = PipelineMcpHttpServer(port = port, handler = McpJsonRpcHandler(toolRegistry))
    server.start()
    println("Pipeline MCP server listening on http://localhost:$port/mcp")
}

class PipelineMcpHttpServer(
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
        exchange.responseHeaders.add("Mcp-Session-Id", "pipeline-mcp-session")
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
                        put("name", "pipeline-mcp")
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
        fun pipeline(
            searchClient: SearchClient,
            summaryWriter: SummaryWriter,
        ): McpToolRegistry =
            McpToolRegistry(
                tools =
                    listOf(
                        McpToolDefinition(
                            name = SEARCH_TOOL,
                            description = "Search Wikipedia for live source data and return structured results.",
                            inputSchema = searchSchema(),
                            handler = { arguments -> searchTool(arguments, searchClient) },
                        ),
                        McpToolDefinition(
                            name = SUMMARIZE_TOOL,
                            description = "Create a deterministic extractive summary from search tool results.",
                            inputSchema = summarizeSchema(),
                            handler = ::summarizeTool,
                        ),
                        McpToolDefinition(
                            name = SAVE_TO_FILE_TOOL,
                            description = "Save a pipeline summary Markdown file into the server output directory.",
                            inputSchema = saveToFileSchema(),
                            handler = { arguments -> saveToFileTool(arguments, summaryWriter) },
                        ),
                    ),
            )
    }
}

data class McpToolCallResult(
    val text: String,
    val isError: Boolean = false,
    val structuredContent: JsonObject? = null,
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
            structuredContent?.let { put("structuredContent", it) }
            if (isError) {
                put("isError", true)
            }
        }

    companion object {
        fun error(message: String): McpToolCallResult = McpToolCallResult(text = message, isError = true)
    }
}

interface SearchClient {
    fun search(
        query: String,
        language: String,
        limit: Int,
    ): SearchResult
}

class LiveWikipediaSearchClient(
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build(),
    private val json: Json = DEFAULT_JSON,
) : SearchClient {
    override fun search(
        query: String,
        language: String,
        limit: Int,
    ): SearchResult {
        val endpoint =
            "https://$language.wikipedia.org/w/api.php" +
                "?action=query" +
                "&list=search" +
                "&format=json" +
                "&utf8=1" +
                "&srlimit=$limit" +
                "&srsearch=${query.urlEncodeQuery()}"
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("User-Agent", "aichallenge-pipeline-mcp")
                .GET()
                .build()

        val response =
            runCatching {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            }.getOrElse { throwable ->
                return SearchResult.Failure("Network error while contacting Wikipedia: ${throwable.message.orEmpty()}")
            }

        if (response.statusCode() !in 200..299) {
            return SearchResult.Failure("Wikipedia returned HTTP ${response.statusCode()}.")
        }

        return parseSearchResponse(language = language, body = response.body())
    }

    private fun parseSearchResponse(
        language: String,
        body: String,
    ): SearchResult =
        runCatching {
            val items =
                json
                    .parseToJsonElement(body)
                    .jsonObject["query"]
                    ?.jsonObjectOrNull()
                    ?.get("search")
                    ?.jsonArrayOrNull()
                    ?.mapNotNull { element ->
                        val item = element.jsonObjectOrNull() ?: return@mapNotNull null
                        val pageId = item["pageid"]?.jsonPrimitiveOrNull()?.contentOrNull?.takeIf { it.isNotBlank() }
                        val title = item.stringOrNull("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        SearchItem(
                            title = title,
                            snippet = item.stringOrNull("snippet").orEmpty().cleanSnippet(),
                            url = pageId?.let { "https://$language.wikipedia.org/?curid=$it" }.orEmpty(),
                        )
                    }.orEmpty()

            if (items.isEmpty()) {
                SearchResult.Failure("Wikipedia returned no results.")
            } else {
                SearchResult.Success(items)
            }
        }.getOrElse { throwable ->
            SearchResult.Failure("Wikipedia response could not be parsed: ${throwable.message.orEmpty()}")
        }
}

sealed interface SearchResult {
    data class Success(
        val items: List<SearchItem>,
    ) : SearchResult

    data class Failure(
        val message: String,
    ) : SearchResult
}

data class SearchItem(
    val title: String,
    val snippet: String,
    val url: String,
)

interface SummaryWriter {
    fun save(
        query: String,
        markdown: String,
        fileName: String?,
    ): SaveResult
}

class FileSummaryWriter(
    outputDir: Path,
) : SummaryWriter {
    private val root = outputDir.toAbsolutePath().normalize()

    override fun save(
        query: String,
        markdown: String,
        fileName: String?,
    ): SaveResult {
        val safeName = fileName?.validateFileName() ?: generatedFileName(query)
        val path = root.resolve(safeName).normalize()
        if (!path.startsWith(root)) {
            return SaveResult.Failure("File name must stay inside MCP pipeline output directory.")
        }

        return runCatching {
            Files.createDirectories(root)
            Files.writeString(
                path,
                markdown,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            SaveResult.Success(
                path = path,
                fileName = safeName,
                byteSize = Files.size(path),
            )
        }.getOrElse { throwable ->
            SaveResult.Failure("Could not save MCP pipeline summary: ${throwable.message.orEmpty()}")
        }
    }

    private fun String.validateFileName(): String {
        val name = trim()
        require(name.isNotEmpty()) { "`fileName` must not be blank." }
        require(name.length <= MAX_FILE_NAME_LENGTH) { "`fileName` must be $MAX_FILE_NAME_LENGTH characters or fewer." }
        require(!name.contains('/')) { "`fileName` must not contain path separators." }
        require(!name.contains('\\')) { "`fileName` must not contain path separators." }
        require(!name.contains("..")) { "`fileName` must not contain `..`." }
        require(SAFE_FILE_NAME_PATTERN.matches(name)) { "`fileName` may contain only letters, numbers, dots, dashes, and underscores." }
        return name
    }

    private fun generatedFileName(query: String): String {
        val slug =
            query
                .lowercase()
                .replace(UNSAFE_SLUG_PATTERN, "-")
                .trim('-')
                .take(MAX_SLUG_LENGTH)
                .ifBlank { "summary" }
        val hash = query.hashCode().toUInt().toString(radix = 16)
        return "$slug-$hash.md"
    }
}

sealed interface SaveResult {
    data class Success(
        val path: Path,
        val fileName: String,
        val byteSize: Long,
    ) : SaveResult

    data class Failure(
        val message: String,
    ) : SaveResult
}

private fun searchTool(
    arguments: JsonObject,
    searchClient: SearchClient,
): McpToolCallResult {
    val query = arguments.stringOrNull("query")?.trim()
    val language = arguments.stringOrNull("language")?.trim()?.ifBlank { DEFAULT_LANGUAGE } ?: DEFAULT_LANGUAGE
    val limit = arguments["limit"]?.jsonPrimitiveOrNull()?.intOrNull ?: DEFAULT_SEARCH_LIMIT
    val validationError = validateSearchArgs(query = query, language = language, limit = limit)
    if (validationError != null) return McpToolCallResult.error(validationError)

    return when (val result = searchClient.search(query = query.orEmpty(), language = language, limit = limit)) {
        is SearchResult.Failure -> McpToolCallResult.error(result.message)
        is SearchResult.Success -> {
            val results = result.items.take(limit)
            McpToolCallResult(
                text =
                    buildString {
                        appendLine("Search results for \"$query\"")
                        results.forEachIndexed { index, item ->
                            appendLine("${index + 1}. ${item.title}")
                            if (item.snippet.isNotBlank()) appendLine("   ${item.snippet}")
                            if (item.url.isNotBlank()) appendLine("   ${item.url}")
                        }
                    }.trimEnd(),
                structuredContent =
                    buildJsonObject {
                        put("query", query)
                        put("language", language)
                        putJsonArray("results") {
                            results.forEach { item ->
                                add(
                                    buildJsonObject {
                                        put("title", item.title)
                                        put("snippet", item.snippet)
                                        put("url", item.url)
                                    },
                                )
                            }
                        }
                    },
            )
        }
    }
}

private fun summarizeTool(arguments: JsonObject): McpToolCallResult {
    val query = arguments.stringOrNull("query")?.trim()
    val results = arguments["results"]?.jsonArrayOrNull()
    if (query.isNullOrBlank()) return McpToolCallResult.error("`query` is required.")
    if (results == null) return McpToolCallResult.error("`results` from `search` is required.")

    val items =
        results.mapNotNull { element ->
            val item = element.jsonObjectOrNull() ?: return@mapNotNull null
            SearchItem(
                title = item.stringOrNull("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                snippet = item.stringOrNull("snippet").orEmpty(),
                url = item.stringOrNull("url").orEmpty(),
            )
        }
    if (items.isEmpty()) return McpToolCallResult.error("`results` must contain at least one search result.")

    val markdown =
        buildString {
            appendLine("# MCP pipeline summary")
            appendLine()
            appendLine("Query: $query")
            appendLine("Sources: ${items.size}")
            appendLine()
            appendLine("## Extractive summary")
            items.forEach { item ->
                append("- ")
                append(item.title)
                item.snippet.takeIf { it.isNotBlank() }?.let { snippet ->
                    append(": ")
                    append(snippet)
                }
                appendLine()
            }
            appendLine()
            appendLine("## Sources")
            items.forEachIndexed { index, item ->
                append(index + 1)
                append(". ")
                append(item.title)
                item.url.takeIf { it.isNotBlank() }?.let { url ->
                    append(" - ")
                    append(url)
                }
                appendLine()
            }
        }.trimEnd()

    return McpToolCallResult(
        text = markdown,
        structuredContent =
            buildJsonObject {
                putJsonObject("summary") {
                    put("query", query)
                    put("sourceCount", items.size)
                    put("markdown", markdown)
                    putJsonArray("sources") {
                        items.forEach { item ->
                            add(
                                buildJsonObject {
                                    put("title", item.title)
                                    put("url", item.url)
                                },
                            )
                        }
                    }
                }
            },
    )
}

private fun saveToFileTool(
    arguments: JsonObject,
    summaryWriter: SummaryWriter,
): McpToolCallResult {
    val query = arguments.stringOrNull("query")?.trim()
    val summary = arguments["summary"]?.jsonObjectOrNull()
    val markdown = summary?.stringOrNull("markdown")?.trim()
    val fileName = arguments.stringOrNull("fileName")
    if (query.isNullOrBlank()) return McpToolCallResult.error("`query` is required.")
    if (markdown.isNullOrBlank()) return McpToolCallResult.error("`summary.markdown` from `summarize` is required.")

    return runCatching {
        when (val result = summaryWriter.save(query = query, markdown = markdown, fileName = fileName)) {
            is SaveResult.Failure -> McpToolCallResult.error(result.message)
            is SaveResult.Success ->
                McpToolCallResult(
                    text = "Saved MCP pipeline summary to ${result.path}.",
                    structuredContent =
                        buildJsonObject {
                            putJsonObject("savedFile") {
                                put("path", result.path.toString())
                                put("fileName", result.fileName)
                                put("byteSize", result.byteSize)
                                put("markdown", markdown)
                            }
                        },
                )
        }
    }.getOrElse { throwable ->
        McpToolCallResult.error(throwable.message ?: "Could not save MCP pipeline summary.")
    }
}

private fun validateSearchArgs(
    query: String?,
    language: String,
    limit: Int,
): String? =
    when {
        query.isNullOrBlank() -> "`query` is required."
        query.length > MAX_QUERY_LENGTH -> "`query` must be $MAX_QUERY_LENGTH characters or fewer."
        language !in SUPPORTED_LANGUAGES -> "`language` must be one of: ${SUPPORTED_LANGUAGES.joinToString()}."
        limit !in SEARCH_LIMIT_RANGE -> "`limit` must be between ${SEARCH_LIMIT_RANGE.first} and ${SEARCH_LIMIT_RANGE.last}."
        else -> null
    }

private fun searchSchema(): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "Search query for Wikipedia.")
                put("minLength", 1)
                put("maxLength", MAX_QUERY_LENGTH)
            }
            putJsonObject("language") {
                put("type", "string")
                put("description", "Wikipedia language code.")
                put("enum", JsonArray(SUPPORTED_LANGUAGES.map(::JsonPrimitive)))
                put("default", DEFAULT_LANGUAGE)
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "Maximum result count.")
                put("minimum", SEARCH_LIMIT_RANGE.first)
                put("maximum", SEARCH_LIMIT_RANGE.last)
                put("default", DEFAULT_SEARCH_LIMIT)
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("query"))))
        put("additionalProperties", false)
    }

private fun summarizeSchema(): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "Original query from the search step.")
            }
            putJsonObject("results") {
                put("type", "array")
                put("description", "Exact `structuredContent.results` array returned by `search`.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("query"), JsonPrimitive("results"))))
        put("additionalProperties", false)
    }

private fun saveToFileSchema(): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "Original query from the search step.")
            }
            putJsonObject("summary") {
                put("type", "object")
                put("description", "Exact `structuredContent.summary` object returned by `summarize`.")
            }
            putJsonObject("fileName") {
                put("type", "string")
                put("description", "Optional safe file name. Generated when omitted.")
                put("maxLength", MAX_FILE_NAME_LENGTH)
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("query"), JsonPrimitive("summary"))))
        put("additionalProperties", false)
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

private fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

private fun JsonElement.jsonArrayOrNull(): JsonArray? = runCatching { jsonArray }.getOrNull()

private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = runCatching { jsonPrimitive }.getOrNull()

private fun String.urlEncodeQuery(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)

private fun String.cleanSnippet(): String =
    replace(HTML_TAG_PATTERN, "")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(WHITESPACE_PATTERN, " ")
        .trim()

const val SEARCH_TOOL = "search"
const val SUMMARIZE_TOOL = "summarize"
const val SAVE_TO_FILE_TOOL = "saveToFile"

private const val DEFAULT_PORT = 8766
private const val JSON_RPC_VERSION = "2.0"
private const val MCP_PROTOCOL_VERSION = "2025-06-18"
private const val JSON_RPC_PARSE_ERROR = -32700
private const val JSON_RPC_METHOD_NOT_FOUND = -32601
private const val JSON_RPC_INVALID_PARAMS = -32602
private const val DEFAULT_LANGUAGE = "en"
private const val DEFAULT_SEARCH_LIMIT = 3
private const val MAX_QUERY_LENGTH = 200
private const val MAX_FILE_NAME_LENGTH = 120
private const val MAX_SLUG_LENGTH = 48
private val SEARCH_LIMIT_RANGE = 1..5
private val SUPPORTED_LANGUAGES = setOf("en", "ru")
private val SAFE_FILE_NAME_PATTERN = Regex("[A-Za-z0-9._-]+")
private val UNSAFE_SLUG_PATTERN = Regex("[^a-zа-яё0-9]+")
private val HTML_TAG_PATTERN = Regex("<[^>]+>")
private val WHITESPACE_PATTERN = Regex("\\s+")
private val DEFAULT_JSON =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
