package com.akhavanskii.aichallenge.mcp.dev

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface DevProcessRunner {
    fun run(
        command: List<String>,
        timeout: Duration = DEFAULT_PROCESS_TIMEOUT,
        outputCapBytes: Int = DEFAULT_OUTPUT_CAP_BYTES,
        artifactName: String = "process-output.txt",
    ): DevProcessResult
}

data class DevProcessResult(
    val command: List<String>,
    val exitCode: Int?,
    val timedOut: Boolean,
    val output: String,
    val artifactPath: String? = null,
) {
    val isSuccess: Boolean
        get() = !timedOut && exitCode == 0
}

class RealDevProcessRunner(
    private val workingDir: Path,
    artifactRoot: Path = workingDir.resolve("build/mcp-dev-qa"),
) : DevProcessRunner {
    private val root = workingDir.toAbsolutePath().normalize()
    private val artifacts = artifactRoot.toAbsolutePath().normalize()

    override fun run(
        command: List<String>,
        timeout: Duration,
        outputCapBytes: Int,
        artifactName: String,
    ): DevProcessResult {
        require(command.isNotEmpty()) { "Command must not be empty." }
        val executor = Executors.newSingleThreadExecutor()
        var process: Process? = null
        return try {
            process =
                ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start()
            val outputFuture =
                executor.submit<String> {
                    process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                }
            val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            val rawOutput =
                runCatching { outputFuture.get(2, TimeUnit.SECONDS) }
                    .getOrElse { "Process output unavailable: ${it.message.orEmpty()}" }
            val capped = rawOutput.redactSecrets().capAndPersist(outputCapBytes, artifacts, artifactName)
            DevProcessResult(
                command = command,
                exitCode = if (finished) process.exitValue() else null,
                timedOut = !finished,
                output = capped.text,
                artifactPath = capped.artifactPath,
            )
        } catch (throwable: Throwable) {
            DevProcessResult(
                command = command,
                exitCode = null,
                timedOut = false,
                output = "Process failed: ${throwable.message.orEmpty()}".redactSecrets(),
            )
        } finally {
            process?.inputStream?.close()
            executor.shutdownNow()
        }
    }
}

class SafeProjectPaths(
    projectRoot: Path,
    artifactRoot: Path = projectRoot.resolve("build/mcp-dev-qa"),
) {
    val root: Path = projectRoot.toAbsolutePath().normalize()
    val artifacts: Path = artifactRoot.toAbsolutePath().normalize()

    fun resolveProjectRelativePath(value: String): PathValidationResult {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return PathValidationResult.Failure("Path must not be blank.")
        if (trimmed.startsWith("/") || trimmed.startsWith("\\")) {
            return PathValidationResult.Failure("Absolute paths are not allowed.")
        }
        if (trimmed.contains('\u0000')) return PathValidationResult.Failure("Path must not contain NUL.")
        val path = root.resolve(trimmed).normalize()
        if (!path.startsWith(root)) return PathValidationResult.Failure("Path traversal is blocked.")
        return PathValidationResult.Success(path)
    }

    fun validateGlob(glob: String?): String? {
        val trimmed = glob?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            trimmed.startsWith("/") || trimmed.startsWith("\\") -> null
            trimmed.contains("..") -> null
            trimmed.contains('\u0000') -> null
            trimmed.length > MAX_GLOB_LENGTH -> null
            else -> trimmed
        }
    }
}

sealed interface PathValidationResult {
    data class Success(
        val path: Path,
    ) : PathValidationResult

    data class Failure(
        val message: String,
    ) : PathValidationResult
}

data class CappedText(
    val text: String,
    val artifactPath: String?,
)

fun String.redactSecrets(): String =
    lineSequence()
        .joinToString(separator = "\n") { line ->
            line
                .replace(BEARER_TOKEN_PATTERN, "Bearer [REDACTED]")
                .replace(API_KEY_ASSIGNMENT_PATTERN, "$1$2[REDACTED]")
                .replace(SENSITIVE_FILE_PATTERN, "[REDACTED_FILE]")
        }

fun String.capAndPersist(
    outputCapBytes: Int = DEFAULT_OUTPUT_CAP_BYTES,
    artifactRoot: Path,
    artifactName: String,
): CappedText {
    val bytes = toByteArray(StandardCharsets.UTF_8)
    if (bytes.size <= outputCapBytes) return CappedText(text = this, artifactPath = null)

    Files.createDirectories(artifactRoot)
    val artifactPath = artifactRoot.resolve(artifactName.safeArtifactName()).normalize()
    Files.writeString(
        artifactPath,
        this,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
    )
    val head = take(outputCapBytes / 2)
    val tail = takeLast(outputCapBytes / 2)
    return CappedText(
        text =
            buildString {
                append(head)
                appendLine()
                appendLine("...[truncated; full redacted output saved to $artifactPath]...")
                append(tail)
            },
        artifactPath = artifactPath.toString(),
    )
}

private fun String.safeArtifactName(): String {
    val base =
        lowercase(Locale.US)
            .replace(UNSAFE_ARTIFACT_CHARS, "-")
            .trim('-')
            .take(96)
            .ifBlank { "process-output" }
    return if (base.endsWith(".txt")) base else "$base.txt"
}

val DEFAULT_PROCESS_TIMEOUT: Duration = Duration.ofSeconds(120)
val QUALITY_GATE_PROCESS_TIMEOUT: Duration = Duration.ofSeconds(240)
const val DEFAULT_OUTPUT_CAP_BYTES = 32 * 1024

private const val MAX_GLOB_LENGTH = 120
private val BEARER_TOKEN_PATTERN = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+")
private val API_KEY_ASSIGNMENT_PATTERN =
    Regex("(?i)([A-Z0-9_]*(?:API[_-]?KEY|TOKEN|SECRET)[A-Z0-9_]*)(\\s*[:=]\\s*)([^\\s\"']+)")
private val SENSITIVE_FILE_PATTERN = Regex("(?i)(local\\.properties|\\.env[A-Za-z0-9_.-]*|[A-Za-z0-9_.-]+\\.(?:jks|keystore|p12))")
private val UNSAFE_ARTIFACT_CHARS = Regex("[^a-z0-9._-]+")
