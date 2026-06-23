package com.akhavanskii.aichallenge.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.reflect.KClass

@OptIn(ExperimentalSerializationApi::class)
class McpClientTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun listToolsInitializesAndReturnsFetchTool() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    when {
                        request.bodyString().contains("\"method\":\"initialize\"") ->
                            jsonResponse(
                                request = request,
                                body =
                                    """
                                    {
                                      "jsonrpc": "2.0",
                                      "id": 1,
                                      "result": {
                                        "protocolVersion": "2025-06-18",
                                        "serverInfo": {"name": "fetch", "version": "1.0.0"},
                                        "capabilities": {"tools": {"listChanged": false}}
                                      }
                                    }
                                    """.trimIndent(),
                                headers = mapOf("Mcp-Session-Id" to "session-1"),
                            )
                        request.bodyString().contains("\"method\":\"notifications/initialized\"") ->
                            emptyResponse(request)
                        else ->
                            jsonResponse(
                                request = request,
                                body =
                                    """
                                    {
                                      "jsonrpc": "2.0",
                                      "id": 2,
                                      "result": {
                                        "tools": [
                                          {
                                            "name": "fetch",
                                            "title": "Fetch",
                                            "description": "Fetches a URL from the internet.",
                                            "inputSchema": {
                                              "type": "object",
                                              "properties": {"url": {"type": "string"}},
                                              "required": ["url"]
                                            }
                                          }
                                        ]
                                      }
                                    }
                                    """.trimIndent(),
                            )
                    }
                }
            val client = client(factory = factory)

            val result = client.listTools()

            assertTrue(result is McpDiscoveryResult.Success)
            val discovery = (result as McpDiscoveryResult.Success).value
            assertEquals(McpServerInfo(name = "fetch", version = "1.0.0"), discovery.serverInfo)
            assertTrue(discovery.toolsCapabilityAdvertised)
            assertEquals(1, discovery.tools.size)
            assertEquals("fetch", discovery.tools.first().name)
            assertEquals(listOf("url"), discovery.tools.first().requiredInputNames)
            assertEquals(3, factory.callCount)
            assertEquals("application/json, text/event-stream", factory.requests[0].header("Accept"))
            assertEquals("application/json", factory.requests[0].header("Content-Type"))
            assertEquals(MCP_PROTOCOL_VERSION, factory.requests[0].header("Mcp-Protocol-Version"))
            assertEquals("session-1", factory.requests[1].header("Mcp-Session-Id"))
            assertEquals("session-1", factory.requests[2].header("Mcp-Session-Id"))
        }

    @Test
    fun listToolsFollowsPagination() =
        runTest {
            var toolsListCalls = 0
            val factory =
                FakeCallFactory { request ->
                    val body = request.bodyString()
                    when {
                        body.contains("\"method\":\"initialize\"") ->
                            jsonResponse(
                                request = request,
                                body = initializedBody(),
                                headers = mapOf("Mcp-Session-Id" to "session-1"),
                            )
                        body.contains("\"method\":\"notifications/initialized\"") -> emptyResponse(request)
                        else -> {
                            toolsListCalls += 1
                            if (toolsListCalls == 1) {
                                jsonResponse(
                                    request = request,
                                    body =
                                        """
                                        {
                                          "jsonrpc": "2.0",
                                          "id": 2,
                                          "result": {
                                            "tools": [{"name": "first"}],
                                            "nextCursor": "cursor-1"
                                          }
                                        }
                                        """.trimIndent(),
                                )
                            } else {
                                assertTrue(body.contains("\"cursor\":\"cursor-1\""))
                                jsonResponse(
                                    request = request,
                                    body =
                                        """
                                        {
                                          "jsonrpc": "2.0",
                                          "id": 3,
                                          "result": {
                                            "tools": [{"name": "second"}]
                                          }
                                        }
                                        """.trimIndent(),
                                )
                            }
                        }
                    }
                }
            val client = client(factory = factory)

            val result = client.listTools()

            assertTrue(result is McpDiscoveryResult.Success)
            val tools = (result as McpDiscoveryResult.Success).value.tools
            assertEquals(listOf("first", "second"), tools.map { it.name })
            assertEquals(2, toolsListCalls)
            assertEquals(4, factory.callCount)
        }

    @Test
    fun listToolsParsesSseJsonResponse() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    when {
                        request.bodyString().contains("\"method\":\"initialize\"") ->
                            jsonResponse(request = request, body = initializedBody())
                        request.bodyString().contains("\"method\":\"notifications/initialized\"") -> emptyResponse(request)
                        else ->
                            textResponse(
                                request = request,
                                contentType = "text/event-stream",
                                body =
                                    """
                                    event: message
                                    data: {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"fetch"}]}}

                                    """.trimIndent(),
                            )
                    }
                }
            val client = client(factory = factory)

            val result = client.listTools()

            assertTrue(result is McpDiscoveryResult.Success)
            assertEquals(
                "fetch",
                (result as McpDiscoveryResult.Success)
                    .value
                    .tools
                    .single()
                    .name,
            )
        }

    @Test
    fun listToolsReturnsNoToolsWhenCapabilityMissing() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    when {
                        request.bodyString().contains("\"method\":\"initialize\"") ->
                            jsonResponse(
                                request = request,
                                body =
                                    """
                                    {
                                      "jsonrpc": "2.0",
                                      "id": 1,
                                      "result": {
                                        "serverInfo": {"name": "fetch", "version": "1.0.0"},
                                        "capabilities": {}
                                      }
                                    }
                                    """.trimIndent(),
                            )
                        else -> emptyResponse(request)
                    }
                }
            val client = client(factory = factory)

            val result = client.listTools()

            assertTrue(result is McpDiscoveryResult.Success)
            val discovery = (result as McpDiscoveryResult.Success).value
            assertEquals(false, discovery.toolsCapabilityAdvertised)
            assertTrue(discovery.tools.isEmpty())
            assertEquals(2, factory.callCount)
        }

    @Test
    fun listToolsReturnsMissingEndpointWithoutNetworkCall() =
        runTest {
            val factory = FakeCallFactory { error("network should not be called") }
            val client = client(serverUrl = "", factory = factory)

            val result = client.listTools()

            assertEquals(McpDiscoveryResult.Failure(McpNetworkError.MissingEndpoint), result)
            assertEquals(0, factory.callCount)
        }

    @Test
    fun listToolsMapsHttpError() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    jsonResponse(request = request, code = 503, body = """{"error":"unavailable"}""")
                }
            val client = client(factory = factory)

            val result = client.listTools()

            assertTrue(result is McpDiscoveryResult.Failure)
            assertEquals(503, ((result as McpDiscoveryResult.Failure).error as McpNetworkError.Http).statusCode)
        }

    @Test
    fun listToolsMapsJsonRpcError() =
        runTest {
            val factory =
                FakeCallFactory { request ->
                    jsonResponse(
                        request = request,
                        body =
                            """
                            {
                              "jsonrpc": "2.0",
                              "id": 1,
                              "error": {"code": "-32601", "message": "Method not found"}
                            }
                            """.trimIndent(),
                    )
                }
            val client = client(factory = factory)

            val result = client.listTools()

            assertTrue(result is McpDiscoveryResult.Failure)
            assertEquals(
                "Fetch MCP server returned JSON-RPC error -32601: Method not found.",
                (result as McpDiscoveryResult.Failure).error.userMessage,
            )
        }

    @Test
    fun listToolsMapsMalformedResponse() =
        runTest {
            val factory = FakeCallFactory { request -> textResponse(request = request, body = "not-json") }
            val client = client(factory = factory)

            val result = client.listTools()

            assertTrue(result is McpDiscoveryResult.Failure)
            assertTrue((result as McpDiscoveryResult.Failure).error is McpNetworkError.Serialization)
        }

    @Test
    fun listToolsMapsNetworkException() =
        runTest {
            val factory = FakeCallFactory { throw IOException("boom") }
            val client = client(factory = factory)

            val result = client.listTools()

            assertTrue(result is McpDiscoveryResult.Failure)
            assertTrue((result as McpDiscoveryResult.Failure).error is McpNetworkError.Network)
        }

    private fun client(
        serverUrl: String = "https://mcp.example.test/mcp",
        factory: Call.Factory,
    ): RestMcpClient =
        RestMcpClient(
            serverUrl = serverUrl,
            callFactory = factory,
            json = json,
            dispatcher = Dispatchers.Unconfined,
        )

    private fun initializedBody(): String =
        """
        {
          "jsonrpc": "2.0",
          "id": 1,
          "result": {
            "protocolVersion": "2025-06-18",
            "serverInfo": {"name": "fetch", "version": "1.0.0"},
            "capabilities": {"tools": {"listChanged": false}}
          }
        }
        """.trimIndent()

    private fun emptyResponse(request: Request): Response =
        textResponse(
            request = request,
            code = 202,
            body = "",
        )

    private fun jsonResponse(
        request: Request,
        code: Int = 200,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): Response =
        textResponse(
            request = request,
            code = code,
            body = body,
            contentType = "application/json",
            headers = headers,
        )

    private fun textResponse(
        request: Request,
        code: Int = 200,
        body: String,
        contentType: String = "text/plain",
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val builder =
            Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Error")
                .body(body.toResponseBody(contentType.toMediaType()))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private fun Request.bodyString(): String {
        val buffer = Buffer()
        body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private class FakeCallFactory(
        private val handler: (Request) -> Response,
    ) : Call.Factory {
        val requests = mutableListOf<Request>()
        val callCount: Int
            get() = requests.size

        override fun newCall(request: Request): Call {
            requests += request
            return FakeCall(request) { handler(request) }
        }
    }

    private class FakeCall(
        private val request: Request,
        private val executeHandler: () -> Response,
    ) : Call {
        override fun request(): Request = request

        override fun execute(): Response = executeHandler()

        override fun enqueue(responseCallback: Callback) {
            runCatching { execute() }
                .onSuccess { response -> responseCallback.onResponse(this, response) }
                .onFailure { throwable -> responseCallback.onFailure(this, throwable as IOException) }
        }

        override fun cancel() = Unit

        override fun isExecuted(): Boolean = false

        override fun isCanceled(): Boolean = false

        override fun timeout(): Timeout = Timeout.NONE

        override fun clone(): Call = FakeCall(request, executeHandler)

        override fun <T : Any> tag(type: KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(
            type: KClass<T>,
            computeIfAbsent: () -> T,
        ): T = computeIfAbsent()

        override fun <T : Any> tag(
            type: Class<T>,
            computeIfAbsent: () -> T,
        ): T = computeIfAbsent()
    }
}
