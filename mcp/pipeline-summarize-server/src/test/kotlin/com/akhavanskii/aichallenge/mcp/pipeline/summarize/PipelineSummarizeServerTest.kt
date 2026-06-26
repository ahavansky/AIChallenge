package com.akhavanskii.aichallenge.mcp.pipeline.summarize

import com.akhavanskii.aichallenge.mcp.pipeline.McpJsonRpcHandler
import com.akhavanskii.aichallenge.mcp.pipeline.McpToolRegistry
import com.akhavanskii.aichallenge.mcp.pipeline.PipelineToolDefinitions
import com.akhavanskii.aichallenge.mcp.pipeline.SEARCH_TOOL
import com.akhavanskii.aichallenge.mcp.pipeline.SUMMARIZE_TOOL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineSummarizeServerTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun toolsListExposesOnlySummarizeTool() {
        val tools = handler().handle("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").tools()

        assertEquals(listOf(SUMMARIZE_TOOL), tools.map { it["name"]!!.jsonPrimitive.content })
    }

    @Test
    fun wrongToolReturnsUnknownTool() {
        val response =
            callTool(
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
    fun summarizeConsumesSearchResultsAndReturnsSummaryObject() {
        val response =
            callTool(
                name = SUMMARIZE_TOOL,
                arguments =
                    buildJsonObject {
                        put("query", "Kotlin")
                        putJsonArray("results") {
                            add(
                                buildJsonObject {
                                    put("title", "Kotlin programming language")
                                    put("snippet", "Kotlin is a modern language.")
                                    put("url", "https://en.wikipedia.org/wiki/Kotlin")
                                },
                            )
                        }
                    },
            )

        val summary = response.result().structuredContent()["summary"]!!.jsonObject
        val markdown = summary["markdown"]!!.jsonPrimitive.content
        assertEquals("Kotlin", summary["query"]!!.jsonPrimitive.content)
        assertEquals("1", summary["sourceCount"]!!.jsonPrimitive.content)
        assertTrue(markdown.contains("Kotlin programming language: Kotlin is a modern language."))
    }

    private fun handler(): McpJsonRpcHandler =
        McpJsonRpcHandler(
            toolRegistry = McpToolRegistry(listOf(PipelineToolDefinitions.summarize())),
            serverName = "pipeline-summarize-mcp",
            json = json,
        )

    private fun callTool(
        name: String,
        arguments: JsonObject,
    ) = handler().handle(
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
}
