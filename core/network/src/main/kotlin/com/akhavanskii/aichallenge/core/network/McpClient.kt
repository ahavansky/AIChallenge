package com.akhavanskii.aichallenge.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named

const val MCP_FETCH_SERVER_URL_NAME = "McpFetchServerUrl"
const val MCP_SERVER_URL_NAME = MCP_FETCH_SERVER_URL_NAME

const val MCP_PROTOCOL_VERSION = "2025-06-18"

interface McpClient {
    suspend fun listTools(): McpDiscoveryResult<McpToolDiscovery>

    suspend fun callTool(
        name: String,
        arguments: JsonObject,
    ): McpToolCallResult
}

class RestMcpClient
    @Inject
    constructor(
        @param:Named(MCP_SERVER_URL_NAME) private val serverUrl: String,
        private val callFactory: Call.Factory,
        private val json: Json,
        @param:NetworkDispatcher private val dispatcher: CoroutineDispatcher,
    ) : McpClient {
        override suspend fun listTools(): McpDiscoveryResult<McpToolDiscovery> {
            val endpoint = serverUrl.trim()
            if (endpoint.isBlank()) {
                return McpDiscoveryResult.Failure(McpNetworkError.MissingEndpoint)
            }

            return withContext(dispatcher) {
                runCatching {
                    discoverTools(endpoint)
                }.getOrElse { throwable ->
                    if (throwable is CancellationException) throw throwable
                    Timber.tag(LOG_TAG).e(throwable, "MCP discovery failed before parsed result.")
                    when (throwable) {
                        is IOException -> McpDiscoveryResult.Failure(McpNetworkError.Network(throwable.message))
                        is SerializationException -> McpDiscoveryResult.Failure(McpNetworkError.Serialization(throwable.message))
                        is IllegalArgumentException -> McpDiscoveryResult.Failure(McpNetworkError.InvalidEndpoint(throwable.message))
                        else -> McpDiscoveryResult.Failure(McpNetworkError.Network(throwable.message))
                    }
                }
            }
        }

        override suspend fun callTool(
            name: String,
            arguments: JsonObject,
        ): McpToolCallResult {
            val endpoint = serverUrl.trim()
            if (endpoint.isBlank()) {
                return McpToolCallResult.Failure(McpNetworkError.MissingEndpoint)
            }
            val toolName = name.trim()
            if (toolName.isBlank()) {
                return McpToolCallResult.Failure(McpNetworkError.InvalidToolCall("Tool name is required."))
            }

            return withContext(dispatcher) {
                runCatching {
                    callRemoteTool(
                        endpoint = endpoint,
                        name = toolName,
                        arguments = arguments,
                    )
                }.getOrElse { throwable ->
                    if (throwable is CancellationException) throw throwable
                    Timber.tag(LOG_TAG).e(throwable, "MCP tool call failed before parsed result.")
                    when (throwable) {
                        is IOException -> McpToolCallResult.Failure(McpNetworkError.Network(throwable.message))
                        is SerializationException -> McpToolCallResult.Failure(McpNetworkError.Serialization(throwable.message))
                        is IllegalArgumentException -> McpToolCallResult.Failure(McpNetworkError.InvalidEndpoint(throwable.message))
                        else -> McpToolCallResult.Failure(McpNetworkError.Network(throwable.message))
                    }
                }
            }
        }

        private fun discoverTools(endpoint: String): McpDiscoveryResult<McpToolDiscovery> {
            val initialize =
                postJsonRpc(
                    endpoint = endpoint,
                    payload = initializeRequest(id = INITIALIZE_REQUEST_ID),
                    sessionId = null,
                    responseRequired = true,
                )
            val initialized =
                when (initialize) {
                    is McpExchangeResult.Failure -> return McpDiscoveryResult.Failure(initialize.error)
                    is McpExchangeResult.Success -> initialize
                }
            val initializeResult = initialized.result ?: return McpDiscoveryResult.Failure(McpNetworkError.EmptyResponse)
            val serverInfo = initializeResult.serverInfoOrNull()
            val toolsCapabilityAdvertised = initializeResult.hasToolsCapability()

            val notification =
                postJsonRpc(
                    endpoint = endpoint,
                    payload = initializedNotification(),
                    sessionId = initialized.sessionId,
                    responseRequired = false,
                )
            val notificationSessionId =
                when (notification) {
                    is McpExchangeResult.Failure -> return McpDiscoveryResult.Failure(notification.error)
                    is McpExchangeResult.Success -> notification.sessionId ?: initialized.sessionId
                }

            if (!toolsCapabilityAdvertised) {
                return McpDiscoveryResult.Success(
                    McpToolDiscovery(
                        serverInfo = serverInfo,
                        toolsCapabilityAdvertised = false,
                        tools = emptyList(),
                    ),
                )
            }

            val tools = mutableListOf<McpTool>()
            var cursor: String? = null
            do {
                val listResponse =
                    postJsonRpc(
                        endpoint = endpoint,
                        payload = toolsListRequest(id = nextRequestId(), cursor = cursor),
                        sessionId = notificationSessionId,
                        responseRequired = true,
                    )
                val listResult =
                    when (listResponse) {
                        is McpExchangeResult.Failure -> return McpDiscoveryResult.Failure(listResponse.error)
                        is McpExchangeResult.Success ->
                            listResponse.result ?: return McpDiscoveryResult.Failure(McpNetworkError.EmptyResponse)
                    }
                tools += listResult.tools()
                cursor = listResult.stringOrNull("nextCursor")
            } while (!cursor.isNullOrBlank())

            return McpDiscoveryResult.Success(
                McpToolDiscovery(
                    serverInfo = serverInfo,
                    toolsCapabilityAdvertised = true,
                    tools = tools,
                ),
            )
        }

        private fun callRemoteTool(
            endpoint: String,
            name: String,
            arguments: JsonObject,
        ): McpToolCallResult {
            val initialize =
                postJsonRpc(
                    endpoint = endpoint,
                    payload = initializeRequest(id = INITIALIZE_REQUEST_ID),
                    sessionId = null,
                    responseRequired = true,
                )
            val initialized =
                when (initialize) {
                    is McpExchangeResult.Failure -> return McpToolCallResult.Failure(initialize.error)
                    is McpExchangeResult.Success -> initialize
                }
            if (initialized.result == null) {
                return McpToolCallResult.Failure(McpNetworkError.EmptyResponse)
            }

            val notification =
                postJsonRpc(
                    endpoint = endpoint,
                    payload = initializedNotification(),
                    sessionId = initialized.sessionId,
                    responseRequired = false,
                )
            val notificationSessionId =
                when (notification) {
                    is McpExchangeResult.Failure -> return McpToolCallResult.Failure(notification.error)
                    is McpExchangeResult.Success -> notification.sessionId ?: initialized.sessionId
                }

            val callResponse =
                postJsonRpc(
                    endpoint = endpoint,
                    payload = toolsCallRequest(id = nextRequestId(), name = name, arguments = arguments),
                    sessionId = notificationSessionId,
                    responseRequired = true,
                )
            val callResult =
                when (callResponse) {
                    is McpExchangeResult.Failure -> return McpToolCallResult.Failure(callResponse.error)
                    is McpExchangeResult.Success ->
                        callResponse.result ?: return McpToolCallResult.Failure(McpNetworkError.EmptyResponse)
                }
            val contentText = callResult.contentText()
            if (contentText.isBlank()) {
                return McpToolCallResult.Failure(McpNetworkError.EmptyResponse)
            }
            return McpToolCallResult.Success(
                McpToolCall(
                    name = name,
                    contentText = contentText,
                    isError = callResult["isError"]?.jsonPrimitive?.booleanOrNull == true,
                ),
            )
        }

        private fun postJsonRpc(
            endpoint: String,
            payload: JsonObject,
            sessionId: String?,
            responseRequired: Boolean,
        ): McpExchangeResult {
            val requestJson = payload.toString()
            val requestBuilder =
                Request
                    .Builder()
                    .url(endpoint)
                    .header("Accept", MCP_ACCEPT_HEADER)
                    .header("Content-Type", JSON_MEDIA_TYPE.toString())
                    .header(MCP_PROTOCOL_VERSION_HEADER, MCP_PROTOCOL_VERSION)
                    .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
            sessionId?.takeIf { it.isNotBlank() }?.let { requestBuilder.header(MCP_SESSION_ID_HEADER, it) }

            callFactory.newCall(requestBuilder.build()).execute().use { response ->
                val responseBody = response.body.string()
                val responseSessionId =
                    response
                        .header(MCP_SESSION_ID_HEADER)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: sessionId

                if (!response.isSuccessful) {
                    return McpExchangeResult.Failure(
                        McpNetworkError.Http(
                            statusCode = response.code,
                            body = responseBody.takeIf { it.isNotBlank() },
                        ),
                    )
                }
                if (!responseRequired) {
                    return McpExchangeResult.Success(result = null, sessionId = responseSessionId)
                }
                if (responseBody.isBlank()) {
                    return McpExchangeResult.Failure(McpNetworkError.EmptyResponse)
                }

                val responseJson =
                    runCatching {
                        json.parseToJsonElement(responseBody.extractJsonRpcPayload()).jsonObject
                    }.getOrElse { throwable ->
                        return McpExchangeResult.Failure(McpNetworkError.Serialization(throwable.message))
                    }
                responseJson["error"]?.jsonObject?.let { errorObject ->
                    return McpExchangeResult.Failure(
                        McpNetworkError.JsonRpc(
                            code = errorObject["code"]?.jsonPrimitive?.contentOrNull,
                            message = errorObject["message"]?.jsonPrimitive?.contentOrNull,
                        ),
                    )
                }

                val result =
                    responseJson["result"]?.jsonObject
                        ?: return McpExchangeResult.Failure(McpNetworkError.EmptyResponse)
                return McpExchangeResult.Success(result = result, sessionId = responseSessionId)
            }
        }

        private fun initializeRequest(id: Long): JsonObject =
            buildJsonObject {
                put("jsonrpc", JSON_RPC_VERSION)
                put("id", id)
                put("method", "initialize")
                putJsonObject("params") {
                    put("protocolVersion", MCP_PROTOCOL_VERSION)
                    putJsonObject("capabilities") {}
                    putJsonObject("clientInfo") {
                        put("name", "aichallenge-android")
                        put("version", "1.0.0")
                    }
                }
            }

        private fun initializedNotification(): JsonObject =
            buildJsonObject {
                put("jsonrpc", JSON_RPC_VERSION)
                put("method", "notifications/initialized")
            }

        private fun toolsListRequest(
            id: Long,
            cursor: String?,
        ): JsonObject =
            buildJsonObject {
                put("jsonrpc", JSON_RPC_VERSION)
                put("id", id)
                put("method", "tools/list")
                if (!cursor.isNullOrBlank()) {
                    putJsonObject("params") {
                        put("cursor", cursor)
                    }
                }
            }

        private fun toolsCallRequest(
            id: Long,
            name: String,
            arguments: JsonObject,
        ): JsonObject =
            buildJsonObject {
                put("jsonrpc", JSON_RPC_VERSION)
                put("id", id)
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", name)
                    put("arguments", arguments)
                }
            }

        private fun nextRequestId(): Long = requestId++

        private fun JsonObject.serverInfoOrNull(): McpServerInfo? {
            val serverInfo = this["serverInfo"]?.jsonObject ?: return null
            val name = serverInfo.stringOrNull("name")?.takeIf { it.isNotBlank() } ?: return null
            return McpServerInfo(
                name = name,
                version = serverInfo.stringOrNull("version"),
            )
        }

        private fun JsonObject.hasToolsCapability(): Boolean =
            this["capabilities"]
                ?.jsonObject
                ?.containsKey("tools") == true

        private fun JsonObject.tools(): List<McpTool> =
            runCatching { this["tools"]?.jsonArray }
                .getOrNull()
                ?.mapNotNull { element ->
                    val tool = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val name = tool.stringOrNull("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val inputSchema = runCatching { tool["inputSchema"]?.jsonObject }.getOrNull()
                    McpTool(
                        name = name,
                        title = tool.stringOrNull("title"),
                        description = tool.stringOrNull("description"),
                        inputSchemaJson = inputSchema?.toString().orEmpty(),
                        requiredInputNames = inputSchema.requiredInputNames(),
                    )
                }.orEmpty()

        private fun JsonObject.contentText(): String =
            runCatching { this["content"]?.jsonArray }
                .getOrNull()
                ?.mapNotNull { element ->
                    val content = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                    if (content.stringOrNull("type") != "text") return@mapNotNull null
                    content.stringOrNull("text")?.takeIf { it.isNotBlank() }
                }?.joinToString(separator = "\n")
                .orEmpty()

        private fun JsonObject?.requiredInputNames(): List<String> =
            runCatching {
                this
                    ?.get("required")
                    ?.jsonArray
                    ?.mapNotNull { element -> element.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() } }
            }.getOrNull().orEmpty()

        private fun JsonObject.stringOrNull(key: String): String? =
            this[key]
                ?.jsonPrimitive
                ?.contentOrNull

        private fun String.extractJsonRpcPayload(): String {
            val trimmed = trim()
            if (trimmed.startsWith("{")) return trimmed

            val eventPayload =
                trimmed
                    .splitToSequence("\n\n")
                    .mapNotNull { event ->
                        event
                            .lineSequence()
                            .map { line -> line.trimEnd('\r') }
                            .filter { line -> line.startsWith("data:") }
                            .joinToString(separator = "\n") { line -> line.removePrefix("data:").trimStart() }
                            .trim()
                            .takeIf { data -> data.startsWith("{") }
                    }.firstOrNull()

            return eventPayload ?: trimmed
        }

        private var requestId = TOOLS_REQUEST_ID

        private sealed interface McpExchangeResult {
            data class Success(
                val result: JsonObject?,
                val sessionId: String?,
            ) : McpExchangeResult

            data class Failure(
                val error: McpNetworkError,
            ) : McpExchangeResult
        }

        private companion object {
            const val LOG_TAG = "RestMcpClient"
            const val JSON_RPC_VERSION = "2.0"
            const val INITIALIZE_REQUEST_ID = 1L
            const val TOOLS_REQUEST_ID = 2L
            const val MCP_ACCEPT_HEADER = "application/json, text/event-stream"
            const val MCP_SESSION_ID_HEADER = "Mcp-Session-Id"
            const val MCP_PROTOCOL_VERSION_HEADER = "Mcp-Protocol-Version"

            val JSON_MEDIA_TYPE = "application/json".toMediaType()
        }
    }

