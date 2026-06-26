package com.akhavanskii.aichallenge.mcp.pipeline.save

import com.akhavanskii.aichallenge.mcp.pipeline.DEFAULT_SAVE_SERVER_PORT
import com.akhavanskii.aichallenge.mcp.pipeline.FileSummaryWriter
import com.akhavanskii.aichallenge.mcp.pipeline.McpJsonRpcHandler
import com.akhavanskii.aichallenge.mcp.pipeline.McpToolRegistry
import com.akhavanskii.aichallenge.mcp.pipeline.PipelineMcpHttpServer
import com.akhavanskii.aichallenge.mcp.pipeline.PipelineToolDefinitions
import java.nio.file.Path

fun main() {
    val port = System.getenv("MCP_SAVE_SERVER_PORT")?.toIntOrNull() ?: DEFAULT_SAVE_SERVER_PORT
    val outputDir =
        System
            .getenv("MCP_PIPELINE_OUTPUT_DIR")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(Path::of)
            ?: Path.of("build", "mcp-pipeline")
    val handler =
        McpJsonRpcHandler(
            toolRegistry = McpToolRegistry(listOf(PipelineToolDefinitions.saveToFile(FileSummaryWriter(outputDir)))),
            serverName = "pipeline-save-mcp",
        )
    val server =
        PipelineMcpHttpServer(
            port = port,
            handler = handler,
            sessionId = "pipeline-save-mcp-session",
        )
    server.start()
    println("Pipeline Save MCP server listening on http://localhost:$port/mcp")
}
