package com.akhavanskii.aichallenge

import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigModuleTest {
    @Test
    fun pipelineMcpServerDefaultsUseThreeSeparatePorts() {
        assertEquals("http://10.0.2.2:8766/mcp", DEFAULT_ANDROID_MCP_SEARCH_SERVER_URL)
        assertEquals("http://10.0.2.2:8767/mcp", DEFAULT_ANDROID_MCP_SUMMARIZE_SERVER_URL)
        assertEquals("http://10.0.2.2:8768/mcp", DEFAULT_ANDROID_MCP_SAVE_SERVER_URL)
    }

    @Test
    fun blankMcpServerUrlFallsBackToAndroidEmulatorHostLoopback() {
        assertEquals(
            DEFAULT_ANDROID_MCP_SERVER_URL,
            "".toAndroidMcpServerUrl(),
        )
    }

    @Test
    fun localhostMcpServerUrlUsesAndroidEmulatorHostLoopback() {
        assertEquals(
            "http://10.0.2.2:8765/mcp",
            "http://localhost:8765/mcp".toAndroidMcpServerUrl(),
        )
    }

    @Test
    fun loopbackMcpServerUrlUsesAndroidEmulatorHostLoopback() {
        assertEquals(
            "http://10.0.2.2:8765/mcp",
            "http://127.0.0.1:8765/mcp".toAndroidMcpServerUrl(),
        )
    }

    @Test
    fun pipelineLoopbackMcpServerUrlUsesAndroidEmulatorHostLoopback() {
        assertEquals(
            "http://10.0.2.2:8768/mcp",
            "http://localhost:8768/mcp".toAndroidMcpServerUrl(),
        )
    }

    @Test
    fun explicitRemoteMcpServerUrlIsPreserved() {
        assertEquals(
            "http://192.168.1.10:8765/mcp",
            "http://192.168.1.10:8765/mcp".toAndroidMcpServerUrl(),
        )
    }
}
