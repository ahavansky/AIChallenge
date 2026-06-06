package com.akhavanskii.aichallenge.feature.home

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.akhavanskii.aichallenge.core.designsystem.AIChallengeTheme
import com.akhavanskii.aichallenge.core.designsystem.ChallengeButton
import com.akhavanskii.aichallenge.core.designsystem.ResponsePanel
import com.akhavanskii.aichallenge.core.network.HuggingFaceResponseMetadata
import com.akhavanskii.aichallenge.core.network.HuggingFaceTokenUsage
import java.util.Locale

@Composable
fun HuggingFaceLabScreen(
    state: HuggingFaceLabUiState,
    onAction: (HuggingFaceLabAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .padding(WindowInsets.safeDrawing.asPaddingValues()),
    ) {
        val isWide = maxWidth >= 920.dp
        val contentModifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (isWide) 40.dp else 20.dp, vertical = 24.dp)

        Column(
            modifier = contentModifier,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            HuggingFaceLabHeader(onBack = onBack)
            HuggingFaceLabInput(
                state = state,
                onAction = onAction,
            )
            HuggingFaceModelsSection()
            HuggingFaceOutputs(outputs = state.outputs)
            HuggingFaceEvaluation(state = state.evaluationState)
        }
    }
}

@Composable
private fun HuggingFaceLabHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "HuggingFace Lab",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Benchmark three HuggingFace models, then let Gemini compare quality, speed, and tokens.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onBack,
            modifier = Modifier.testTag(HuggingFaceLabTags.BACK_BUTTON),
        ) {
            Text("Back")
        }
    }
}

@Composable
private fun HuggingFaceLabInput(
    state: HuggingFaceLabUiState,
    onAction: (HuggingFaceLabAction) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.task,
            onValueChange = { onAction(HuggingFaceLabAction.TaskChanged(it)) },
            enabled = state.inputEnabled,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 132.dp)
                    .testTag(HuggingFaceLabTags.TASK_INPUT),
            label = { Text("Task for HuggingFace models") },
            placeholder = { Text("Paste the task you want three HuggingFace models to answer.") },
            minLines = 4,
            shape = RoundedCornerShape(8.dp),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
        )
        HuggingFaceGeminiModelSelector(
            selectedModel = state.selectedGeminiModel,
            enabled = state.inputEnabled,
            onSelected = { onAction(HuggingFaceLabAction.GeminiModelChanged(it)) },
        )
        ChallengeButton(
            onClick = { onAction(HuggingFaceLabAction.SubmitTask) },
            enabled = state.canRun,
            modifier = Modifier.testTag(HuggingFaceLabTags.RUN_BUTTON),
        ) {
            Text("Benchmark 3 models")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HuggingFaceGeminiModelSelector(
    selectedModel: GeminiModelOption,
    enabled: Boolean,
    onSelected: (GeminiModelOption) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Gemini evaluator model",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "This Gemini model receives all HuggingFace answers and writes the final comparison.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GeminiModelOption.entries.forEach { model ->
                FilterChip(
                    selected = model == selectedModel,
                    onClick = { onSelected(model) },
                    enabled = enabled,
                    label = { Text(model.title) },
                    modifier = Modifier.testTag("${HuggingFaceLabTags.GEMINI_MODEL_PREFIX}_${model.name}"),
                )
            }
        }
        Text(
            text = "${selectedModel.modelName}: ${selectedModel.description}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HuggingFaceModelsSection() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(HuggingFaceLabTags.MODELS),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider()
        Text(
            text = "HuggingFace models",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "The presets are selected from the beginning, middle, and end of HuggingFace's recommended chat model list.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 3,
        ) {
            HuggingFaceModelPreset.entries.forEach { preset ->
                ResponsePanel(
                    title = "${preset.strengthLabel}: ${preset.title}",
                    body =
                        "${preset.modelName}\n" +
                            "Provider: ${preset.provider}; size: ${preset.sizeLabel}\n" +
                            "${preset.capabilitySummary}\n" +
                            preset.description,
                    modifier =
                        Modifier
                            .weight(1f)
                            .widthIn(min = 260.dp)
                            .testTag("${HuggingFaceLabTags.MODEL_PREFIX}_${preset.name}"),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HuggingFaceOutputs(outputs: List<HuggingFaceLabOutput>) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(HuggingFaceLabTags.OUTPUTS),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Three model outputs",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 3,
        ) {
            outputs.forEach { output ->
                HuggingFaceOutputPane(
                    output = output,
                    modifier =
                        Modifier
                            .weight(1f)
                            .widthIn(min = 260.dp)
                            .testTag("${HuggingFaceLabTags.OUTPUT_PREFIX}_${output.preset.name}"),
                )
            }
        }
    }
}

@Composable
private fun HuggingFaceOutputPane(
    output: HuggingFaceLabOutput,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = output.preset.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HuggingFaceResponsePane(
            title = "${output.preset.strengthLabel}: ${output.preset.title}",
            state = output.state,
            metrics = output.metricsText(),
            loadingTag = "${HuggingFaceLabTags.LOADING_PREFIX}_${output.preset.name}",
            loadingBody = "Waiting for HuggingFace",
        )
    }
}

@Composable
private fun HuggingFaceEvaluation(state: ResponsePaneState) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(HuggingFaceLabTags.EVALUATION),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Gemini evaluation",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        HuggingFaceResponsePane(
            title = "Criteria: quality, speed, resource usage",
            state = state,
            metrics = null,
            loadingTag = HuggingFaceLabTags.EVALUATION_LOADING,
            loadingBody = "Waiting for Gemini",
        )
    }
}

