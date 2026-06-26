package com.akhavanskii.aichallenge.mcp.pipeline.search

import com.akhavanskii.aichallenge.mcp.pipeline.DEFAULT_SEARCH_SERVER_PORT
import com.akhavanskii.aichallenge.mcp.pipeline.LiveWikipediaSearchClient
import com.akhavanskii.aichallenge.mcp.pipeline.McpJsonRpcHandler
import com.akhavanskii.aichallenge.mcp.pipeline.McpToolRegistry
import com.akhavanskii.aichallenge.mcp.pipeline.PipelineMcpHttpServer
import com.akhavanskii.aichallenge.mcp.pipeline.PipelineToolDefinitions

fun main() {
    val port = System.getenv("MCP_SEARCH_SERVER_PORT")?.toIntOrNull() ?: DEFAULT_SEARCH_SERVER_PORT
    val handler =
        McpJsonRpcHandler(
            toolRegistry = McpToolRegistry(listOf(PipelineToolDefinitions.search(LiveWikipediaSearchClient()))),
            serverName = "pipeline-search-mcp",
        )
    val server =
        PipelineMcpHttpServer(
            port = port,
            handler = handler,
            sessionId = "pipeline-search-mcp-session",
        )
    server.start()
    println("Pipeline Search MCP server listening on http://localhost:$port/mcp")
}