sealed interface McpDiscoveryResult<out T> {
    data class Success<T>(
        val value: T,
    ) : McpDiscoveryResult<T>

    data class Failure(
        val error: McpNetworkError,
    ) : McpDiscoveryResult<Nothing>
}

data class McpToolDiscovery(
    val serverInfo: McpServerInfo?,
    val toolsCapabilityAdvertised: Boolean,
    val tools: List<McpTool>,
)

data class McpServerInfo(
    val name: String,
    val version: String?,
)

data class McpTool(
    val name: String,
    val title: String?,
    val description: String?,
    val inputSchemaJson: String,
    val requiredInputNames: List<String>,
)

sealed interface McpToolCallResult {
    data class Success(
        val value: McpToolCall,
    ) : McpToolCallResult

    data class Failure(
        val error: McpNetworkError,
    ) : McpToolCallResult
}

data class McpToolCall(
    val name: String,
    val contentText: String,
    val isError: Boolean,
)

sealed interface McpNetworkError {
    val userMessage: String

    data object MissingEndpoint : McpNetworkError {
        override val userMessage: String =
            "MCP server URL is missing. Add MCP_SERVER_URL to local.properties or your environment."
    }

    data class InvalidEndpoint(
        val cause: String?,
    ) : McpNetworkError {
        override val userMessage: String = "MCP server URL is invalid."
    }

    data class InvalidToolCall(
        val cause: String?,
    ) : McpNetworkError {
        override val userMessage: String = cause ?: "MCP tool call is invalid."
    }

    data class Http(
        val statusCode: Int,
        val body: String?,
    ) : McpNetworkError {
        override val userMessage: String = "MCP request failed with HTTP $statusCode."
    }

    data class JsonRpc(
        val code: String?,
        val message: String?,
    ) : McpNetworkError {
        override val userMessage: String =
            buildString {
                append("MCP server returned JSON-RPC error")
                code?.let { append(" $it") }
                message?.let { append(": $it") }
                append(".")
            }
    }

    data class Network(
        val cause: String?,
    ) : McpNetworkError {
        override val userMessage: String = "Network error while contacting MCP server."
    }

    data class Serialization(
        val cause: String?,
    ) : McpNetworkError {
        override val userMessage: String = "MCP response could not be parsed."
    }

    data object EmptyResponse : McpNetworkError {
        override val userMessage: String = "MCP server returned an empty response."
    }
}
