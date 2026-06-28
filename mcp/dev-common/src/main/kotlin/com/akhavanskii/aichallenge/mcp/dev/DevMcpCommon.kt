package com.akhavanskii.aichallenge.mcp.dev

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
import java.nio.charset.StandardCharsets

class DevMcpHttpServer(
    private val port: Int,
    private val handler: DevMcpJsonRpcHandler,
    private val sessionId: String,
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
        exchange.responseHeaders.add("Mcp-Session-Id", sessionId)
        exchange.writeText(statusCode = response.statusCode, body = response.body)
    }
}

class DevMcpJsonRpcHandler(
    private val toolRegistry: DevMcpToolRegistry,
    private val serverName: String,
    private val json: Json = DEV_JSON,
) {
    fun handle(requestBody: String): DevHttpResponseBody {
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
            "notifications/initialized" -> DevHttpResponseBody(statusCode = 202, body = "")
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

    private fun initialize(id: JsonElement): DevHttpResponseBody =
        jsonRpcResult(
            id = id,
            result =
                buildJsonObject {
                    put("protocolVersion", MCP_PROTOCOL_VERSION)
                    putJsonObject("serverInfo") {
                        put("name", serverName)
                        put("version", "1.0.0")
                    }
                    putJsonObject("capabilities") {
                        putJsonObject("tools") {
                            put("listChanged", false)
                        }
                    }
                },
        )

    private fun toolsList(id: JsonElement): DevHttpResponseBody =
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
    ): DevHttpResponseBody {
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
    ): DevHttpResponseBody =
        DevHttpResponseBody(
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
    ): DevHttpResponseBody =
        DevHttpResponseBody(
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

data class DevHttpResponseBody(
    val statusCode: Int,
    val body: String,
)

data class DevMcpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val handler: (JsonObject) -> DevMcpToolCallResult,
)

class DevMcpToolRegistry(
    val tools: List<DevMcpToolDefinition>,
) {
    private val toolsByName = tools.associateBy { it.name }

    fun call(
        name: String,
        arguments: JsonObject,
    ): DevMcpToolCallResult? = toolsByName[name]?.handler?.invoke(arguments)
}

data class DevMcpToolCallResult(
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
        fun error(message: String): DevMcpToolCallResult = DevMcpToolCallResult(text = message, isError = true)
    }
}

fun objectSchema(
    properties: Map<String, JsonObject>,
    required: List<String> = emptyList(),
): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            properties.forEach { (name, schema) -> put(name, schema) }
        }
        if (required.isNotEmpty()) {
            put("required", JsonArray(required.map(::JsonPrimitive)))
        }
        put("additionalProperties", false)
    }

fun stringSchema(
    description: String,
    enumValues: List<String> = emptyList(),
    minLength: Int? = null,
    maxLength: Int? = null,
): JsonObject =
    buildJsonObject {
        put("type", "string")
        put("description", description)
        if (enumValues.isNotEmpty()) put("enum", JsonArray(enumValues.map(::JsonPrimitive)))
        minLength?.let { put("minLength", it) }
        maxLength?.let { put("maxLength", it) }
    }

fun integerSchema(
    description: String,
    minimum: Int,
    maximum: Int,
    default: Int? = null,
): JsonObject =
    buildJsonObject {
        put("type", "integer")
        put("description", description)
        put("minimum", minimum)
        put("maximum", maximum)
        default?.let { put("default", it) }
    }

fun JsonObject.stringOrNull(key: String): String? =
    this[key]
        ?.jsonPrimitiveOrNull()
        ?.contentOrNull

fun JsonObject.intOrNull(key: String): Int? =
    this[key]
        ?.jsonPrimitiveOrNull()
        ?.intOrNull

fun JsonObject.booleanOrNull(key: String): Boolean? =
    this[key]
        ?.jsonPrimitiveOrNull()
        ?.booleanOrNull

fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

fun JsonElement.jsonArrayOrNull(): JsonArray? = runCatching { jsonArray }.getOrNull()

fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = runCatching { jsonPrimitive }.getOrNull()

private fun HttpExchange.writeText(
    statusCode: Int,
    body: String,
) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    sendResponseHeaders(statusCode, bytes.size.toLong())
    responseBody.use { output -> output.write(bytes) }
}

const val MCP_PROTOCOL_VERSION = "2025-06-18"
val DEV_JSON: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

private const val JSON_RPC_VERSION = "2.0"
private const val JSON_RPC_PARSE_ERROR = -32700
private const val JSON_RPC_METHOD_NOT_FOUND = -32601
private const val JSON_RPC_INVALID_PARAMS = -32602
