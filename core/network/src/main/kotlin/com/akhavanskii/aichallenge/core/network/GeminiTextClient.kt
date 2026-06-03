package com.akhavanskii.aichallenge.core.network

interface GeminiTextClient {
    suspend fun generate(
        prompt: String,
        generationConfig: GeminiGenerationConfig? = null,
    ): GeminiResult<String>
}

data class GeminiGenerationConfig(
    val responseMimeType: String? = null,
    val responseSchemaJson: String? = null,
    val maxOutputTokens: Int? = null,
    val stopSequences: List<String> = emptyList(),
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val candidateCount: Int? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
)
