package com.akhavanskii.aichallenge.mcp.dev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DevProcessTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun redactsSecretsAndSensitiveFileNames() {
        val redacted =
            """
            GEMINI_API_KEY=real-key
            Authorization: Bearer abc.def.ghi
            local.properties
            release.keystore
            .env.local
            """.trimIndent().redactSecrets()

        assertTrue(redacted.contains("GEMINI_API_KEY=[REDACTED]"))
        assertTrue(redacted.contains("Bearer [REDACTED]"))
        assertTrue(redacted.contains("[REDACTED_FILE]"))
        assertTrue(!redacted.contains("real-key"))
        assertTrue(!redacted.contains("abc.def.ghi"))
    }

    @Test
    fun capWritesFullRedactedOutputArtifact() {
        val root = temporaryFolder.newFolder("artifacts").toPath()
        val capped =
            "x".repeat(128).capAndPersist(
                outputCapBytes = 32,
                artifactRoot = root,
                artifactName = "Big Output.txt",
            )

        assertTrue(capped.text.contains("truncated"))
        assertNotNull(capped.artifactPath)
        assertEquals(
            128,
            root
                .resolve("big-output.txt")
                .toFile()
                .readText()
                .length,
        )
    }

    @Test
    fun projectPathTraversalBlocked() {
        val root = temporaryFolder.newFolder("project").toPath()
        val paths = SafeProjectPaths(root)

        val result = paths.resolveProjectRelativePath("../outside")

        assertTrue(result is PathValidationResult.Failure)
    }
}
