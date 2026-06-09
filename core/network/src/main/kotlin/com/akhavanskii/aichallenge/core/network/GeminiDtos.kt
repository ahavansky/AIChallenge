package com.akhavanskii.aichallenge.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
data class GenerateContentRequest(
    val contents: List<GeminiContentDto>,
    @SerialName("generationConfig")
    val generationConfig: GeminiGenerationConfigDto? = null,
) {
    companion object {
        fun fromPrompt(
            prompt: String,
            generationConfig: GeminiGenerationConfigDto? = null,
        ): GenerateContentRequest =
            fromMessages(
                messages = listOf(AgentMessage.User(prompt)),
                generationConfig = generationConfig,
            )

        fun fromMessages(
            messages: List<AgentMessage>,
            generationConfig: GeminiGenerationConfigDto? = null,
        ): GenerateContentRequest =
            GenerateContentRequest(
                contents =
                    messages.map { message ->
                        GeminiContentDto(
                            role =
                                when (message) {
                                    is AgentMessage.User -> "user"
                                    is AgentMessage.Model -> "model"
                                },
                            parts = listOf(GeminiPartDto(text = message.text)),
                        )
                    },
                generationConfig = generationConfig,
            )
    }
}

@Serializable
data class GeminiGenerationConfigDto(
    @SerialName("responseMimeType")
    val responseMimeType: String? = null,
    @SerialName("responseSchema")
    val responseSchema: JsonElement? = null,
    @SerialName("maxOutputTokens")
    val maxOutputTokens: Int? = null,
    @SerialName("stopSequences")
    val stopSequences: List<String>? = null,
    val temperature: Double? = null,
    @SerialName("topP")
    val topP: Double? = null,
    @SerialName("topK")
    val topK: Int? = null,
    @SerialName("candidateCount")
    val candidateCount: Int? = null,
    @SerialName("presencePenalty")
    val presencePenalty: Double? = null,
    @SerialName("frequencyPenalty")
    val frequencyPenalty: Double? = null,
)

fun GeminiGenerationConfig.toDto(json: Json): GeminiGenerationConfigDto =
    GeminiGenerationConfigDto(
        responseMimeType = responseMimeType?.trim()?.takeIf { it.isNotEmpty() },
        responseSchema = responseSchemaJson?.trim()?.takeIf { it.isNotEmpty() }?.let(json::parseToJsonElement),
        maxOutputTokens = maxOutputTokens,
        stopSequences = stopSequences.map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() },
        temperature = temperature,
        topP = topP,
        topK = topK,
        candidateCount = candidateCount,
        presencePenalty = presencePenalty,
        frequencyPenalty = frequencyPenalty,
    )

@Serializable
data class GeminiContentDto(
    val parts: List<GeminiPartDto>,
    val role: String? = null,
)

@Serializable
data class GeminiPartDto(
    val text: String? = null,
)

@Serializable
data class GenerateContentResponse(
    val candidates: List<GeminiCandidateDto> = emptyList(),
)

@Serializable
data class GeminiCandidateDto(
    val content: GeminiContentDto? = null,
    @SerialName("finishReason")
    val finishReason: String? = null,
)

fun GenerateContentResponse.textOrNull(): String? {
    val texts =
        candidates.mapIndexedNotNull { index, candidate ->
            candidate.content
                ?.parts
                ?.asSequence()
                ?.mapNotNull { it.text?.trim() }
                ?.filter { it.isNotEmpty() }
                ?.joinToString(separator = "\n")
                ?.takeIf { it.isNotEmpty() }
                ?.let { text ->
                    if (candidates.size == 1) {
                        text
                    } else {
                        "Candidate ${index + 1}\n$text"
                    }
                }
        }

    return texts.takeIf { it.isNotEmpty() }?.joinToString(separator = "\n\n")
}
