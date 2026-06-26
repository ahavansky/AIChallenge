package com.akhavanskii.aichallenge.mcp.pipeline.summarize

import com.akhavanskii.aichallenge.mcp.pipeline.DEFAULT_SUMMARIZE_SERVER_PORT
import com.akhavanskii.aichallenge.mcp.pipeline.McpJsonRpcHandler
import com.akhavanskii.aichallenge.mcp.pipeline.McpToolRegistry
import com.akhavanskii.aichallenge.mcp.pipeline.PipelineMcpHttpServer
import com.akhavanskii.aichallenge.mcp.pipeline.PipelineToolDefinitions

fun main() {
    val port = System.getenv("MCP_SUMMARIZE_SERVER_PORT")?.toIntOrNull() ?: DEFAULT_SUMMARIZE_SERVER_PORT
    val handler =
        McpJsonRpcHandler(
            toolRegistry = McpToolRegistry(listOf(PipelineToolDefinitions.summarize())),
            serverName = "pipeline-summarize-mcp",
        )
    val server =
        PipelineMcpHttpServer(
            port = port,
            handler = handler,
            sessionId = "pipeline-summarize-mcp-session",
        )
    server.start()
    println("Pipeline Summarize MCP server listening on http://localhost:$port/mcp")
}
