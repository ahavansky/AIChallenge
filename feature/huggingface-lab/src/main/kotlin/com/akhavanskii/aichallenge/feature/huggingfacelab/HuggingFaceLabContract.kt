package com.akhavanskii.aichallenge.feature.huggingfacelab

import com.akhavanskii.aichallenge.core.mvvm.UiEvent
import com.akhavanskii.aichallenge.core.mvvm.UiState
import com.akhavanskii.aichallenge.core.network.HuggingFaceResponseMetadata
import com.akhavanskii.aichallenge.core.network.HuggingFaceTokenUsage
import com.akhavanskii.aichallenge.feature.common.GeminiModelOption
import com.akhavanskii.aichallenge.feature.common.ResponsePaneState

data class HuggingFaceLabUiState(
    val task: String = "",
    val selectedGeminiModel: GeminiModelOption = GeminiModelOption.GEMINI_2_5_FLASH,
    val outputs: List<HuggingFaceLabOutput> = initialOutputs(),
    val evaluationState: ResponsePaneState =
        ResponsePaneState.Empty(
            "Gemini evaluation will appear after the three HuggingFace outputs finish.",
        ),
) : UiState {
    val isLoading: Boolean
        get() = outputs.any { it.state is ResponsePaneState.Loading } || evaluationState is ResponsePaneState.Loading

    val inputEnabled: Boolean
        get() = !isLoading

    val canRun: Boolean
        get() = task.isNotBlank() && !isLoading

    companion object {
        fun initialOutputs(): List<HuggingFaceLabOutput> =
            HuggingFaceModelPreset.entries.map { preset ->
                HuggingFaceLabOutput(
                    preset = preset,
                    state = ResponsePaneState.Empty("Run a task to benchmark this model."),
                )
            }

        fun loadingOutputs(): List<HuggingFaceLabOutput> =
            HuggingFaceModelPreset.entries.map { preset ->
                HuggingFaceLabOutput(
                    preset = preset,
                    state = ResponsePaneState.Loading,
                )
            }
    }
}

data class HuggingFaceLabOutput(
    val preset: HuggingFaceModelPreset,
    val state: ResponsePaneState,
    val responseTimeMillis: Long? = null,
    val tokenUsage: HuggingFaceTokenUsage? = null,
    val metadata: HuggingFaceResponseMetadata? = null,
)

enum class HuggingFaceModelPreset(
    val modelName: String,
    val title: String,
    val strengthLabel: String,
    val provider: String,
    val sizeLabel: String,
    val capabilitySummary: String,
    val description: String,
) {
    WEAK(
        modelName = "openai/gpt-oss-20b:groq",
        title = "GPT-OSS 20B",
        strengthLabel = "Weak",
        provider = "Groq",
        sizeLabel = "20B",
        capabilitySummary = "Text chat, reasoning capable, request max output 1024 tokens.",
        description = "Smaller provider-qualified chat model through Groq; useful as a lower-cost baseline.",
    ),
    MEDIUM(
        modelName = "openai/gpt-oss-120b:groq",
        title = "GPT-OSS 120B Groq",
        strengthLabel = "Medium",
        provider = "Groq",
        sizeLabel = "120B",
        capabilitySummary = "Text chat, reasoning capable, request max output 1024 tokens.",
        description = "Larger provider-qualified chat model through Groq; useful for quality versus speed comparison.",
    ),
    STRONG(
        modelName = "openai/gpt-oss-120b:cerebras",
        title = "GPT-OSS 120B Cerebras",
        strengthLabel = "Strong",
        provider = "Cerebras",
        sizeLabel = "120B",
        capabilitySummary = "Text chat, reasoning capable, request max output 1024 tokens.",
        description = "Same large open model through Cerebras; useful for comparing provider speed and resource metrics.",
    ),
}

sealed interface HuggingFaceLabAction : UiEvent {
    data class TaskChanged(
        val task: String,
    ) : HuggingFaceLabAction

    data class GeminiModelChanged(
        val model: GeminiModelOption,
    ) : HuggingFaceLabAction

    data object SubmitTask : HuggingFaceLabAction
}
