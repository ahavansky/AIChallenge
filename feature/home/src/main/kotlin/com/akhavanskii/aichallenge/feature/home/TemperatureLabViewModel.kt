package com.akhavanskii.aichallenge.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akhavanskii.aichallenge.core.network.GeminiGenerationConfig
import com.akhavanskii.aichallenge.core.network.GeminiResult
import com.akhavanskii.aichallenge.core.network.GeminiTextClient
import com.akhavanskii.aichallenge.core.utils.normalizedPromptOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TemperatureLabViewModel
    @Inject
    constructor(
        private val geminiTextClient: GeminiTextClient,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(TemperatureLabUiState())
        val uiState: StateFlow<TemperatureLabUiState> = mutableUiState.asStateFlow()

        fun onAction(action: TemperatureLabAction) {
            when (action) {
                is TemperatureLabAction.TaskChanged -> onTaskChanged(action.task)
                is TemperatureLabAction.ModelChanged -> onModelChanged(action.model)
                is TemperatureLabAction.TemperatureChanged -> onTemperatureChanged(action.slot, action.temperature)
                TemperatureLabAction.SubmitTask -> submitTask()
            }
        }

        private fun onTaskChanged(task: String) {
            mutableUiState.update { current ->
                current.copy(
                    task = task,
                    outputs =
                        if (task.isBlank()) {
                            TemperatureLabUiState.initialOutputs(current.settings)
                        } else {
                            current.outputs.resetCompletedOutputs()
                        },
                    evaluationState =
                        if (task.isBlank()) {
                            ResponsePaneState.Empty("The temperature evaluation will appear after all three outputs finish.")
                        } else {
                            ResponsePaneState.Empty("Ready to compare three temperature settings.")
                        },
                )
            }
        }

        private fun onModelChanged(model: GeminiModelOption) {
            mutableUiState.update { current ->
                if (current.isLoading) {
                    current
                } else {
                    current.copy(
                        selectedModel = model,
                        outputs = TemperatureLabUiState.initialOutputs(current.settings),
                        evaluationState = ResponsePaneState.Empty("Ready to compare three temperature settings."),
                    )
                }
            }
        }

        private fun onTemperatureChanged(
            slot: TemperatureSlot,
            temperature: Double,
        ) {
            mutableUiState.update { current ->
                if (current.isLoading) {
                    current
                } else {
                    val updatedSettings =
                        current.settings.map { setting ->
                            if (setting.slot == slot) {
                                setting.copy(
                                    temperature =
                                        temperature.coerceIn(
                                            TemperatureSlot.MIN_TEMPERATURE,
                                            TemperatureSlot.MAX_TEMPERATURE,
                                        ),
                                )
                            } else {
                                setting
                            }
                        }
                    current.copy(
                        settings = updatedSettings,
                        outputs = TemperatureLabUiState.initialOutputs(updatedSettings),
                        evaluationState = ResponsePaneState.Empty("Ready to compare three temperature settings."),
                    )
                }
            }
        }

        private fun submitTask() {
            val currentState = mutableUiState.value
            val task = currentState.task.normalizedPromptOrNull()
            val modelName = currentState.selectedModel.modelName
            val settings = currentState.settings
            if (task == null) {
                mutableUiState.update {
                    it.copy(evaluationState = ResponsePaneState.Error("Enter a task before running Temperature Lab."))
                }
                return
            }

            viewModelScope.launch {
                mutableUiState.update {
                    it.copy(
                        task = task,
                        outputs = TemperatureLabUiState.loadingOutputs(settings),
                        evaluationState = ResponsePaneState.Empty("Waiting for all three outputs before evaluation."),
                    )
                }

                coroutineScope {
                    val deferredResults =
                        settings.associate { setting ->
                            setting.slot to async { runTemperaturePrompt(task = task, modelName = modelName, setting = setting) }
                        }

                    deferredResults.forEach { (slot, deferred) ->
                        launch {
                            updateOutput(slot = slot, state = deferred.await().toPaneState())
                        }
                    }

                    val results = awaitAllBySlot(deferredResults)
                    results.forEach { (slot, result) ->
                        updateOutput(slot = slot, state = result.toPaneState())
                    }
                    evaluateOutputs(task = task, modelName = modelName, settings = settings, results = results)
                }
            }
        }

        private suspend fun runTemperaturePrompt(
            task: String,
            modelName: String,
            setting: TemperatureSetting,
        ): TemperatureRunResult =
            geminiTextClient
                .generate(
                    prompt = task,
                    generationConfig = GeminiGenerationConfig(temperature = setting.temperature),
                    modelName = modelName,
                ).toTemperatureRunResult()

        private suspend fun evaluateOutputs(
            task: String,
            modelName: String,
            settings: List<TemperatureSetting>,
            results: Map<TemperatureSlot, TemperatureRunResult>,
        ) {
            if (results.values.none { it is TemperatureRunResult.Success }) {
                mutableUiState.update {
                    it.copy(evaluationState = ResponsePaneState.Error("Evaluation skipped: no successful temperature outputs."))
                }
                return
            }

            mutableUiState.update { it.copy(evaluationState = ResponsePaneState.Loading) }
            val evaluationPrompt = buildEvaluationPrompt(task = task, settings = settings, results = results)
            val evaluationState =
                when (
                    val result =
                        geminiTextClient.generate(
                            prompt = evaluationPrompt,
                            generationConfig = null,
                            modelName = modelName,
                        )
                ) {
                    is GeminiResult.Success -> ResponsePaneState.Success(result.value)
                    is GeminiResult.Failure -> ResponsePaneState.Error(result.error.userMessage)
                }
            mutableUiState.update { it.copy(evaluationState = evaluationState) }
        }

        private fun updateOutput(
            slot: TemperatureSlot,
            state: ResponsePaneState,
        ) {
            mutableUiState.update { current ->
                current.copy(
                    outputs =
                        current.outputs.map { output ->
                            if (output.slot == slot) {
                                output.copy(state = state)
                            } else {
                                output
                            }
                        },
                )
            }
        }

        private fun buildEvaluationPrompt(
            task: String,
            settings: List<TemperatureSetting>,
            results: Map<TemperatureSlot, TemperatureRunResult>,
        ): String {
            val outputs =
                settings.joinToString(separator = "\n\n") { setting ->
                    val resultText =
                        when (val result = results[setting.slot]) {
                            is TemperatureRunResult.Success -> result.response
                            is TemperatureRunResult.Error -> "ERROR: ${result.message}"
                            null -> "ERROR: Missing output."
                        }
                    "${setting.slot.title} (temperature=${setting.temperature.formatTemperature()}):\n$resultText"
                }

            return "Ты сравниваешь три ответа Gemini на одну задачу, где отличается только параметр temperature.\n\n" +
                "Исходная задача:\n$task\n\n" +
                "Ответы:\n$outputs\n\n" +
                "Оцени:\n" +
                "1. Чем отличаются ответы по точности, структуре, полноте и креативности.\n" +
                "2. Для каких типов задач лучше подходит каждая настройка temperature.\n" +
                "3. Какую настройку выбрать для этой конкретной задачи и почему.\n\n" +
                "Верни краткий сравнительный вывод и практические рекомендации."
        }

        private fun GeminiResult<String>.toTemperatureRunResult(): TemperatureRunResult =
            when (this) {
                is GeminiResult.Success -> TemperatureRunResult.Success(value)
                is GeminiResult.Failure -> TemperatureRunResult.Error(error.userMessage)
            }

        private fun TemperatureRunResult.toPaneState(): ResponsePaneState =
            when (this) {
                is TemperatureRunResult.Success -> ResponsePaneState.Success(response)
                is TemperatureRunResult.Error -> ResponsePaneState.Error(message)
            }

        private suspend fun awaitAllBySlot(
            deferredResults: Map<TemperatureSlot, Deferred<TemperatureRunResult>>,
        ): Map<TemperatureSlot, TemperatureRunResult> = deferredResults.mapValues { (_, deferred) -> deferred.await() }

        private fun List<TemperatureLabOutput>.resetCompletedOutputs(): List<TemperatureLabOutput> =
            map { output ->
                output.copy(state = ResponsePaneState.Empty("Ready to run this temperature."))
            }

        private fun Double.formatTemperature(): String = "%.2f".format(java.util.Locale.US, this)

        private sealed interface TemperatureRunResult {
            data class Success(
                val response: String,
            ) : TemperatureRunResult

            data class Error(
                val message: String,
            ) : TemperatureRunResult
        }
    }
