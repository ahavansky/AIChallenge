package com.akhavanskii.aichallenge.feature.home

import com.akhavanskii.aichallenge.core.mvvm.UiEvent
import com.akhavanskii.aichallenge.core.mvvm.UiState

data class PromptLabUiState(
    val task: String = "",
    val selectedModel: GeminiModelOption = GeminiModelOption.GEMINI_2_5_FLASH,
    val outputs: List<PromptLabStrategyOutput> = initialOutputs(),
    val comparisonState: ResponsePaneState = ResponsePaneState.Empty("The final comparison will appear after all four outputs finish."),
) : UiState {
    val isLoading: Boolean
        get() = outputs.any { it.state is ResponsePaneState.Loading } || comparisonState is ResponsePaneState.Loading

    val inputEnabled: Boolean
        get() = !isLoading

    val canRun: Boolean
        get() = task.isNotBlank() && !isLoading

    companion object {
        fun initialOutputs(): List<PromptLabStrategyOutput> =
            PromptLabStrategy.entries.map { strategy ->
                PromptLabStrategyOutput(
                    strategy = strategy,
                    state = ResponsePaneState.Empty("Run a task to see this method's output."),
                )
            }

        fun loadingOutputs(): List<PromptLabStrategyOutput> =
            PromptLabStrategy.entries.map { strategy ->
                PromptLabStrategyOutput(
                    strategy = strategy,
                    state = ResponsePaneState.Loading,
                )
            }
    }
}

data class PromptLabStrategyOutput(
    val strategy: PromptLabStrategy,
    val state: ResponsePaneState,
)

enum class PromptLabStrategy(
    val title: String,
    val description: String,
) {
    DIRECT(
        title = "Direct prompt",
        description = "The original task is sent exactly as entered.",
    ),
    STEP_BY_STEP(
        title = "Step-by-step",
        description = "Adds the instruction: \"решай пошагово\".",
    ),
    GENERATED_PROMPT(
        title = "Generated prompt",
        description = "First asks Gemini to write a stronger prompt, then runs that prompt.",
    ),
    EXPERT_GROUP(
        title = "Expert group",
        description = "Asks an analyst, engineer, and critic to solve the task separately.",
    ),
}

sealed interface PromptLabAction : UiEvent {
    data class TaskChanged(
        val task: String,
    ) : PromptLabAction

    data class ModelChanged(
        val model: GeminiModelOption,
    ) : PromptLabAction

    data object SubmitTask : PromptLabAction
}
