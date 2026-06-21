package com.akhavanskii.aichallenge.feature.common

enum class GeminiModelOption(
    val modelName: String,
    val title: String,
    val description: String,
) {
    GEMINI_2_5_FLASH(
        modelName = "gemini-2.5-flash",
        title = "Gemini 2.5 Flash",
        description = "Stable free-tier model for balanced quality, speed, and everyday reasoning tasks.",
    ),
    GEMINI_2_5_FLASH_LITE(
        modelName = "gemini-2.5-flash-lite",
        title = "Gemini 2.5 Flash-Lite",
        description = "Stable free-tier model tuned for lower latency and simpler high-volume tasks.",
    ),
    GEMMA_4_31B_IT(
        modelName = "gemma-4-31b-it",
        title = "Gemma 4 31B IT",
        description = "Free Gemma 4 dense model for stronger open-model reasoning.",
    ),
}
