package com.akhavanskii.aichallenge.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GenerateContentRequest(
    val contents: List<GeminiContentDto>,
) {
    companion object {
        fun fromPrompt(prompt: String): GenerateContentRequest =
            GenerateContentRequest(
                contents =
                    listOf(
                        GeminiContentDto(
                            role = "user",
                            parts = listOf(GeminiPartDto(text = prompt)),
                        ),
                    ),
            )
    }
}

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

fun GenerateContentResponse.firstTextOrNull(): String? =
    candidates
        .asSequence()
        .mapNotNull { it.content }
        .flatMap { it.parts.asSequence() }
        .mapNotNull { it.text?.trim() }
        .firstOrNull { it.isNotEmpty() }