@Composable
private fun HuggingFaceResponsePane(
    title: String,
    state: ResponsePaneState,
    metrics: String?,
    loadingTag: String,
    loadingBody: String,
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopStart,
    ) {
        when (state) {
            is ResponsePaneState.Empty ->
                ResponsePanel(
                    title = title,
                    body = state.message,
                )
            ResponsePaneState.Loading ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(modifier = Modifier.testTag(loadingTag))
                    Spacer(modifier = Modifier.height(16.dp))
                    ResponsePanel(
                        title = title,
                        body = loadingBody,
                    )
                }
            is ResponsePaneState.Success ->
                ResponsePanel(
                    title = title,
                    body = listOfNotNull(metrics, state.response).joinToString(separator = "\n\n"),
                )
            is ResponsePaneState.Error ->
                ResponsePanel(
                    title = title,
                    body = listOfNotNull(metrics, state.message).joinToString(separator = "\n\n"),
                    isError = true,
                )
        }
    }
}

object HuggingFaceLabTags {
    const val BACK_BUTTON = "huggingface_lab_back_button"
    const val TASK_INPUT = "huggingface_lab_task_input"
    const val GEMINI_MODEL_PREFIX = "huggingface_lab_gemini_model"
    const val RUN_BUTTON = "huggingface_lab_run_button"
    const val MODELS = "huggingface_lab_models"
    const val MODEL_PREFIX = "huggingface_lab_model"
    const val OUTPUTS = "huggingface_lab_outputs"
    const val OUTPUT_PREFIX = "huggingface_lab_output"
    const val LOADING_PREFIX = "huggingface_lab_loading"
    const val EVALUATION = "huggingface_lab_evaluation"
    const val EVALUATION_LOADING = "huggingface_lab_evaluation_loading"
}

private fun HuggingFaceLabOutput.metricsText(): String? {
    if (responseTimeMillis == null && tokenUsage == null && metadata == null) {
        return null
    }
    return "Response time: ${responseTimeMillis?.let { "$it ms" } ?: "unknown"}\n" +
        "Throughput: ${completionTokensPerSecond().formatRate()} completion tok/s\n" +
        "Attempts: ${metadata?.attemptCount?.toString() ?: "unknown"}; retries: ${metadata.retryCountLabel()}\n" +
        "Finish reason: ${metadata.formatFinishReasons()}\n" +
        "Tokens: ${tokenUsage.formatTokenSummary()}"
}

private fun HuggingFaceTokenUsage?.formatTokenSummary(): String =
    if (this == null) {
        "unknown"
    } else {
        "total ${totalTokens.formatTokenCount()} " +
            "(prompt ${promptTokens.formatTokenCount()}, completion ${completionTokens.formatTokenCount()}, " +
            "visible ${visibleOutputTokens.formatTokenCount()}, reasoning ${reasoningTokens.formatTokenCount()})"
    }

private fun Int?.formatTokenCount(): String = this?.toString() ?: "unknown"

private fun HuggingFaceLabOutput.completionTokensPerSecond(): Double? {
    val tokens = tokenUsage?.completionTokens ?: return null
    val millis = responseTimeMillis ?: return null
    if (millis <= 0L) return null
    return tokens * 1000.0 / millis
}

private fun Double?.formatRate(): String = this?.let { String.format(Locale.US, "%.2f", it) } ?: "unknown"

private fun HuggingFaceResponseMetadata?.retryCountLabel(): String =
    this?.let { ((it.attemptCount - 1).coerceAtLeast(0)).toString() } ?: "unknown"

private fun HuggingFaceResponseMetadata?.formatFinishReasons(): String =
    this?.finishReasons?.takeIf { it.isNotEmpty() }?.joinToString(separator = ", ") ?: "unknown"

@Preview(showBackground = true, widthDp = 390, heightDp = 980)
@Composable
fun HuggingFaceLabIdlePreview() {
    AIChallengeTheme(dynamicColor = false) {
        HuggingFaceLabScreen(
            state = HuggingFaceLabUiState(),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 1200, heightDp = 900)
@Composable
fun HuggingFaceLabSuccessWidePreview() {
    AIChallengeTheme(dynamicColor = false) {
        HuggingFaceLabScreen(
            state =
                HuggingFaceLabUiState(
                    task = "Compare architectural tradeoffs for a small Android app.",
                    outputs =
                        HuggingFaceModelPreset.entries.mapIndexed { index, preset ->
                            HuggingFaceLabOutput(
                                preset = preset,
                                state = ResponsePaneState.Success("${preset.title} answer with concrete tradeoffs."),
                                responseTimeMillis = 900L + index * 300L,
                                tokenUsage =
                                    HuggingFaceTokenUsage(
                                        promptTokens = 40,
                                        completionTokens = 120 + index * 20,
                                        totalTokens = 160 + index * 20,
                                        reasoningTokens = 30 + index * 5,
                                    ),
                                metadata =
                                    HuggingFaceResponseMetadata(
                                        attemptCount = 1 + index,
                                        finishReasons = listOf("stop"),
                                    ),
                            )
                        },
                    evaluationState =
                        ResponsePaneState.Success(
                            "GPT-OSS 120B gives stronger reasoning, while GPT-OSS 20B is the faster lower-cost baseline.",
                        ),
                ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview(
    showBackground = true,
    widthDp = 390,
    heightDp = 980,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun HuggingFaceLabLoadingDarkPreview() {
    AIChallengeTheme(dynamicColor = false) {
        HuggingFaceLabScreen(
            state =
                HuggingFaceLabUiState(
                    task = "Draft a concise testing strategy.",
                    outputs = HuggingFaceLabUiState.loadingOutputs(),
                    evaluationState = ResponsePaneState.Empty("Waiting for all three model outputs before evaluation."),
                ),
            onAction = {},
            onBack = {},
        )
    }
}
