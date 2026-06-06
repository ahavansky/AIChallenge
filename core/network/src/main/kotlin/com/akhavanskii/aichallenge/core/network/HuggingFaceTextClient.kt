package com.akhavanskii.aichallenge.core.network

interface HuggingFaceTextClient {
    suspend fun generate(
        prompt: String,
        modelName: String,
    ): HuggingFaceResult<HuggingFaceTextResponse>
}
