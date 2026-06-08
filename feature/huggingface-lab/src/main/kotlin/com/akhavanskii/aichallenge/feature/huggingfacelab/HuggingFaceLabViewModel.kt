package com.akhavanskii.aichallenge.feature.huggingfacelab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akhavanskii.aichallenge.core.network.GeminiResult
import com.akhavanskii.aichallenge.core.network.GeminiTextClient
import com.akhavanskii.aichallenge.core.network.HuggingFaceResponseMetadata
import com.akhavanskii.aichallenge.core.network.HuggingFaceResult
import com.akhavanskii.aichallenge.core.network.HuggingFaceTextClient
import com.akhavanskii.aichallenge.core.network.HuggingFaceTextResponse
import com.akhavanskii.aichallenge.core.network.HuggingFaceTokenUsage
import com.akhavanskii.aichallenge.core.utils.normalizedPromptOrNull
import com.akhavanskii.aichallenge.feature.common.GeminiModelOption
import com.akhavanskii.aichallenge.feature.common.ResponsePaneState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import kotlin.time.TimeSource

@HiltViewModel
class HuggingFaceLabViewModel
    @Inject
    constructor(
        private val huggingFaceTextClient: HuggingFaceTextClient,
        private val geminiTextClient: GeminiTextClient,
    ) : ViewModel() {
        private val mutableUiState = MutableStateFlow(HuggingFaceLabUiState())
        val uiState: StateFlow<HuggingFaceLabUiState> = mutableUiState.asStateFlow()

        fun onAction(action: HuggingFaceLabAction) {
            when (action) {
                is HuggingFaceLabAction.TaskChanged -> onTaskChanged(action.task)
                is HuggingFaceLabAction.GeminiModelChanged -> onGeminiModelChanged(action.model)
                HuggingFaceLabAction.SubmitTask -> submitTask()
            }
        }

        private fun onTaskChanged(task: String) {
            mutableUiState.update { current ->
                current.copy(
                    task = task,
                    outputs =
                        if (task.isBlank()) {
                            HuggingFaceLabUiState.initialOutputs()
                        } else {
                            current.outputs.resetCompletedOutputs()
                        },
                    evaluationState =
                        if (task.isBlank()) {
                            ResponsePaneState.Empty("Gemini evaluation will appear after the three HuggingFace outputs finish.")
                        } else {
                            ResponsePaneState.Empty("Ready to benchmark three HuggingFace models.")
                        },
                )
            }
        }

        private fun onGeminiModelChanged(model: GeminiModelOption) {
            mutableUiState.update { current ->
                if (current.isLoading) {
                    current
                } else {
                    current.copy(selectedGeminiModel = model)
                }
            }
        }

        private fun submitTask() {
            val currentState = mutableUiState.value
            val task = currentState.task.normalizedPromptOrNull()
            val geminiModelName = currentState.selectedGeminiModel.modelName
            if (task == null) {
                mutableUiState.update {
                    it.copy(evaluationState = ResponsePaneState.Error("Enter a task before running HuggingFace Lab."))
                }
                return
            }

            viewModelScope.launch {
                mutableUiState.update {
                    it.copy(
                        task = task,
                        outputs = HuggingFaceLabUiState.loadingOutputs(),
                        evaluationState = ResponsePaneState.Empty("Waiting for all three model outputs before evaluation."),
                    )
                }

                coroutineScope {
                    val deferredResults =
                        HuggingFaceModelPreset.entries.associateWith { preset ->
                            async { runHuggingFaceModel(task = task, preset = preset) }
                        }

                    deferredResults.forEach { (preset, deferred) ->
                        launch {
                            updateOutput(preset = preset, output = deferred.await().toOutput(preset))
                        }
                    }

                    val results = awaitAllByPreset(deferredResults)
                    results.forEach { (preset, result) ->
                        updateOutput(preset = preset, output = result.toOutput(preset))
                    }
                    evaluateOutputs(task = task, geminiModelName = geminiModelName, results = results)
                }
            }
        }

        private suspend fun runHuggingFaceModel(
            task: String,
            preset: HuggingFaceModelPreset,
        ): ModelRunResult {
            val startedAt = TimeSource.Monotonic.markNow()
            val result =
                huggingFaceTextClient.generate(
                    prompt = task,
                    modelName = preset.modelName,
                )
            val elapsedMillis = startedAt.elapsedNow().inWholeMilliseconds.coerceAtLeast(0L)
            return result.toModelRunResult(responseTimeMillis = elapsedMillis)
        }

        private suspend fun evaluateOutputs(
            task: String,
            geminiModelName: String,
            results: Map<HuggingFaceModelPreset, ModelRunResult>,
        ) {
            if (results.values.none { it is ModelRunResult.Success }) {
                mutableUiState.update {
                    it.copy(evaluationState = ResponsePaneState.Error("Evaluation skipped: no successful HuggingFace outputs."))
                }
                return
            }

            mutableUiState.update { it.copy(evaluationState = ResponsePaneState.Loading) }
            val evaluationPrompt = buildEvaluationPrompt(task = task, results = results)
            val evaluationState =
                when (
                    val result =
                        geminiTextClient.generate(
                            prompt = evaluationPrompt,
                            generationConfig = null,
                            modelName = geminiModelName,
                        )
                ) {
                    is GeminiResult.Success -> ResponsePaneState.Success(result.value)
                    is GeminiResult.Failure -> ResponsePaneState.Error(result.error.userMessage)
                }
            mutableUiState.update { it.copy(evaluationState = evaluationState) }
        }

        private fun updateOutput(
            preset: HuggingFaceModelPreset,
            output: HuggingFaceLabOutput,
        ) {
            mutableUiState.update { current ->
                current.copy(
                    outputs =
                        current.outputs.map { currentOutput ->
                            if (currentOutput.preset == preset) {
                                output
                            } else {
                                currentOutput
                            }
                        },
                )
            }
        }

        private fun buildEvaluationPrompt(
            task: String,
            results: Map<HuggingFaceModelPreset, ModelRunResult>,
        ): String {
            val outputs =
                HuggingFaceModelPreset.entries.joinToString(separator = "\n\n") { preset ->
                    val result = results[preset]
                    val resultText =
                        when (result) {
                            is ModelRunResult.Success -> result.response
                            is ModelRunResult.Error -> "ERROR: ${result.message}"
                            null -> "ERROR: Missing output."
                        }
                    val metrics =
                        when (result) {
                            is ModelRunResult.Success ->
                                result.formatMetricsForPrompt()
                            is ModelRunResult.Error ->
                                "responseTimeMs=${result.responseTimeMillis}; tokens=unknown"
                            null -> "responseTimeMs=unknown; tokens=unknown"
                        }
                    "${preset.strengthLabel}: ${preset.title}\n" +
                        "model=${preset.modelName}\n" +
                        "provider=${preset.provider}; size=${preset.sizeLabel}; capabilities=${preset.capabilitySummary}\n" +
                        "metrics=$metrics\n" +
                        "answer:\n$resultText"
                }

            return "Ты сравниваешь ответы трех моделей HuggingFace на одну задачу.\n\n" +
                "Исходная задача:\n$task\n\n" +
                "Ответы и метрики:\n$outputs\n\n" +
                "Критерии сравнения:\n" +
                "1. Качество ответов: точность, полнота, структура, полезность.\n" +
                "2. Следование инструкции: выполнила ли модель формат и ограничения задачи.\n" +
                "3. Ясность: насколько ответ понятный, структурированный и без лишнего шума.\n" +
                "4. Риск галлюцинаций: есть ли неподтвержденные факты или неверные допущения.\n" +
                "5. Скорость: сравни responseTimeMs и completionTokensPerSecond; меньше latency и выше throughput обычно лучше.\n" +
                "6. Стабильность: учитывай retryCount и finishReasons, особенно length/error.\n" +
                "7. Ресурсоемкость: сравни total_tokens, prompt_tokens, completion_tokens, visible_output_tokens и reasoning_tokens.\n\n" +
                "Верни краткую таблицу со score 1-5 для accuracy, instruction_following, clarity и hallucination_risk. " +
                "После таблицы дай рейтинг моделей, объясни компромисс качество/скорость/ресурсоемкость и укажи, " +
                "какую модель выбрать для этой конкретной задачи."
        }

        private fun HuggingFaceResult<HuggingFaceTextResponse>.toModelRunResult(responseTimeMillis: Long): ModelRunResult =
            when (this) {
                is HuggingFaceResult.Success ->
                    ModelRunResult.Success(
                        response = value.text,
                        responseTimeMillis = responseTimeMillis,
                        tokenUsage = value.tokenUsage,
                        metadata = value.metadata,
                    )
                is HuggingFaceResult.Failure ->
                    ModelRunResult.Error(
                        message = error.userMessage,
                        responseTimeMillis = responseTimeMillis,
                    )
            }

        private fun ModelRunResult.toOutput(preset: HuggingFaceModelPreset): HuggingFaceLabOutput =
            when (this) {
                is ModelRunResult.Success ->
                    HuggingFaceLabOutput(
                        preset = preset,
                        state = ResponsePaneState.Success(response),
                        responseTimeMillis = responseTimeMillis,
                        tokenUsage = tokenUsage,
                        metadata = metadata,
                    )
                is ModelRunResult.Error ->
                    HuggingFaceLabOutput(
                        preset = preset,
                        state = ResponsePaneState.Error(message),
                        responseTimeMillis = responseTimeMillis,
                    )
            }

        private suspend fun awaitAllByPreset(
            deferredResults: Map<HuggingFaceModelPreset, Deferred<ModelRunResult>>,
        ): Map<HuggingFaceModelPreset, ModelRunResult> = deferredResults.mapValues { (_, deferred) -> deferred.await() }

        private fun List<HuggingFaceLabOutput>.resetCompletedOutputs(): List<HuggingFaceLabOutput> =
            map { output ->
                output.copy(
                    state = ResponsePaneState.Empty("Ready to benchmark this model."),
                    responseTimeMillis = null,
                    tokenUsage = null,
                    metadata = null,
                )
            }

        private fun ModelRunResult.Success.formatMetricsForPrompt(): String =
            "responseTimeMs=$responseTimeMillis; " +
                "completionTokensPerSecond=${completionTokensPerSecond().formatDecimalOrUnknown()}; " +
                "retryCount=${metadata.retryCount()}; " +
                "finishReasons=${metadata.finishReasonsForPrompt()}; " +
                "prompt_tokens=${tokenUsage?.promptTokens.formatCount()}; " +
                "completion_tokens=${tokenUsage?.completionTokens.formatCount()}; " +
                "visible_output_tokens=${tokenUsage?.visibleOutputTokens.formatCount()}; " +
                "reasoning_tokens=${tokenUsage?.reasoningTokens.formatCount()}; " +
                "total_tokens=${tokenUsage?.totalTokens.formatCount()}"

        private fun ModelRunResult.Success.completionTokensPerSecond(): Double? {
            val completionTokens = tokenUsage?.completionTokens ?: return null
            if (responseTimeMillis <= 0L) return null
            return completionTokens * 1000.0 / responseTimeMillis
        }

        private fun HuggingFaceResponseMetadata?.retryCount(): Int = ((this?.attemptCount ?: 1) - 1).coerceAtLeast(0)

        private fun HuggingFaceResponseMetadata?.finishReasonsForPrompt(): String =
            this?.finishReasons?.takeIf { it.isNotEmpty() }?.joinToString(separator = "|") ?: "unknown"

        private fun Int?.formatCount(): String = this?.toString() ?: "unknown"

        private fun Double?.formatDecimalOrUnknown(): String = this?.let { String.format(Locale.US, "%.2f", it) } ?: "unknown"

        private sealed interface ModelRunResult {
            data class Success(
                val response: String,
                val responseTimeMillis: Long,
                val tokenUsage: HuggingFaceTokenUsage?,
                val metadata: HuggingFaceResponseMetadata,
            ) : ModelRunResult

            data class Error(
                val message: String,
                val responseTimeMillis: Long,
            ) : ModelRunResult
        }
    }
