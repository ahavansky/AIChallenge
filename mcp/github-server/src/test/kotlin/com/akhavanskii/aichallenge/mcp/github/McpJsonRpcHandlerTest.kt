package com.akhavanskii.aichallenge.mcp.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpJsonRpcHandlerTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun toolsListExposesGithubRepositorySummarySchema() {
        val handler = handler()

        val response =
            handler.handle(
                """
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                """.trimIndent(),
            )

        assertEquals(200, response.statusCode)
        val tool =
            json
                .parseToJsonElement(response.body)
                .jsonObject["result"]!!
                .jsonObject["tools"]!!
                .jsonArray
                .single()
                .jsonObject
        assertEquals(GITHUB_REPOSITORY_SUMMARY_TOOL, tool["name"]!!.jsonPrimitive.content)
        val schema = tool["inputSchema"]!!.jsonObject
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertTrue(schema["required"]!!.jsonArray.any { it.jsonPrimitive.content == "owner" })
        assertTrue(schema["required"]!!.jsonArray.any { it.jsonPrimitive.content == "repo" })
    }

    @Test
    fun toolsCallReturnsLiveGithubSummaryFromClientResult() {
        val handler =
            handler(
                result =
                    GitHubRepositoryResult.Success(
                        GitHubRepositorySummary(
                            fullName = "square/okhttp",
                            htmlUrl = "https://github.com/square/okhttp",
                            description = "Square's meticulous HTTP client.",
                            stargazersCount = 47000,
                            forksCount = 9200,
                            openIssuesCount = 100,
                            defaultBranch = "master",
                            licenseName = "Apache-2.0",
                            language = "Kotlin",
                            updatedAt = "2026-06-01T00:00:00Z",
                        ),
                    ),
            )

        val response =
            handler.handle(
                """
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "method": "tools/call",
                  "params": {
                    "name": "github_repository_summary",
                    "arguments": {"owner": "square", "repo": "okhttp"}
                  }
                }
                """.trimIndent(),
            )

        val result =
            json
                .parseToJsonElement(response.body)
                .jsonObject["result"]!!
                .jsonObject
        assertFalse(result.containsKey("isError"))
        val text = result.textContent()
        assertTrue(text.contains("Full name: square/okhttp"))
        assertTrue(text.contains("Stars: 47000"))
        assertTrue(text.contains("Language: Kotlin"))
    }

    @Test
    fun toolsCallReturnsToolErrorForInvalidArgs() {
        val handler = handler()

        val response =
            handler.handle(
                """
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "method": "tools/call",
                  "params": {
                    "name": "github_repository_summary",
                    "arguments": {"owner": "bad owner", "repo": "okhttp"}
                  }
                }
                """.trimIndent(),
            )

        val result =
            json
                .parseToJsonElement(response.body)
                .jsonObject["result"]!!
                .jsonObject
        assertEquals("true", result["isError"]!!.jsonPrimitive.content)
        val text = result.textContent()
        assertTrue(text.contains("valid GitHub path segment"))
    }

    @Test
    fun toolsCallMapsClientFailureToToolError() {
        val handler = handler(GitHubRepositoryResult.Failure("Repository not found: square/missing."))

        val response =
            handler.handle(
                """
                {
                  "jsonrpc": "2.0",
                  "id": 2,
                  "method": "tools/call",
                  "params": {
                    "name": "github_repository_summary",
                    "arguments": {"owner": "square", "repo": "missing"}
                  }
                }
                """.trimIndent(),
            )

        val result =
            json
                .parseToJsonElement(response.body)
                .jsonObject["result"]!!
                .jsonObject
        assertEquals("true", result["isError"]!!.jsonPrimitive.content)
        val text = result.textContent()
        assertEquals("Repository not found: square/missing.", text)
    }

    private fun JsonObject.textContent(): String =
        this["content"]!!
            .jsonArray
            .single()
            .jsonObject["text"]!!
            .jsonPrimitive
            .content

    private fun handler(
        result: GitHubRepositoryResult =
            GitHubRepositoryResult.Failure("GitHub rate limit or abuse protection blocked the request."),
    ): McpJsonRpcHandler =
        McpJsonRpcHandler(
            McpToolRegistry.github(
                object : GitHubRepositoryClient {
                    override fun repositorySummary(
                        owner: String,
                        repo: String,
                    ): GitHubRepositoryResult = result
                },
            ),
        )
}
