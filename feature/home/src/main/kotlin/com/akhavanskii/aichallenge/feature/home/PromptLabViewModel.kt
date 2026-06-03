package com.akhavanskii.aichallenge.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
class PromptLabViewModel
    @Inject
    constructor(
        private val geminiTextClient: GeminiTextClient,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(PromptLabUiState())
        val uiState: StateFlow<PromptLabUiState> = mutableUiState.asStateFlow()

        fun onAction(action: PromptLabAction) {
            when (action) {
                is PromptLabAction.TaskChanged -> onTaskChanged(action.task)
                is PromptLabAction.ModelChanged -> onModelChanged(action.model)
                PromptLabAction.SubmitTask -> submitTask()
            }
        }

        private fun onModelChanged(model: PromptLabGeminiModel) {
            mutableUiState.update { current ->
                if (current.isLoading) {
                    current
                } else {
                    current.copy(selectedModel = model)
                }
            }
        }

        private fun onTaskChanged(task: String) {
            mutableUiState.update { current ->
                current.copy(
                    task = task,
                    outputs =
                        if (task.isBlank()) {
                            PromptLabUiState.initialOutputs()
                        } else {
                            current.outputs.resetCompletedOutputs()
                        },
                    comparisonState =
                        if (task.isBlank()) {
                            ResponsePaneState.Empty("The final comparison will appear after all four outputs finish.")
                        } else {
                            ResponsePaneState.Empty("Ready to run four prompt methods.")
                        },
                )
            }
        }

        private fun submitTask() {
            val currentState = mutableUiState.value
            val task = currentState.task.normalizedPromptOrNull()
            val modelName = currentState.selectedModel.modelName
            if (task == null) {
                mutableUiState.update {
                    it.copy(
                        comparisonState = ResponsePaneState.Error("Enter a task before running Prompt Lab."),
                    )
                }
                return
            }

            viewModelScope.launch {
                mutableUiState.update {
                    it.copy(
                        task = task,
                        outputs = PromptLabUiState.loadingOutputs(),
                        comparisonState = ResponsePaneState.Empty("Waiting for all four outputs before comparison."),
                    )
                }

                coroutineScope {
                    val deferredResults =
                        mapOf(
                            PromptLabStrategy.DIRECT to async { runDirectPrompt(task, modelName) },
                            PromptLabStrategy.STEP_BY_STEP to async { runStepByStepPrompt(task, modelName) },
                            PromptLabStrategy.GENERATED_PROMPT to async { runGeneratedPrompt(task, modelName) },
                            PromptLabStrategy.EXPERT_GROUP to async { runExpertGroupPrompt(task, modelName) },
                        )

                    deferredResults.forEach { (strategy, deferred) ->
                        launch {
                            updateOutput(strategy = strategy, state = deferred.await().toPaneState())
                        }
                    }

                    val results = awaitAllByStrategy(deferredResults)
                    results.forEach { (strategy, result) ->
                        updateOutput(strategy = strategy, state = result.toPaneState())
                    }
                    compareOutputs(task = task, modelName = modelName, results = results)
                }
            }
        }

        private suspend fun runDirectPrompt(
            task: String,
            modelName: String,
        ): StrategyRunResult =
            geminiTextClient
                .generate(prompt = task, generationConfig = null, modelName = modelName)
                .toStrategyRunResult()

        private suspend fun runStepByStepPrompt(
            task: String,
            modelName: String,
        ): StrategyRunResult =
            geminiTextClient
                .generate(
                    prompt =
                        "Решай пошагово. Сначала кратко перечисли шаги рассуждения, затем дай итоговое решение.\n\n" +
                            "Задача:\n$task",
                    generationConfig = null,
                    modelName = modelName,
                ).toStrategyRunResult()

        private suspend fun runGeneratedPrompt(
            task: String,
            modelName: String,
        ): StrategyRunResult {
            val promptGenerationResult =
                geminiTextClient.generate(
                    prompt =
                        "Составь лучший промпт для LLM, чтобы решить задачу ниже. " +
                            "Верни только готовый промпт, без пояснений.\n\nЗадача:\n$task",
                    generationConfig = null,
                    modelName = modelName,
                )
            val generatedPrompt =
                when (promptGenerationResult) {
                    is GeminiResult.Success -> promptGenerationResult.value.trim().takeIf { it.isNotEmpty() }
                    is GeminiResult.Failure -> return promptGenerationResult.toStrategyRunResult()
                } ?: return StrategyRunResult.Error("Generated prompt is empty.")

            return when (
                val finalResult =
                    geminiTextClient.generate(
                        prompt = generatedPrompt,
                        generationConfig = null,
                        modelName = modelName,
                    )
            ) {
                is GeminiResult.Success ->
                    StrategyRunResult.Success(
                        "Generated prompt:\n$generatedPrompt\n\nFinal answer:\n${finalResult.value}",
                    )
                is GeminiResult.Failure ->
                    StrategyRunResult.Error(
                        "Generated prompt:\n$generatedPrompt\n\nFinal answer failed:\n${finalResult.error.userMessage}",
                    )
            }
        }

        private suspend fun runExpertGroupPrompt(
            task: String,
            modelName: String,
        ): StrategyRunResult =
            geminiTextClient
                .generate(
                    prompt =
                        "Создай группу экспертов: аналитик, инженер, критик. " +
                            "Каждый эксперт должен отдельно решить задачу. Верни ответ в секциях: " +
                            "Аналитик, Инженер, Критик, Итог.\n\nЗадача:\n$task",
                    generationConfig = null,
                    modelName = modelName,
                ).toStrategyRunResult()

        private suspend fun compareOutputs(
            task: String,
            modelName: String,
            results: Map<PromptLabStrategy, StrategyRunResult>,
        ) {
            if (results.values.none { it is StrategyRunResult.Success }) {
                mutableUiState.update {
                    it.copy(comparisonState = ResponsePaneState.Error("Comparison skipped: no successful strategy outputs."))
                }
                return
            }

            mutableUiState.update { it.copy(comparisonState = ResponsePaneState.Loading) }
            val comparisonPrompt = buildComparisonPrompt(task = task, results = results)
            val comparisonState =
                when (
                    val result =
                        geminiTextClient.generate(
                            prompt = comparisonPrompt,
                            generationConfig = null,
                            modelName = modelName,
                        )
                ) {
                    is GeminiResult.Success -> ResponsePaneState.Success(result.value)
                    is GeminiResult.Failure -> ResponsePaneState.Error(result.error.userMessage)
                }
            mutableUiState.update { it.copy(comparisonState = comparisonState) }
        }

        private fun updateOutput(
            strategy: PromptLabStrategy,
            state: ResponsePaneState,
        ) {
            mutableUiState.update { current ->
                current.copy(
                    outputs =
                        current.outputs.map { output ->
                            if (output.strategy == strategy) {
                                output.copy(state = state)
                            } else {
                                output
                            }
                        },
                )
            }
        }

        private fun buildComparisonPrompt(
            task: String,
            results: Map<PromptLabStrategy, StrategyRunResult>,
        ): String {
            val outputs =
                PromptLabStrategy.entries.joinToString(separator = "\n\n") { strategy ->
                    val resultText =
                        when (val result = results[strategy]) {
                            is StrategyRunResult.Success -> result.response
                            is StrategyRunResult.Error -> "ERROR: ${result.message}"
                            null -> "ERROR: Missing output."
                        }
                    "${strategy.title}:\n$resultText"
                }

            return "Ты сравниваешь четыре ответа LLM на одну задачу.\n\n" +
                "Исходная задача:\n$task\n\n" +
                "Ответы:\n$outputs\n\n" +
                "Критерии сравнения:\n" +
                "1. Отличаются ли ответы? Укажи основные смысловые различия.\n" +
                "2. Какой способ дал наиболее точный результат и почему?\n\n" +
                "Верни краткий вывод, рейтинг способов от лучшего к худшему и практическую рекомендацию."
        }

        private fun GeminiResult<String>.toStrategyRunResult(): StrategyRunResult =
            when (this) {
                is GeminiResult.Success -> StrategyRunResult.Success(value)
                is GeminiResult.Failure -> StrategyRunResult.Error(error.userMessage)
            }

        private fun StrategyRunResult.toPaneState(): ResponsePaneState =
            when (this) {
                is StrategyRunResult.Success -> ResponsePaneState.Success(response)
                is StrategyRunResult.Error -> ResponsePaneState.Error(message)
            }

        private suspend fun awaitAllByStrategy(
            deferredResults: Map<PromptLabStrategy, Deferred<StrategyRunResult>>,
        ): Map<PromptLabStrategy, StrategyRunResult> = deferredResults.mapValues { (_, deferred) -> deferred.await() }

        private fun List<PromptLabStrategyOutput>.resetCompletedOutputs(): List<PromptLabStrategyOutput> =
            map { output ->
                output.copy(state = ResponsePaneState.Empty("Ready to run this method."))
            }

        private sealed interface StrategyRunResult {
            data class Success(
                val response: String,
            ) : StrategyRunResult

            data class Error(
                val message: String,
            ) : StrategyRunResult
        }
    }
