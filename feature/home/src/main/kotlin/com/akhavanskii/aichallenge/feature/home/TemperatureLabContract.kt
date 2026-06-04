package com.akhavanskii.aichallenge.feature.home

import com.akhavanskii.aichallenge.core.mvvm.UiEvent
import com.akhavanskii.aichallenge.core.mvvm.UiState

data class TemperatureLabUiState(
    val task: String = "",
    val selectedModel: GeminiModelOption = GeminiModelOption.GEMINI_2_5_FLASH,
    val settings: List<TemperatureSetting> = TemperatureSlot.defaultSettings(),
    val outputs: List<TemperatureLabOutput> = initialOutputs(TemperatureSlot.defaultSettings()),
    val evaluationState: ResponsePaneState =
        ResponsePaneState.Empty(
            "The temperature evaluation will appear after all three outputs finish.",
        ),
) : UiState {
    val isLoading: Boolean
        get() = outputs.any { it.state is ResponsePaneState.Loading } || evaluationState is ResponsePaneState.Loading

    val inputEnabled: Boolean
        get() = !isLoading

    val canRun: Boolean
        get() = task.isNotBlank() && !isLoading

    companion object {
        fun initialOutputs(settings: List<TemperatureSetting>): List<TemperatureLabOutput> =
            settings.map { setting ->
                TemperatureLabOutput(
                    slot = setting.slot,
                    temperature = setting.temperature,
                    state = ResponsePaneState.Empty("Run a task to see this temperature output."),
                )
            }

        fun loadingOutputs(settings: List<TemperatureSetting>): List<TemperatureLabOutput> =
            settings.map { setting ->
                TemperatureLabOutput(
                    slot = setting.slot,
                    temperature = setting.temperature,
                    state = ResponsePaneState.Loading,
                )
            }
    }
}

data class TemperatureSetting(
    val slot: TemperatureSlot,
    val temperature: Double,
)

data class TemperatureLabOutput(
    val slot: TemperatureSlot,
    val temperature: Double,
    val state: ResponsePaneState,
)

enum class TemperatureSlot(
    val title: String,
    val defaultTemperature: Double,
    val description: String,
) {
    VARIANT_A(
        title = "Temperature A",
        defaultTemperature = 0.2,
        description = "Starts low for precise, stable, repeatable answers.",
    ),
    VARIANT_B(
        title = "Temperature B",
        defaultTemperature = 0.7,
        description = "Starts balanced for everyday tasks with some variation.",
    ),
    VARIANT_C(
        title = "Temperature C",
        defaultTemperature = 1.4,
        description = "Starts high for divergent, exploratory, creative answers.",
    ),
    ;

    companion object {
        const val MIN_TEMPERATURE = 0.0
        const val MAX_TEMPERATURE = 2.0
        const val TEMPERATURE_STEP = 0.05

        fun defaultSettings(): List<TemperatureSetting> =
            entries.map { slot ->
                TemperatureSetting(
                    slot = slot,
                    temperature = slot.defaultTemperature,
                )
            }
    }
}

sealed interface TemperatureLabAction : UiEvent {
    data class TaskChanged(
        val task: String,
    ) : TemperatureLabAction

    data class ModelChanged(
        val model: GeminiModelOption,
    ) : TemperatureLabAction

    data class TemperatureChanged(
        val slot: TemperatureSlot,
        val temperature: Double,
    ) : TemperatureLabAction

    data object SubmitTask : TemperatureLabAction
}
