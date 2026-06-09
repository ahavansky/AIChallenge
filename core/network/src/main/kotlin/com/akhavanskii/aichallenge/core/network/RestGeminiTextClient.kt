package com.akhavanskii.aichallenge.core.network

import javax.inject.Inject

class RestGeminiTextClient
    @Inject
    constructor(
        private val agent: LlmAgent,
    ) : GeminiTextClient {
        override suspend fun generate(
            prompt: String,
            generationConfig: GeminiGenerationConfig?,
            modelName: String?,
        ): GeminiResult<String> = agent.sendMessage(prompt, generationConfig, modelName)
    }
