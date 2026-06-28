package com.akhavanskii.aichallenge.mcp.dev.buildserver

import com.akhavanskii.aichallenge.mcp.dev.DevMcpHttpServer
import com.akhavanskii.aichallenge.mcp.dev.DevMcpJsonRpcHandler
import java.nio.file.Path

fun main() {
    val projectRoot = Path.of(System.getenv("MCP_DEV_PROJECT_ROOT") ?: "").toAbsolutePath().normalize()
    val port = System.getenv("MCP_DEV_BUILD_SERVER_PORT")?.toIntOrNull() ?: DEFAULT_BUILD_SERVER_PORT
    val handler =
        DevMcpJsonRpcHandler(
            toolRegistry = buildDevToolRegistry(projectRoot),
            serverName = "dev-build-mcp",
        )
    val server =
        DevMcpHttpServer(
            port = port,
            handler = handler,
            sessionId = "dev-build-mcp-session",
        )
    server.start()
    println("Dev Build MCP server listening on http://localhost:$port/mcp")
}
