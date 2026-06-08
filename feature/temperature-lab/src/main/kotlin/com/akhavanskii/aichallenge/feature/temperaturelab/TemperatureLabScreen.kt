package com.akhavanskii.aichallenge.feature.temperaturelab

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
import androidx.compose.material3.Slider
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
import com.akhavanskii.aichallenge.feature.common.GeminiModelOption
import com.akhavanskii.aichallenge.feature.common.ResponsePaneState
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun TemperatureLabScreen(
    state: TemperatureLabUiState,
    onAction: (TemperatureLabAction) -> Unit,
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
            TemperatureLabHeader(onBack = onBack)
            TemperatureLabInput(
                state = state,
                onAction = onAction,
            )
            TemperatureLabOutputs(outputs = state.outputs)
            TemperatureLabEvaluation(state = state.evaluationState)
        }
    }
}

@Composable
private fun TemperatureLabHeader(onBack: () -> Unit) {
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
                text = "Temperature Lab",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Run one task with three temperature settings, then let Gemini evaluate the tradeoffs.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onBack,
            modifier = Modifier.testTag(TemperatureLabTags.BACK_BUTTON),
        ) {
            Text("Back")
        }
    }
}

@Composable
private fun TemperatureLabInput(
    state: TemperatureLabUiState,
    onAction: (TemperatureLabAction) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.task,
            onValueChange = { onAction(TemperatureLabAction.TaskChanged(it)) },
            enabled = state.inputEnabled,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 132.dp)
                    .testTag(TemperatureLabTags.TASK_INPUT),
            label = { Text("Task for Gemini") },
            placeholder = { Text("Paste the task you want Gemini to answer with three temperature settings.") },
            minLines = 4,
            shape = RoundedCornerShape(8.dp),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
        )
        TemperatureLabModelSelector(
            selectedModel = state.selectedModel,
            enabled = state.inputEnabled,
            onSelected = { onAction(TemperatureLabAction.ModelChanged(it)) },
        )
        TemperatureSettingsSection(
            settings = state.settings,
            enabled = state.inputEnabled,
            onTemperatureChanged = { slot, temperature ->
                onAction(TemperatureLabAction.TemperatureChanged(slot = slot, temperature = temperature))
            },
        )
        ChallengeButton(
            onClick = { onAction(TemperatureLabAction.SubmitTask) },
            enabled = state.canRun,
            modifier = Modifier.testTag(TemperatureLabTags.RUN_BUTTON),
        ) {
            Text("Compare temperatures")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemperatureLabModelSelector(
    selectedModel: GeminiModelOption,
    enabled: Boolean,
    onSelected: (GeminiModelOption) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Model",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Main free-tier Gemini Developer API text models.",
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
                    modifier = Modifier.testTag("${TemperatureLabTags.MODEL_PREFIX}_${model.name}"),
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
private fun TemperatureSettingsSection(
    settings: List<TemperatureSetting>,
    enabled: Boolean,
    onTemperatureChanged: (TemperatureSlot, Double) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider()
        Text(
            text = "temperature",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 3,
        ) {
            settings.forEach { setting ->
                TemperatureSlider(
                    setting = setting,
                    enabled = enabled,
                    onTemperatureChanged = onTemperatureChanged,
                    modifier =
                        Modifier
                            .weight(1f)
                            .widthIn(min = 220.dp)
                            .testTag("${TemperatureLabTags.TEMPERATURE_PREFIX}_${setting.slot.name}"),
                )
            }
        }
    }
}

@Composable
private fun TemperatureSlider(
    setting: TemperatureSetting,
    enabled: Boolean,
    onTemperatureChanged: (TemperatureSlot, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 132.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = setting.slot.title,
                style = MaterialTheme.typography.labelLarge,
                color =
                    if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Text(
                text = setting.temperature.formatTemperature(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value =
                setting.temperature
                    .coerceIn(TemperatureSlot.MIN_TEMPERATURE, TemperatureSlot.MAX_TEMPERATURE)
                    .toFloat(),
            onValueChange = { sliderValue ->
                onTemperatureChanged(
                    setting.slot,
                    sliderValue
                        .toDouble()
                        .roundToStep(TemperatureSlot.TEMPERATURE_STEP)
                        .coerceIn(TemperatureSlot.MIN_TEMPERATURE, TemperatureSlot.MAX_TEMPERATURE),
                )
            },
            enabled = enabled,
            valueRange = TemperatureSlot.MIN_TEMPERATURE.toFloat()..TemperatureSlot.MAX_TEMPERATURE.toFloat(),
            steps =
                sliderSteps(
                    valueRange = TemperatureSlot.MIN_TEMPERATURE..TemperatureSlot.MAX_TEMPERATURE,
                    step = TemperatureSlot.TEMPERATURE_STEP,
                ),
        )
        Text(
            text = setting.slot.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemperatureLabOutputs(outputs: List<TemperatureLabOutput>) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(TemperatureLabTags.OUTPUTS),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider()
        Text(
            text = "Three outputs",
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
                TemperatureOutputPane(
                    output = output,
                    modifier =
                        Modifier
                            .weight(1f)
                            .widthIn(min = 260.dp)
                            .testTag("${TemperatureLabTags.OUTPUT_PREFIX}_${output.slot.name}"),
                )
            }
        }
    }
}

@Composable
private fun TemperatureOutputPane(
    output: TemperatureLabOutput,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = output.slot.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TemperatureResponsePane(
            title = "${output.slot.title} (${output.temperature.formatTemperature()})",
            state = output.state,
            loadingTag = "${TemperatureLabTags.LOADING_PREFIX}_${output.slot.name}",
        )
    }
}

@Composable
private fun TemperatureLabEvaluation(state: ResponsePaneState) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(TemperatureLabTags.EVALUATION),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "LLM evaluation",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        TemperatureResponsePane(
            title = "Best-fit tasks for each temperature",
            state = state,
            loadingTag = TemperatureLabTags.EVALUATION_LOADING,
        )
    }
}

