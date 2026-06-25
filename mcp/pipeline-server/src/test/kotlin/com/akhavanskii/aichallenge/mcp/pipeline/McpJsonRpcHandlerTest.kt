package com.akhavanskii.aichallenge.mcp.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class McpJsonRpcHandlerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun toolsListExposesPipelineToolSchemas() {
        val handler = handler()

        val response = handler.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")

        assertEquals(200, response.statusCode)
        val tools =
            json
                .parseToJsonElement(response.body)
                .jsonObject["result"]!!
                .jsonObject["tools"]!!
                .jsonArray
                .map { it.jsonObject }
        assertEquals(listOf(SEARCH_TOOL, SUMMARIZE_TOOL, SAVE_TO_FILE_TOOL), tools.map { it["name"]!!.jsonPrimitive.content })
        val searchSchema = tools.first { it["name"]!!.jsonPrimitive.content == SEARCH_TOOL }["inputSchema"]!!.jsonObject
        assertEquals("object", searchSchema["type"]!!.jsonPrimitive.content)
        assertTrue(searchSchema["required"]!!.jsonArray.any { it.jsonPrimitive.content == "query" })
    }

    @Test
    fun searchReturnsStructuredResultsFromSearchClient() {
        val handler = handler()

        val response =
            handler.handle(
                """
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "method": "tools/call",
                  "params": {
                    "name": "search",
                    "arguments": {"query": "Kotlin", "language": "en", "limit": 2}
                  }
                }
                """.trimIndent(),
            )

        val result = response.result()
        assertFalse(result.containsKey("isError"))
        assertTrue(result.textContent().contains("Kotlin programming language"))
        val structuredContent = result.structuredContent()
        assertEquals("Kotlin", structuredContent["query"]!!.jsonPrimitive.content)
        val results = structuredContent["results"]!!.jsonArray
        assertEquals(2, results.size)
        assertEquals(
            "Kotlin programming language",
            results
                .first()
                .jsonObject["title"]!!
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun summarizeConsumesSearchResultsAndReturnsSummaryObject() {
        val handler = handler()

        val response =
            handler.handle(
                """
                {
                  "jsonrpc": "2.0",
                  "id": 3,
                  "method": "tools/call",
                  "params": {
                    "name": "summarize",
                    "arguments": {
                      "query": "Kotlin",
                      "results": [
                        {
                          "title": "Kotlin programming language",
                          "snippet": "Kotlin is a modern language.",
                          "url": "https://en.wikipedia.org/wiki/Kotlin"
                        }
                      ]
                    }
                  }
                }
                """.trimIndent(),
            )

        val summary = response.result().structuredContent()["summary"]!!.jsonObject
        val markdown = summary["markdown"]!!.jsonPrimitive.content
        assertEquals("Kotlin", summary["query"]!!.jsonPrimitive.content)
        assertEquals("1", summary["sourceCount"]!!.jsonPrimitive.content)
        assertTrue(markdown.contains("Kotlin programming language: Kotlin is a modern language."))
    }

    @Test
    fun saveToFileWritesMarkdownAndRejectsUnsafeFileName() {
        val outputDir = temporaryFolder.newFolder("pipeline-output").toPath()
        val handler = handler(outputDir = outputDir)

        val saveResponse =
            handler.handle(
                saveRequest(
                    fileName = "kotlin-summary.md",
                    markdown = "# MCP pipeline summary\n\nKotlin summary",
                ),
            )

        val savedFileObject =
            saveResponse
                .result()
                .structuredContent()["savedFile"]!!
                .jsonObject
        val savedFile =
            savedFileObject["path"]!!
                .jsonPrimitive
                .content
        assertTrue(Files.exists(Path.of(savedFile)))
        assertEquals("# MCP pipeline summary\n\nKotlin summary", Files.readString(Path.of(savedFile)))
        assertEquals("kotlin-summary.md", savedFileObject["fileName"]!!.jsonPrimitive.content)
        assertEquals("# MCP pipeline summary\n\nKotlin summary", savedFileObject["markdown"]!!.jsonPrimitive.content)
        assertEquals(
            Files.size(Path.of(savedFile)).toString(),
            savedFileObject["byteSize"]!!.jsonPrimitive.content,
        )

        val unsafeResponse =
            handler.handle(
                saveRequest(
                    fileName = "../escape.md",
                    markdown = "# Unsafe",
                ),
            )
        val unsafeResult = unsafeResponse.result()
        assertEquals("true", unsafeResult["isError"]!!.jsonPrimitive.content)
        assertTrue(unsafeResult.textContent().contains("path separators"))
    }

    @Test
    fun handlerLevelPipelinePassesStructuredContentBetweenTools() {
        val outputDir = temporaryFolder.newFolder("pipeline-output").toPath()
        val handler = handler(outputDir = outputDir)

        val search = callTool(handler, SEARCH_TOOL, buildJsonObject { put("query", "Kotlin") })
        val searchContent = search.structuredContent()
        val results = searchContent["results"]!!

        val summarize =
            callTool(
                handler,
                SUMMARIZE_TOOL,
                buildJsonObject {
                    put("query", searchContent["query"]!!)
                    put("results", results)
                },
            )
        val summary = summarize.structuredContent()["summary"]!!

        val save =
            callTool(
                handler,
                SAVE_TO_FILE_TOOL,
                buildJsonObject {
                    put("query", searchContent["query"]!!)
                    put("summary", summary)
                },
            )

        val savedPath =
            save
                .structuredContent()["savedFile"]!!
                .jsonObject["path"]!!
                .jsonPrimitive
                .content
        assertTrue(Files.exists(Path.of(savedPath)))
        assertTrue(Files.readString(Path.of(savedPath)).contains("Kotlin programming language"))
    }

    private fun handler(
        outputDir: Path = temporaryFolder.newFolder("pipeline-output").toPath(),
        searchResult: SearchResult = fakeSearchResult(),
    ): McpJsonRpcHandler =
        McpJsonRpcHandler(
            McpToolRegistry.pipeline(
                searchClient =
                    object : SearchClient {
                        override fun search(
                            query: String,
                            language: String,
                            limit: Int,
                        ): SearchResult = searchResult
                    },
                summaryWriter = FileSummaryWriter(outputDir),
            ),
        )

    private fun callTool(
        handler: McpJsonRpcHandler,
        name: String,
        arguments: JsonObject,
    ): JsonObject {
        val response =
            handler.handle(
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
        return response.result()
    }

    private fun saveRequest(
        fileName: String,
        markdown: String,
    ): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 4)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", SAVE_TO_FILE_TOOL)
                putJsonObject("arguments") {
                    put("query", "Kotlin")
                    putJsonObject("summary") {
                        put("markdown", markdown)
                    }
                    put("fileName", fileName)
                }
            }
        }.toString()

    private fun HttpResponseBody.result(): JsonObject =
        json
            .parseToJsonElement(body)
            .jsonObject["result"]!!
            .jsonObject

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
                SearchItem(
                    title = "Kotlin Multiplatform",
                    snippet = "Kotlin supports multiplatform development.",
                    url = "https://en.wikipedia.org/wiki/Kotlin_Multiplatform",
                ),
            ),
        )
}
