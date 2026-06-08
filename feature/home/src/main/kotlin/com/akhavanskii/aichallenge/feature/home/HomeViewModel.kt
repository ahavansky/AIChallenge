package com.akhavanskii.aichallenge.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akhavanskii.aichallenge.core.network.GeminiGenerationConfig
import com.akhavanskii.aichallenge.core.network.GeminiResult
import com.akhavanskii.aichallenge.core.network.GeminiTextClient
import com.akhavanskii.aichallenge.core.utils.normalizedPromptOrNull
import com.akhavanskii.aichallenge.feature.common.ResponsePaneState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val geminiTextClient: GeminiTextClient,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(HomeUiState())
        val uiState: StateFlow<HomeUiState> = mutableUiState.asStateFlow()

        fun onAction(action: HomeAction) {
            when (action) {
                is HomeAction.PromptChanged -> onPromptChanged(action.prompt)
                is HomeAction.GenerationConfigChanged -> onGenerationConfigChanged(action.generationConfig)
                HomeAction.SubmitPrompt -> submitPrompt()
            }
        }

        private fun onPromptChanged(prompt: String) {
            mutableUiState.update { current ->
                current.copy(
                    prompt = prompt,
                    comparisonState = if (prompt.isBlank()) HomeComparisonState.idle() else HomeComparisonState.input(),
                )
            }
        }

        private fun onGenerationConfigChanged(generationConfig: GeminiGenerationConfigUiState) {
            mutableUiState.update { current ->
                current.copy(
                    generationConfig = generationConfig,
                    comparisonState = if (current.prompt.isBlank()) HomeComparisonState.idle() else HomeComparisonState.input(),
                )
            }
        }

        private fun submitPrompt() {
            val currentState = mutableUiState.value
            val prompt = currentState.prompt.normalizedPromptOrNull()
            if (prompt == null) {
                mutableUiState.update {
                    it.copy(
                        comparisonState =
                            HomeComparisonState(
                                configured = ResponsePaneState.Error("Enter a prompt before sending."),
                                baseline = ResponsePaneState.Error("Enter a prompt before sending."),
                            ),
                    )
                }
                return
            }

            viewModelScope.launch {
                mutableUiState.update { it.copy(prompt = prompt, comparisonState = HomeComparisonState.loading()) }
                coroutineScope {
                    val configuredRequest =
                        async {
                            when (val config = currentState.generationConfig.toNetworkConfig()) {
                                is ConfigBuildResult.Success -> geminiTextClient.generate(prompt, config.value).toPaneState()
                                is ConfigBuildResult.Error -> ResponsePaneState.Error(config.message)
                            }
                        }
                    val baselineRequest = async { geminiTextClient.generate(prompt, generationConfig = null).toPaneState() }

                    launch {
                        updateConfiguredPane(configuredRequest.await())
                    }
                    launch {
                        updateBaselinePane(baselineRequest.await())
                    }
                }
            }
        }

        private fun updateConfiguredPane(state: ResponsePaneState) {
            mutableUiState.update { current ->
                current.copy(comparisonState = current.comparisonState.copy(configured = state))
            }
        }

        private fun updateBaselinePane(state: ResponsePaneState) {
            mutableUiState.update { current ->
                current.copy(comparisonState = current.comparisonState.copy(baseline = state))
            }
        }

        private fun GeminiResult<String>.toPaneState(): ResponsePaneState =
            when (this) {
                is GeminiResult.Success -> ResponsePaneState.Success(value)
                is GeminiResult.Failure -> ResponsePaneState.Error(error.userMessage)
            }

        private fun GeminiGenerationConfigUiState.toNetworkConfig(): ConfigBuildResult {
            val errors = mutableListOf<String>()
            val mimeType =
                responseMimeType
                    .trim()
                    .takeIf { it in GeminiGenerationConfigUiState.RESPONSE_MIME_TYPES }
                    ?: GeminiGenerationConfigUiState.RESPONSE_MIME_TYPE_TEXT.also {
                        errors += "responseMimeType must be text/plain, application/json, or text/x.enum."
                    }
            val schema = responseSchema.trim().takeIf { it.isNotEmpty() }
            if (schema != null && mimeType == GeminiGenerationConfigUiState.RESPONSE_MIME_TYPE_TEXT) {
                errors += "responseSchema requires application/json or text/x.enum responseMimeType."
            }

            val stopSequences =
                stopSequences
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
            if (stopSequences.size > MAX_STOP_SEQUENCES) {
                errors += "stopSequences supports up to $MAX_STOP_SEQUENCES values."
            }

            val config =
                GeminiGenerationConfig(
                    responseMimeType = mimeType,
                    responseSchemaJson = schema,
                    maxOutputTokens =
                        maxOutputTokens.coerceIn(
                            GeminiGenerationConfigUiState.MIN_MAX_OUTPUT_TOKENS,
                            GeminiGenerationConfigUiState.MAX_MAX_OUTPUT_TOKENS,
                        ),
                    stopSequences = stopSequences.take(MAX_STOP_SEQUENCES),
                    temperature =
                        temperature.coerceIn(
                            GeminiGenerationConfigUiState.MIN_TEMPERATURE,
                            GeminiGenerationConfigUiState.MAX_TEMPERATURE,
                        ),
                    topP = topP.coerceIn(GeminiGenerationConfigUiState.MIN_TOP_P, GeminiGenerationConfigUiState.MAX_TOP_P),
                    topK = topK.coerceIn(GeminiGenerationConfigUiState.MIN_TOP_K, GeminiGenerationConfigUiState.MAX_TOP_K),
                    candidateCount =
                        candidateCount.coerceIn(
                            GeminiGenerationConfigUiState.MIN_CANDIDATE_COUNT,
                            GeminiGenerationConfigUiState.MAX_CANDIDATE_COUNT,
                        ),
                    presencePenalty = presencePenalty.takeIfValidPenalty("presencePenalty", errors),
                    frequencyPenalty = frequencyPenalty.takeIfValidPenalty("frequencyPenalty", errors),
                )

            return if (errors.isEmpty()) {
                ConfigBuildResult.Success(config)
            } else {
                ConfigBuildResult.Error(errors.joinToString(separator = "\n"))
            }
        }

        private fun Double?.takeIfValidPenalty(
            name: String,
            errors: MutableList<String>,
        ): Double? {
            if (this == null) return null
            if (this < GeminiGenerationConfigUiState.MIN_PENALTY || this >= GeminiGenerationConfigUiState.MAX_PENALTY) {
                errors +=
                    "$name must be greater than or equal to ${GeminiGenerationConfigUiState.MIN_PENALTY} " +
                    "and less than ${GeminiGenerationConfigUiState.MAX_PENALTY}."
            }
            return this
        }

        private sealed interface ConfigBuildResult {
            data class Success(
                val value: GeminiGenerationConfig,
            ) : ConfigBuildResult

            data class Error(
                val message: String,
            ) : ConfigBuildResult
        }

        private companion object {
            const val MAX_STOP_SEQUENCES = 5
        }
    }
