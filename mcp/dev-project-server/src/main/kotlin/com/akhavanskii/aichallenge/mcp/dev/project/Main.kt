package com.akhavanskii.aichallenge.mcp.dev.project

import com.akhavanskii.aichallenge.mcp.dev.DevMcpHttpServer
import com.akhavanskii.aichallenge.mcp.dev.DevMcpJsonRpcHandler
import java.nio.file.Path

fun main() {
    val projectRoot = Path.of(System.getenv("MCP_DEV_PROJECT_ROOT") ?: "").toAbsolutePath().normalize()
    val port = System.getenv("MCP_DEV_PROJECT_SERVER_PORT")?.toIntOrNull() ?: DEFAULT_PROJECT_SERVER_PORT
    val handler =
        DevMcpJsonRpcHandler(
            toolRegistry = projectDevToolRegistry(projectRoot),
            serverName = "dev-project-mcp",
        )
    val server =
        DevMcpHttpServer(
            port = port,
            handler = handler,
            sessionId = "dev-project-mcp-session",
        )
    server.start()
    println("Dev Project MCP server listening on http://localhost:$port/mcp")
}
