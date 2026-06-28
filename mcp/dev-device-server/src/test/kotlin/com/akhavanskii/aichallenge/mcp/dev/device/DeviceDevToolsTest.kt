package com.akhavanskii.aichallenge.mcp.dev.device

import com.akhavanskii.aichallenge.mcp.dev.DevProcessResult
import com.akhavanskii.aichallenge.mcp.dev.DevProcessRunner
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration

class DeviceDevToolsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exposesOnlyDeviceTools() {
        val registry = deviceDevToolRegistry(temporaryFolder.newFolder("project").toPath(), FakeProcessRunner())

        assertEquals(listOf(DEVICE_STATUS_TOOL, UI_SNAPSHOT_TOOL, LOGCAT_EXCERPT_TOOL), registry.tools.map { it.name })
    }

    @Test
    fun deviceStatusRunsFixedAdbDevicesCommand() {
        val runner =
            FakeProcessRunner(
                output =
                    """
                    List of devices attached
                    emulator-5554	device
                    """.trimIndent(),
            )
        val registry = deviceDevToolRegistry(temporaryFolder.newFolder("project").toPath(), runner)

        val result = registry.call(DEVICE_STATUS_TOOL, buildJsonObject { })

        assertFalse(result!!.isError)
        assertEquals(listOf("rtk", "adb", "devices"), runner.commands.single())
        val device =
            result.structuredContent!!["devices"]!!
                .jsonArray
                .single()
                .jsonObject
        assertEquals("emulator-5554", device["serial"]!!.jsonPrimitive.content)
    }

    @Test
    fun uiSnapshotRejectsUnsafeSerial() {
        val runner = FakeProcessRunner()
        val registry = deviceDevToolRegistry(temporaryFolder.newFolder("project").toPath(), runner)

        val result =
            registry.call(
                name = UI_SNAPSHOT_TOOL,
                arguments = buildJsonObject { put("deviceSerial", "bad serial;rm") },
            )

        assertTrue(result!!.isError)
        assertEquals(emptyList<List<String>>(), runner.commands)
    }

    @Test
    fun logcatUsesFixedCommandWithLineCap() {
        val runner = FakeProcessRunner(output = "I/com.akhavanskii.aichallenge: ok")
        val registry = deviceDevToolRegistry(temporaryFolder.newFolder("project").toPath(), runner)

        val result =
            registry.call(
                name = LOGCAT_EXCERPT_TOOL,
                arguments =
                    buildJsonObject {
                        put("deviceSerial", "emulator-5554")
                        put("packageName", "com.akhavanskii.aichallenge")
                        put("lines", 50)
                    },
            )

        assertFalse(result!!.isError)
        assertEquals(listOf("rtk", "adb", "-s", "emulator-5554", "logcat", "-d", "-t", "50"), runner.commands.single())
        assertTrue(result.text.contains("com.akhavanskii.aichallenge"))
    }

    private class FakeProcessRunner(
        private val output: String = "",
    ) : DevProcessRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(
            command: List<String>,
            timeout: Duration,
            outputCapBytes: Int,
            artifactName: String,
        ): DevProcessResult {
            commands += command
            return DevProcessResult(command = command, exitCode = 0, timedOut = false, output = output)
        }
    }
}
