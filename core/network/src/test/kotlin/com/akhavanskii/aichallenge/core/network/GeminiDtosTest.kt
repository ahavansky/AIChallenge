package com.akhavanskii.aichallenge.core.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalSerializationApi::class)
class GeminiDtosTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun requestFromPromptSerializesUserPrompt() {
        val encoded = json.encodeToString(GenerateContentRequest.fromPrompt("Hello Gemini"))

        assertTrue(encoded.contains("\"role\":\"user\""))
        assertTrue(encoded.contains("\"text\":\"Hello Gemini\""))
        assertFalse(encoded.contains("generationConfig"))
    }

    @Test
    fun requestFromPromptSerializesGenerationConfig() {
        val encoded =
            json.encodeToString(
                GenerateContentRequest.fromPrompt(
                    prompt = "Hello Gemini",
                    generationConfig =
                        GeminiGenerationConfig(
                            responseMimeType = "application/json",
                            responseSchemaJson = """{"type":"object","properties":{"answer":{"type":"string"}}}""",
                            maxOutputTokens = 128,
                            stopSequences = listOf("END"),
                            temperature = 0.4,
                            topP = 0.8,
                            topK = 32,
                            candidateCount = 2,
                            presencePenalty = 0.1,
                            frequencyPenalty = 0.2,
                        ).toDto(json),
                ),
            )

        assertTrue(encoded.contains("\"generationConfig\""))
        assertTrue(encoded.contains("\"responseMimeType\":\"application/json\""))
        assertTrue(encoded.contains("\"responseSchema\":{\"type\":\"object\""))
        assertTrue(encoded.contains("\"maxOutputTokens\":128"))
        assertTrue(encoded.contains("\"stopSequences\":[\"END\"]"))
        assertTrue(encoded.contains("\"temperature\":0.4"))
        assertTrue(encoded.contains("\"topP\":0.8"))
        assertTrue(encoded.contains("\"topK\":32"))
        assertTrue(encoded.contains("\"candidateCount\":2"))
        assertTrue(encoded.contains("\"presencePenalty\":0.1"))
        assertTrue(encoded.contains("\"frequencyPenalty\":0.2"))
    }

    @Test
    fun requestFromMessagesSerializesChatHistory() {
        val encoded =
            json.encodeToString(
                GenerateContentRequest.fromMessages(
                    listOf(
                        AgentMessage.User("First question"),
                        AgentMessage.Model("First answer"),
                        AgentMessage.User("Follow-up"),
                    ),
                ),
            )

        assertTrue(encoded.contains("\"role\":\"user\""))
        assertTrue(encoded.contains("\"role\":\"model\""))
        assertTrue(encoded.contains("\"text\":\"First question\""))
        assertTrue(encoded.contains("\"text\":\"First answer\""))
        assertTrue(encoded.contains("\"text\":\"Follow-up\""))
        assertFalse(encoded.contains("generationConfig"))
    }

    @Test
    fun countTokensRequestSerializesMessagesWithoutGenerationConfig() {
        val encoded =
            json.encodeToString(
                CountTokensRequest.fromMessages(
                    listOf(
                        AgentMessage.User("Current request"),
                    ),
                ),
            )

        assertTrue(encoded.contains("\"contents\""))
        assertTrue(encoded.contains("\"role\":\"user\""))
        assertTrue(encoded.contains("\"text\":\"Current request\""))
        assertFalse(encoded.contains("generationConfig"))
    }

    @Test
    fun textOrNullReturnsFirstNonBlankPart() {
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

        assertEquals("Useful answer", decoded.textOrNull())
    }

    @Test
    fun usageMetadataMapsToTokenUsage() {
        val decoded =
            json.decodeFromString<GenerateContentResponse>(
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {"text": "Useful answer"}
                        ]
                      }
                    }
                  ],
                  "usageMetadata": {
                    "promptTokenCount": 11,
                    "candidatesTokenCount": 7,
                    "totalTokenCount": 18
                  }
                }
                """.trimIndent(),
            )

        assertEquals(
            GeminiTokenUsage(
                currentRequestTokens = 3,
                conversationHistoryTokens = 11,
                modelResponseTokens = 7,
                totalTokens = 18,
            ),
            decoded.usageMetadata?.toTokenUsage(currentRequestTokens = 3),
        )
    }

    @Test
    fun textOrNullLabelsMultipleCandidates() {
        val decoded =
            json.decodeFromString<GenerateContentResponse>(
                """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {"text": "First answer"}
                        ]
                      }
                    },
                    {
                      "content": {
                        "parts": [
                          {"text": "Second answer"}
                        ]
                      }
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertEquals(
            """
            Candidate 1
            First answer

            Candidate 2
            Second answer
            """.trimIndent(),
            decoded.textOrNull(),
        )
    }
}
