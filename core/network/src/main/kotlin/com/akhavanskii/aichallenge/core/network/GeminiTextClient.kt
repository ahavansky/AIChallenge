package com.akhavanskii.aichallenge.core.network

interface GeminiTextClient {
    suspend fun generate(prompt: String): GeminiResult<String>
}
