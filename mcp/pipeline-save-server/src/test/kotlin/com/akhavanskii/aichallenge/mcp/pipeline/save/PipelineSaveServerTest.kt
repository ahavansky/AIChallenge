package com.akhavanskii.aichallenge.mcp.pipeline.save

import com.akhavanskii.aichallenge.mcp.pipeline.FileSummaryWriter
import com.akhavanskii.aichallenge.mcp.pipeline.McpJsonRpcHandler
import com.akhavanskii.aichallenge.mcp.pipeline.McpToolRegistry
import com.akhavanskii.aichallenge.mcp.pipeline.PipelineToolDefinitions
import com.akhavanskii.aichallenge.mcp.pipeline.SAVE_TO_FILE_TOOL
import com.akhavanskii.aichallenge.mcp.pipeline.SEARCH_TOOL
import com.akhavanskii.aichallenge.mcp.pipeline.SUMMARIZE_TOOL
import com.akhavanskii.aichallenge.mcp.pipeline.SearchClient
import com.akhavanskii.aichallenge.mcp.pipeline.SearchItem
import com.akhavanskii.aichallenge.mcp.pipeline.SearchResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class PipelineSaveServerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun toolsListExposesOnlySaveTool() {
        val tools = saveHandler().handle("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").tools()

        assertEquals(listOf(SAVE_TO_FILE_TOOL), tools.map { it["name"]!!.jsonPrimitive.content })
    }

    @Test
    fun wrongToolReturnsUnknownTool() {
        val response =
            callTool(
                handler = saveHandler(),
                name = SEARCH_TOOL,
                arguments = buildJsonObject { put("query", "Kotlin") },
            )

        assertTrue(
            response
                .error()["message"]!!
                .jsonPrimitive
                .content
                .contains("Unknown tool"),
        )
    }

    @Test
    fun saveToFileWritesMarkdownAndRejectsUnsafeFileName() {
        val outputDir = temporaryFolder.newFolder("pipeline-output").toPath()
        val handler = saveHandler(outputDir)

        val saveResponse =
            callTool(
                handler = handler,
                name = SAVE_TO_FILE_TOOL,
                arguments =
                    buildJsonObject {
                        put("query", "Kotlin")
                        putJsonObject("summary") {
                            put("markdown", "# MCP pipeline summary\n\nKotlin summary")
                        }
                        put("fileName", "kotlin-summary.md")
                    },
            )

        val savedFileObject =
            saveResponse
                .result()
                .structuredContent()["savedFile"]!!
                .jsonObject
        val savedFile = savedFileObject["path"]!!.jsonPrimitive.content
        assertTrue(Files.exists(Path.of(savedFile)))
        assertEquals("# MCP pipeline summary\n\nKotlin summary", Files.readString(Path.of(savedFile)))
        assertEquals("kotlin-summary.md", savedFileObject["fileName"]!!.jsonPrimitive.content)
        assertEquals("# MCP pipeline summary\n\nKotlin summary", savedFileObject["markdown"]!!.jsonPrimitive.content)
        assertEquals(
            Files.size(Path.of(savedFile)).toString(),
            savedFileObject["byteSize"]!!.jsonPrimitive.content,
        )

        val unsafeResponse =
            callTool(
                handler = handler,
                name = SAVE_TO_FILE_TOOL,
                arguments =
                    buildJsonObject {
                        put("query", "Kotlin")
                        putJsonObject("summary") {
                            put("markdown", "# Unsafe")
                        }
                        put("fileName", "../escape.md")
                    },
            )
        val unsafeResult = unsafeResponse.result()
        assertEquals("true", unsafeResult["isError"]!!.jsonPrimitive.content)
        assertTrue(unsafeResult.textContent().contains("path separators"))
    }

    @Test
    fun handlerLevelPipelinePassesStructuredContentBetweenServers() {
        val outputDir = temporaryFolder.newFolder("pipeline-output").toPath()
        val searchHandler =
            McpJsonRpcHandler(
                toolRegistry =
                    McpToolRegistry(
                        listOf(
                            PipelineToolDefinitions.search(
                                object : SearchClient {
                                    override fun search(
                                        query: String,
                                        language: String,
                                        limit: Int,
                                    ): SearchResult = fakeSearchResult()
                                },
                            ),
                        ),
                    ),
                serverName = "pipeline-search-mcp",
                json = json,
            )
        val summarizeHandler =
            McpJsonRpcHandler(
                toolRegistry = McpToolRegistry(listOf(PipelineToolDefinitions.summarize())),
                serverName = "pipeline-summarize-mcp",
                json = json,
            )
        val saveHandler = saveHandler(outputDir)

        val search = callTool(searchHandler, SEARCH_TOOL, buildJsonObject { put("query", "Kotlin") }).result()
        val searchContent = search.structuredContent()
        val results = searchContent["results"]!!

        val summarize =
            callTool(
                handler = summarizeHandler,
                name = SUMMARIZE_TOOL,
                arguments =
                    buildJsonObject {
                        put("query", searchContent["query"]!!)
                        put("results", results)
                    },
            ).result()
        val summary = summarize.structuredContent()["summary"]!!

        val save =
            callTool(
                handler = saveHandler,
                name = SAVE_TO_FILE_TOOL,
                arguments =
                    buildJsonObject {
                        put("query", searchContent["query"]!!)
                        put("summary", summary)
                    },
            ).result()

        val savedFile = save.structuredContent()["savedFile"]!!.jsonObject
        val savedPath = savedFile["path"]!!.jsonPrimitive.content
        assertTrue(Files.exists(Path.of(savedPath)))
        assertEquals(savedFile["markdown"]!!.jsonPrimitive.content, Files.readString(Path.of(savedPath)))
        assertTrue(Files.readString(Path.of(savedPath)).contains("Kotlin programming language"))
    }

    private fun saveHandler(outputDir: Path = temporaryFolder.newFolder("pipeline-output").toPath()): McpJsonRpcHandler =
        McpJsonRpcHandler(
            toolRegistry =
                McpToolRegistry(
                    listOf(PipelineToolDefinitions.saveToFile(FileSummaryWriter(outputDir))),
                ),
            serverName = "pipeline-save-mcp",
            json = json,
        )

    private fun callTool(
        handler: McpJsonRpcHandler,
        name: String,
        arguments: JsonObject,
    ) = handler.handle(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 10)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", name)
                put("arguments", arguments)
            }
        }.toString(),
    )

    private fun com.akhavanskii.aichallenge.mcp.pipeline.HttpResponseBody.tools(): List<JsonObject> =
        json
            .parseToJsonElement(body)
            .jsonObject["result"]!!
            .jsonObject["tools"]!!
            .jsonArray
            .map { it.jsonObject }

    private fun com.akhavanskii.aichallenge.mcp.pipeline.HttpResponseBody.result(): JsonObject =
        json.parseToJsonElement(body).jsonObject["result"]!!.jsonObject

    private fun com.akhavanskii.aichallenge.mcp.pipeline.HttpResponseBody.error(): JsonObject =
        json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject

    private fun JsonObject.structuredContent(): JsonObject = this["structuredContent"]!!.jsonObject

    private fun JsonObject.textContent(): String =
        this["content"]!!
            .jsonArray
            .single()
            .jsonObject["text"]!!
            .jsonPrimitive
            .content

    private fun fakeSearchResult(): SearchResult =
        SearchResult.Success(
            listOf(
                SearchItem(
                    title = "Kotlin programming language",
                    snippet = "Kotlin is a modern language.",
                    url = "https://en.wikipedia.org/wiki/Kotlin",
                ),
            ),
        )
}
