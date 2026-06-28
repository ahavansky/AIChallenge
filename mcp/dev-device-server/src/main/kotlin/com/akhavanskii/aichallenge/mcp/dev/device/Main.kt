package com.akhavanskii.aichallenge.mcp.dev.device

import com.akhavanskii.aichallenge.mcp.dev.DevMcpHttpServer
import com.akhavanskii.aichallenge.mcp.dev.DevMcpJsonRpcHandler
import java.nio.file.Path

fun main() {
    val projectRoot = Path.of(System.getenv("MCP_DEV_PROJECT_ROOT") ?: "").toAbsolutePath().normalize()
    val port = System.getenv("MCP_DEV_DEVICE_SERVER_PORT")?.toIntOrNull() ?: DEFAULT_DEVICE_SERVER_PORT
    val handler =
        DevMcpJsonRpcHandler(
            toolRegistry = deviceDevToolRegistry(projectRoot),
            serverName = "dev-device-mcp",
        )
    val server =
        DevMcpHttpServer(
            port = port,
            handler = handler,
            sessionId = "dev-device-mcp-session",
        )
    server.start()
    println("Dev Device MCP server listening on http://localhost:$port/mcp")
}
