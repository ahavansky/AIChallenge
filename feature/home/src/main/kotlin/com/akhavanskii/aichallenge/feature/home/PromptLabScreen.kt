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

@Composable
fun PromptLabScreen(
    state: PromptLabUiState,
    onAction: (PromptLabAction) -> Unit,
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
            PromptLabHeader(onBack = onBack)
            PromptLabInput(
                state = state,
                onAction = onAction,
            )
            PromptLabOutputs(outputs = state.outputs)
            PromptLabComparison(state = state.comparisonState)
        }
    }
}

@Composable
private fun PromptLabHeader(onBack: () -> Unit) {
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
                text = "Prompt Lab",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Run one task through four prompting methods, then compare the outputs.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onBack,
            modifier = Modifier.testTag(PromptLabTags.BACK_BUTTON),
        ) {
            Text("Back")
        }
    }
}

@Composable
private fun PromptLabInput(
    state: PromptLabUiState,
    onAction: (PromptLabAction) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.task,
            onValueChange = { onAction(PromptLabAction.TaskChanged(it)) },
            enabled = state.inputEnabled,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 132.dp)
                    .testTag(PromptLabTags.TASK_INPUT),
            label = { Text("Task for Gemini") },
            placeholder = { Text("Paste the task you want Gemini to solve in four different ways.") },
            minLines = 4,
            shape = RoundedCornerShape(8.dp),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
        )
        PromptLabModelSelector(
            selectedModel = state.selectedModel,
            enabled = state.inputEnabled,
            onSelected = { onAction(PromptLabAction.ModelChanged(it)) },
        )
        ChallengeButton(
            onClick = { onAction(PromptLabAction.SubmitTask) },
            enabled = state.canRun,
            modifier = Modifier.testTag(PromptLabTags.RUN_BUTTON),
        ) {
            Text("Run 4 methods")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromptLabModelSelector(
    selectedModel: PromptLabGeminiModel,
    enabled: Boolean,
    onSelected: (PromptLabGeminiModel) -> Unit,
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
            PromptLabGeminiModel.entries.forEach { model ->
                FilterChip(
                    selected = model == selectedModel,
                    onClick = { onSelected(model) },
                    enabled = enabled,
                    label = { Text(model.title) },
                    modifier = Modifier.testTag("${PromptLabTags.MODEL_PREFIX}_${model.name}"),
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
private fun PromptLabOutputs(outputs: List<PromptLabStrategyOutput>) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(PromptLabTags.OUTPUTS),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider()
        Text(
            text = "Four outputs",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 2,
        ) {
            outputs.forEach { output ->
                PromptLabOutputPane(
                    output = output,
                    modifier =
                        Modifier
                            .weight(1f)
                            .widthIn(min = 260.dp)
                            .testTag("${PromptLabTags.OUTPUT_PREFIX}_${output.strategy.name}"),
                )
            }
        }
    }
}

@Composable
private fun PromptLabOutputPane(
    output: PromptLabStrategyOutput,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = output.strategy.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ResponsePane(
            title = output.strategy.title,
            state = output.state,
            loadingTag = "${PromptLabTags.LOADING_PREFIX}_${output.strategy.name}",
        )
    }
}

@Composable
private fun PromptLabComparison(state: ResponsePaneState) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(PromptLabTags.COMPARISON),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "LLM comparison",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        ResponsePane(
            title = "Criteria: differences and most accurate method",
            state = state,
            loadingTag = PromptLabTags.COMPARISON_LOADING,
        )
    }
}

@Composable
private fun ResponsePane(
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

object PromptLabTags {
    const val BACK_BUTTON = "prompt_lab_back_button"
    const val MODEL_PREFIX = "prompt_lab_model"
    const val TASK_INPUT = "prompt_lab_task_input"
    const val RUN_BUTTON = "prompt_lab_run_button"
    const val OUTPUTS = "prompt_lab_outputs"
    const val OUTPUT_PREFIX = "prompt_lab_output"
    const val LOADING_PREFIX = "prompt_lab_loading"
    const val COMPARISON = "prompt_lab_comparison"
    const val COMPARISON_LOADING = "prompt_lab_comparison_loading"
}

@Preview(showBackground = true, widthDp = 390, heightDp = 900)
@Composable
fun PromptLabIdlePreview() {
    AIChallengeTheme(dynamicColor = false) {
        PromptLabScreen(
            state = PromptLabUiState(),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 1100, heightDp = 860)
@Composable
fun PromptLabSuccessWidePreview() {
    AIChallengeTheme(dynamicColor = false) {
        PromptLabScreen(
            state =
                PromptLabUiState(
                    task = "Find the best architecture for a small Android app.",
                    outputs =
                        PromptLabStrategy.entries.map { strategy ->
                            PromptLabStrategyOutput(
                                strategy = strategy,
                                state = ResponsePaneState.Success("${strategy.title} answer with implementation tradeoffs."),
                            )
                        },
                    comparisonState =
                        ResponsePaneState.Success(
                            "The expert group produced the strongest answer because it covered risks and alternatives.",
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
    heightDp = 900,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun PromptLabLoadingDarkPreview() {
    AIChallengeTheme(dynamicColor = false) {
        PromptLabScreen(
            state =
                PromptLabUiState(
                    task = "Explain the tradeoff between topP and temperature.",
                    outputs = PromptLabUiState.loadingOutputs(),
                    comparisonState = ResponsePaneState.Empty("Waiting for all four outputs before comparison."),
                ),
            onAction = {},
            onBack = {},
        )
    }
}
