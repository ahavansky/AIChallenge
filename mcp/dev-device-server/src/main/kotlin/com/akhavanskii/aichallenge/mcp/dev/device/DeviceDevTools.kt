package com.akhavanskii.aichallenge.mcp.dev.device

import com.akhavanskii.aichallenge.mcp.dev.DEFAULT_OUTPUT_CAP_BYTES
import com.akhavanskii.aichallenge.mcp.dev.DEFAULT_PROCESS_TIMEOUT
import com.akhavanskii.aichallenge.mcp.dev.DevMcpToolCallResult
import com.akhavanskii.aichallenge.mcp.dev.DevMcpToolDefinition
import com.akhavanskii.aichallenge.mcp.dev.DevMcpToolRegistry
import com.akhavanskii.aichallenge.mcp.dev.DevProcessRunner
import com.akhavanskii.aichallenge.mcp.dev.RealDevProcessRunner
import com.akhavanskii.aichallenge.mcp.dev.intOrNull
import com.akhavanskii.aichallenge.mcp.dev.integerSchema
import com.akhavanskii.aichallenge.mcp.dev.objectSchema
import com.akhavanskii.aichallenge.mcp.dev.stringOrNull
import com.akhavanskii.aichallenge.mcp.dev.stringSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Path

fun deviceDevToolRegistry(
    projectRoot: Path,
    processRunner: DevProcessRunner = RealDevProcessRunner(projectRoot),
): DevMcpToolRegistry =
    DevMcpToolRegistry(
        tools =
            listOf(
                DevMcpToolDefinition(
                    name = DEVICE_STATUS_TOOL,
                    description = "Run allowlisted adb device discovery and report connected device status.",
                    inputSchema = objectSchema(properties = emptyMap()),
                    handler = { deviceStatus(processRunner) },
                ),
                DevMcpToolDefinition(
                    name = UI_SNAPSHOT_TOOL,
                    description = "Run allowlisted Android layout dump and return current UI tree summary.",
                    inputSchema = uiSnapshotSchema(),
                    handler = { arguments -> uiSnapshot(arguments, processRunner) },
                ),
                DevMcpToolDefinition(
                    name = LOGCAT_EXCERPT_TOOL,
                    description = "Run allowlisted logcat excerpt command with line cap and secret redaction.",
                    inputSchema = logcatExcerptSchema(),
                    handler = { arguments -> logcatExcerpt(arguments, processRunner) },
                ),
            ),
    )

private fun deviceStatus(processRunner: DevProcessRunner): DevMcpToolCallResult {
    val result =
        processRunner.run(
            command = listOf("rtk", "adb", "devices"),
            timeout = DEFAULT_PROCESS_TIMEOUT,
            outputCapBytes = DEFAULT_OUTPUT_CAP_BYTES,
            artifactName = "device-status.txt",
        )
    val devices = parseAdbDevices(result.output)
    val status =
        when {
            !result.isSuccess -> "error"
            devices.isEmpty() -> "none"
            devices.size == 1 -> "connected"
            else -> "multiple"
        }
    return DevMcpToolCallResult(
        text =
            buildString {
                appendLine("Device status: $status")
                devices.forEach { appendLine("- ${it.serial}: ${it.state}") }
                if (devices.isEmpty() && result.output.isNotBlank()) appendLine(result.output)
            }.trimEnd(),
        isError = !result.isSuccess,
        structuredContent =
            buildJsonObject {
                put("status", status)
                putJsonArray("devices") {
                    devices.forEach { device ->
                        add(
                            buildJsonObject {
                                put("serial", device.serial)
                                put("state", device.state)
                            },
                        )
                    }
                }
            },
    )
}

private fun uiSnapshot(
    arguments: JsonObject,
    processRunner: DevProcessRunner,
): DevMcpToolCallResult {
    arguments.rejectUnknownArgs(setOf("deviceSerial"))?.let { return DevMcpToolCallResult.error(it) }
    val serial = arguments.stringOrNull("deviceSerial")?.trim()?.takeIf { it.isNotEmpty() }
    if (serial != null && !DEVICE_SERIAL_PATTERN.matches(serial)) {
        return DevMcpToolCallResult.error("`deviceSerial` contains unsupported characters.")
    }
    val command =
        buildList {
            addAll(listOf("rtk", "android", "layout", "--pretty"))
            serial?.let { add("--device=$it") }
        }
    val result =
        processRunner.run(
            command = command,
            timeout = DEFAULT_PROCESS_TIMEOUT,
            outputCapBytes = DEFAULT_OUTPUT_CAP_BYTES,
            artifactName = "ui-snapshot.txt",
        )
    return DevMcpToolCallResult(
        text =
            buildString {
                appendLine("UI snapshot ${if (result.isSuccess) "captured" else "failed"}.")
                result.artifactPath?.let { appendLine("Artifact: $it") }
                if (result.output.isNotBlank()) {
                    appendLine()
                    append(result.output)
                }
            }.trimEnd(),
        isError = !result.isSuccess,
        structuredContent =
            buildJsonObject {
                put("success", result.isSuccess)
                serial?.let { put("deviceSerial", it) }
                result.artifactPath?.let { put("artifact", it) }
            },
    )
}

