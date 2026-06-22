package com.akhavanskii.aichallenge.core.network

import javax.inject.Inject

class RoutingLlmAgent
    @Inject
    constructor(
        private val geminiAgent: GeminiAgent,
        private val deepSeekAgent: DeepSeekAgent,
    ) : LlmAgent {
        override suspend fun countTokens(
            messages: List<AgentMessage>,
            systemInstruction: String?,
            modelName: String?,
        ): AgentResult<Int> =
            agentFor(modelName).countTokens(
                messages = messages,
                systemInstruction = systemInstruction,
                modelName = modelName,
            )

        override suspend fun sendMessage(
            messages: List<AgentMessage>,
            systemInstruction: String?,
            generationConfig: GeminiGenerationConfig?,
            modelName: String?,
            totalTokenLimit: Int?,
        ): AgentResult<String> =
            agentFor(modelName).sendMessage(
                messages = messages,
                systemInstruction = systemInstruction,
                generationConfig = generationConfig,
                modelName = modelName,
                totalTokenLimit = totalTokenLimit,
            )

        private fun agentFor(modelName: String?): LlmAgent =
            if (modelName?.trim()?.startsWith(DEEPSEEK_MODEL_PREFIX, ignoreCase = true) == true) {
                deepSeekAgent
            } else {
                geminiAgent
            }

        private companion object {
            const val DEEPSEEK_MODEL_PREFIX = "deepseek-"
        }
    }
