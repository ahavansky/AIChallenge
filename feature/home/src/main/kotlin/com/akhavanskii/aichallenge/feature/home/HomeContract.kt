package com.akhavanskii.aichallenge.feature.home

import com.akhavanskii.aichallenge.core.mvvm.UiEvent
import com.akhavanskii.aichallenge.core.mvvm.UiState

data class HomeUiState(
    val prompt: String = "",
    val generationConfig: GeminiGenerationConfigUiState = GeminiGenerationConfigUiState(),
    val comparisonState: HomeComparisonState = HomeComparisonState.idle(),
) : UiState {
    val canSend: Boolean
        get() = prompt.isNotBlank() && !comparisonState.isLoading

    val inputEnabled: Boolean
        get() = !comparisonState.isLoading
}

data class GeminiGenerationConfigUiState(
    val responseMimeType: String = RESPONSE_MIME_TYPE_JSON,
    val responseSchema: String = DEFAULT_RESPONSE_SCHEMA,
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    val stopSequences: String = "",
    val temperature: Double = DEFAULT_TEMPERATURE,
    val topP: Double = DEFAULT_TOP_P,
    val topK: Int = DEFAULT_TOP_K,
    val candidateCount: Int = DEFAULT_CANDIDATE_COUNT,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
) {
    companion object {
        const val RESPONSE_MIME_TYPE_TEXT = "text/plain"
        const val RESPONSE_MIME_TYPE_JSON = "application/json"
        const val RESPONSE_MIME_TYPE_ENUM = "text/x.enum"
        const val DEFAULT_RESPONSE_SCHEMA =
            "{\n" +
                "  \"type\": \"OBJECT\",\n" +
                "  \"properties\": {\n" +
                "    \"answer\": {\n" +
                "      \"type\": \"STRING\"\n" +
                "    },\n" +
                "    \"keyPoints\": {\n" +
                "      \"type\": \"ARRAY\",\n" +
                "      \"items\": {\n" +
                "        \"type\": \"STRING\"\n" +
                "      }\n" +
                "    }\n" +
                "  },\n" +
                "  \"required\": [\"answer\", \"keyPoints\"]\n" +
                "}"
        const val MIN_MAX_OUTPUT_TOKENS = 1
        const val MAX_MAX_OUTPUT_TOKENS = 4096
        const val DEFAULT_MAX_OUTPUT_TOKENS = 512
        const val MIN_TEMPERATURE = 0.0
        const val MAX_TEMPERATURE = 2.0
        const val DEFAULT_TEMPERATURE = 0.7
        const val MIN_TOP_P = 0.0
        const val MAX_TOP_P = 1.0
        const val DEFAULT_TOP_P = 0.95
        const val MIN_TOP_K = 1
        const val MAX_TOP_K = 40
        const val DEFAULT_TOP_K = 40
        const val MIN_CANDIDATE_COUNT = 1
        const val MAX_CANDIDATE_COUNT = 8
        const val DEFAULT_CANDIDATE_COUNT = 1
        const val MIN_PENALTY = -2.0
        const val MAX_PENALTY = 2.0

        val RESPONSE_MIME_TYPES =
            listOf(
                RESPONSE_MIME_TYPE_TEXT,
                RESPONSE_MIME_TYPE_JSON,
                RESPONSE_MIME_TYPE_ENUM,
            )
    }
}

data class HomeComparisonState(
    val configured: ResponsePaneState,
    val baseline: ResponsePaneState,
) {
    val isLoading: Boolean
        get() = configured is ResponsePaneState.Loading || baseline is ResponsePaneState.Loading

    companion object {
        fun idle(): HomeComparisonState =
            HomeComparisonState(
                configured = ResponsePaneState.Empty("The configured Gemini response will appear here."),
                baseline = ResponsePaneState.Empty("The baseline response without parameters will appear here."),
            )

        fun input(): HomeComparisonState =
            HomeComparisonState(
                configured = ResponsePaneState.Empty("Ready to compare with your parameters."),
                baseline = ResponsePaneState.Empty("Ready to compare without extra parameters."),
            )

        fun loading(): HomeComparisonState =
            HomeComparisonState(
                configured = ResponsePaneState.Loading,
                baseline = ResponsePaneState.Loading,
            )
    }
}

sealed interface ResponsePaneState {
    data class Empty(
        val message: String,
    ) : ResponsePaneState

    data object Loading : ResponsePaneState

    data class Success(
        val response: String,
    ) : ResponsePaneState

    data class Error(
        val message: String,
    ) : ResponsePaneState
}

sealed interface HomeAction : UiEvent {
    data class PromptChanged(
        val prompt: String,
    ) : HomeAction

    data class GenerationConfigChanged(
        val generationConfig: GeminiGenerationConfigUiState,
    ) : HomeAction

    data object SubmitPrompt : HomeAction
}
