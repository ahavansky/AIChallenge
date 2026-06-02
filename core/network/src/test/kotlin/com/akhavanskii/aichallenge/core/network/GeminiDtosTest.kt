package com.akhavanskii.aichallenge.core.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiDtosTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun requestFromPromptSerializesUserPrompt() {
        val encoded = json.encodeToString(GenerateContentRequest.fromPrompt("Hello Gemini"))

        assertTrue(encoded.contains("\"role\":\"user\""))
        assertTrue(encoded.contains("\"text\":\"Hello Gemini\""))
    }

    @Test
    fun firstTextOrNullReturnsFirstNonBlankPart() {
        val decoded =
            json.decodeFromString<GenerateContentResponse>(
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {"text": "   "},
                          {"text": " Useful answer "}
                        ]
                      }
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertEquals("Useful answer", decoded.firstTextOrNull())
    }
}
