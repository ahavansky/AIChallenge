package com.akhavanskii.aichallenge.mcp.pipeline.search

import com.akhavanskii.aichallenge.mcp.pipeline.McpJsonRpcHandler
import com.akhavanskii.aichallenge.mcp.pipeline.McpToolRegistry
import com.akhavanskii.aichallenge.mcp.pipeline.PipelineToolDefinitions
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineSearchServerTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun toolsListExposesOnlySearchTool() {
        val handler = handler()

        val tools = handler.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").tools()

        assertEquals(listOf(SEARCH_TOOL), tools.map { it["name"]!!.jsonPrimitive.content })
        assertTrue(
            tools
                .single()["inputSchema"]!!
                .jsonObject["required"]!!
                .jsonArray
                .any { it.jsonPrimitive.content == "query" },
        )
    }

    @Test
    fun wrongToolReturnsUnknownTool() {
        val response =
            callTool(
                handler = handler(),
                name = SUMMARIZE_TOOL,
                arguments = buildJsonObject { put("query", "Kotlin") },
            )

        val error = response.error()
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("Unknown tool"))
    }

    @Test
    fun searchReturnsStructuredResultsFromSearchClient() {
        val result =
            callTool(
                handler = handler(),
                name = SEARCH_TOOL,
                arguments =
                    buildJsonObject {
                        put("query", "Kotlin")
                        put("language", "en")
                        put("limit", 2)
                    },
            ).result()

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

    private fun handler(): McpJsonRpcHandler =
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
                SearchItem(
                    title = "Kotlin Multiplatform",
                    snippet = "Kotlin supports multiplatform development.",
                    url = "https://en.wikipedia.org/wiki/Kotlin_Multiplatform",
                ),
            ),
        )
}