@Composable
private fun TemperatureResponsePane(
    title: String,
    state: ResponsePaneState,
    loadingTag: String,
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
                        body = "Waiting for Gemini",
                    )
                }
            is ResponsePaneState.Success ->
                ResponsePanel(
                    title = title,
                    body = state.response,
                )
            is ResponsePaneState.Error ->
                ResponsePanel(
                    title = title,
                    body = state.message,
                    isError = true,
                )
        }
    }
}

object TemperatureLabTags {
    const val BACK_BUTTON = "temperature_lab_back_button"
    const val MODEL_PREFIX = "temperature_lab_model"
    const val TASK_INPUT = "temperature_lab_task_input"
    const val TEMPERATURE_PREFIX = "temperature_lab_temperature"
    const val RUN_BUTTON = "temperature_lab_run_button"
    const val OUTPUTS = "temperature_lab_outputs"
    const val OUTPUT_PREFIX = "temperature_lab_output"
    const val LOADING_PREFIX = "temperature_lab_loading"
    const val EVALUATION = "temperature_lab_evaluation"
    const val EVALUATION_LOADING = "temperature_lab_evaluation_loading"
}

private fun Double.roundToStep(step: Double): Double {
    val multiplier = (1.0 / step).roundToInt().coerceAtLeast(1)
    return (this * multiplier).roundToInt() / multiplier.toDouble()
}

private fun Double.formatTemperature(): String = "%.2f".format(Locale.US, this)

private fun sliderSteps(
    valueRange: ClosedFloatingPointRange<Double>,
    step: Double,
): Int {
    val selectableValues = ((valueRange.endInclusive - valueRange.start) / step).roundToInt() + 1
    return (selectableValues - 2).coerceAtLeast(0)
}

@Preview(showBackground = true, widthDp = 390, heightDp = 940)
@Composable
fun TemperatureLabIdlePreview() {
    AIChallengeTheme(dynamicColor = false) {
        TemperatureLabScreen(
            state = TemperatureLabUiState(),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 1200, heightDp = 860)
@Composable
fun TemperatureLabSuccessWidePreview() {
    AIChallengeTheme(dynamicColor = false) {
        TemperatureLabScreen(
            state =
                TemperatureLabUiState(
                    task = "Write a product strategy memo.",
                    outputs =
                        TemperatureSlot.defaultSettings().map { setting ->
                            TemperatureLabOutput(
                                slot = setting.slot,
                                temperature = setting.temperature,
                                state = ResponsePaneState.Success("${setting.slot.title} answer with visible tradeoffs."),
                            )
                        },
                    evaluationState =
                        ResponsePaneState.Success(
                            "Temperature A is best for strict tasks, B for balanced analysis, C for ideation.",
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
    heightDp = 940,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun TemperatureLabLoadingDarkPreview() {
    AIChallengeTheme(dynamicColor = false) {
        TemperatureLabScreen(
            state =
                TemperatureLabUiState(
                    task = "Draft three concepts for a launch campaign.",
                    outputs = TemperatureLabUiState.loadingOutputs(TemperatureSlot.defaultSettings()),
                    evaluationState = ResponsePaneState.Empty("Waiting for all three outputs before evaluation."),
                ),
            onAction = {},
            onBack = {},
        )
    }
}