private fun logcatExcerpt(
    arguments: JsonObject,
    processRunner: DevProcessRunner,
): DevMcpToolCallResult {
    arguments.rejectUnknownArgs(setOf("deviceSerial", "packageName", "lines"))?.let { return DevMcpToolCallResult.error(it) }
    val serial = arguments.stringOrNull("deviceSerial")?.trim()?.takeIf { it.isNotEmpty() }
    if (serial != null && !DEVICE_SERIAL_PATTERN.matches(serial)) {
        return DevMcpToolCallResult.error("`deviceSerial` contains unsupported characters.")
    }
    val packageName = arguments.stringOrNull("packageName")?.trim()?.takeIf { it.isNotEmpty() }
    if (packageName != null && !PACKAGE_NAME_PATTERN.matches(packageName)) {
        return DevMcpToolCallResult.error("`packageName` is invalid.")
    }
    val lines = arguments.intOrNull("lines") ?: DEFAULT_LOGCAT_LINES
    if (lines !in LOGCAT_LINE_RANGE) {
        return DevMcpToolCallResult.error("`lines` must be between ${LOGCAT_LINE_RANGE.first} and ${LOGCAT_LINE_RANGE.last}.")
    }

    val command =
        buildList {
            addAll(listOf("rtk", "adb"))
            serial?.let {
                add("-s")
                add(it)
            }
            addAll(listOf("logcat", "-d", "-t", lines.toString()))
        }
    val result =
        processRunner.run(
            command = command,
            timeout = DEFAULT_PROCESS_TIMEOUT,
            outputCapBytes = DEFAULT_OUTPUT_CAP_BYTES,
            artifactName = "logcat-excerpt.txt",
        )
    val output =
        if (packageName == null) {
            result.output
        } else {
            result.output
                .lineSequence()
                .filter { it.contains(packageName) }
                .joinToString(separator = "\n")
                .ifBlank { "No logcat lines matched package `$packageName`." }
        }

    return DevMcpToolCallResult(
        text =
            buildString {
                appendLine("Logcat excerpt ${if (result.isSuccess) "captured" else "failed"}.")
                result.artifactPath?.let { appendLine("Artifact: $it") }
                appendLine()
                append(output)
            }.trimEnd(),
        isError = !result.isSuccess,
        structuredContent =
            buildJsonObject {
                put("success", result.isSuccess)
                put("lines", lines)
                serial?.let { put("deviceSerial", it) }
                packageName?.let { put("packageName", it) }
                result.artifactPath?.let { put("artifact", it) }
            },
    )
}

private data class AdbDevice(
    val serial: String,
    val state: String,
)

private fun parseAdbDevices(output: String): List<AdbDevice> =
    output
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("List of devices attached") }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2) null else AdbDevice(serial = parts[0], state = parts[1])
        }.filter { it.state == "device" }
        .toList()

private fun JsonObject.rejectUnknownArgs(allowed: Set<String>): String? {
    val unknown = keys.filterNot { it in allowed }
    return unknown.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Unknown argument(s): ")
}

private fun uiSnapshotSchema(): JsonObject =
    objectSchema(
        properties =
            mapOf(
                "deviceSerial" to stringSchema("Optional adb device serial.", maxLength = 80),
            ),
    )

private fun logcatExcerptSchema(): JsonObject =
    objectSchema(
        properties =
            mapOf(
                "deviceSerial" to stringSchema("Optional adb device serial.", maxLength = 80),
                "packageName" to stringSchema("Optional Android package name filter.", maxLength = 160),
                "lines" to integerSchema("Maximum logcat lines.", LOGCAT_LINE_RANGE.first, LOGCAT_LINE_RANGE.last, DEFAULT_LOGCAT_LINES),
            ),
    )

const val DEVICE_STATUS_TOOL = "device_status"
const val UI_SNAPSHOT_TOOL = "ui_snapshot"
const val LOGCAT_EXCERPT_TOOL = "logcat_excerpt"
const val DEFAULT_DEVICE_SERVER_PORT = 8773

private const val DEFAULT_LOGCAT_LINES = 120
private val LOGCAT_LINE_RANGE = 1..500
private val DEVICE_SERIAL_PATTERN = Regex("[A-Za-z0-9._:-]{1,80}")
private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
