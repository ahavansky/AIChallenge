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
import com.akhavanskii.aichallenge.core.designsystem.ChallengeTextField
import com.akhavanskii.aichallenge.core.designsystem.ResponsePanel
import com.akhavanskii.aichallenge.feature.common.ResponsePaneState
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
    onOpenAgentChat: () -> Unit = {},
    onOpenContextAgent: () -> Unit = {},
    onOpenPromptLab: () -> Unit = {},
    onOpenTemperatureLab: () -> Unit = {},
    onOpenHuggingFaceLab: () -> Unit = {},
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

        if (isWide) {
            Row(
                modifier = contentModifier,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                PromptSection(
                    state = state,
                    onAction = onAction,
                    onOpenAgentChat = onOpenAgentChat,
                    onOpenContextAgent = onOpenContextAgent,
                    onOpenPromptLab = onOpenPromptLab,
                    onOpenTemperatureLab = onOpenTemperatureLab,
                    onOpenHuggingFaceLab = onOpenHuggingFaceLab,
                    modifier = Modifier.weight(0.95f),
                )
                ResultSection(
                    comparisonState = state.comparisonState,
                    modifier = Modifier.weight(1.25f),
                )
            }
        } else {
            Column(
                modifier = contentModifier,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                PromptSection(
                    state = state,
                    onAction = onAction,
                    onOpenAgentChat = onOpenAgentChat,
                    onOpenContextAgent = onOpenContextAgent,
                    onOpenPromptLab = onOpenPromptLab,
                    onOpenTemperatureLab = onOpenTemperatureLab,
                    onOpenHuggingFaceLab = onOpenHuggingFaceLab,
                    modifier = Modifier.fillMaxWidth(),
                )
                ResultSection(
                    comparisonState = state.comparisonState,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromptSection(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    onOpenAgentChat: () -> Unit,
    onOpenContextAgent: () -> Unit,
    onOpenPromptLab: () -> Unit,
    onOpenTemperatureLab: () -> Unit,
    onOpenHuggingFaceLab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "AIChallenge",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = onOpenAgentChat,
                    modifier = Modifier.testTag(HomeTags.AGENT_CHAT_BUTTON),
                ) {
                    Text("Agent Chat")
                }
                TextButton(
                    onClick = onOpenContextAgent,
                    modifier = Modifier.testTag(HomeTags.CONTEXT_AGENT_BUTTON),
                ) {
                    Text("Context Agent")
                }
                TextButton(
                    onClick = onOpenTemperatureLab,
                    modifier = Modifier.testTag(HomeTags.TEMPERATURE_LAB_BUTTON),
                ) {
                    Text("Temperature Lab")
                }
                TextButton(
                    onClick = onOpenHuggingFaceLab,
                    modifier = Modifier.testTag(HomeTags.HUGGINGFACE_LAB_BUTTON),
                ) {
                    Text("HF Lab")
                }
                TextButton(
                    onClick = onOpenPromptLab,
                    modifier = Modifier.testTag(HomeTags.PROMPT_LAB_BUTTON),
                ) {
                    Text("Prompt Lab")
                }
            }
        }
        Text(
            text = "Tune Gemini parameters, send one prompt, and compare configured output against a baseline request.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChallengeTextField(
            value = state.prompt,
            onValueChange = { onAction(HomeAction.PromptChanged(it)) },
            enabled = state.inputEnabled,
            modifier = Modifier.testTag(HomeTags.PROMPT_INPUT),
        )
        GenerationConfigSection(
            config = state.generationConfig,
            enabled = state.inputEnabled,
            onConfigChanged = { onAction(HomeAction.GenerationConfigChanged(it)) },
        )
        ChallengeButton(
            onClick = { onAction(HomeAction.SubmitPrompt) },
            enabled = state.canSend,
            modifier = Modifier.testTag(HomeTags.SEND_BUTTON),
        ) {
            Text("Compare")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenerationConfigSection(
    config: GeminiGenerationConfigUiState,
    enabled: Boolean,
    onConfigChanged: (GeminiGenerationConfigUiState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HorizontalDivider()
        Text(
            text = "generationConfig",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        MimeTypeSelector(
            selectedMimeType = config.responseMimeType,
            enabled = enabled,
            onSelected = { onConfigChanged(config.copy(responseMimeType = it)) },
        )
        ConfigTextField(
            label = "responseSchema",
            value = config.responseSchema,
            onValueChange = { onConfigChanged(config.copy(responseSchema = it)) },
            enabled = enabled,
            description =
                "Optional schema for strict output. Allowed with application/json or text/x.enum only; " +
                    "text/plain with schema can cause HTTP 400. Boundary: empty = no schema; value must be a valid Gemini/OpenAPI " +
                    "schema object; REST type values use OBJECT, STRING, ARRAY, INTEGER, NUMBER, BOOLEAN.",
            modifier = Modifier.testTag(HomeTags.PARAM_RESPONSE_SCHEMA),
            minLines = 4,
            maxLines = 6,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 2,
        ) {
            IntConfigSlider(
                label = "maxOutputTokens",
                value = config.maxOutputTokens,
                onValueChange = { onConfigChanged(config.copy(maxOutputTokens = it)) },
                enabled = enabled,
                valueRange = GeminiGenerationConfigUiState.MIN_MAX_OUTPUT_TOKENS..GeminiGenerationConfigUiState.MAX_MAX_OUTPUT_TOKENS,
                valueText = "${config.maxOutputTokens} tokens",
                description =
                    "Maximum generated tokens. Slider range: 1-4096; model maximum is model-specific. " +
                        "Smaller values make shorter answers; about 100 tokens is roughly 60-80 words.",
                modifier =
                    Modifier
                        .weight(1f)
                        .widthIn(min = 160.dp)
                        .testTag(HomeTags.PARAM_MAX_OUTPUT_TOKENS),
            )
            DoubleConfigSlider(
                label = "temperature",
                value = config.temperature,
                onValueChange = { onConfigChanged(config.copy(temperature = it)) },
                enabled = enabled,
                valueRange = GeminiGenerationConfigUiState.MIN_TEMPERATURE..GeminiGenerationConfigUiState.MAX_TEMPERATURE,
                step = 0.05,
                valueText = config.temperature.formatSliderValue(digits = 2),
                description =
                    "Randomness. Slider range: 0.0-2.0; model range/default can vary. " +
                        "0 is most deterministic; higher values make wording more varied.",
                modifier =
                    Modifier
                        .weight(1f)
                        .widthIn(min = 160.dp)
                        .testTag(HomeTags.PARAM_TEMPERATURE),
            )
            DoubleConfigSlider(
                label = "topP",
                value = config.topP,
                onValueChange = { onConfigChanged(config.copy(topP = it)) },
                enabled = enabled,
                valueRange = GeminiGenerationConfigUiState.MIN_TOP_P..GeminiGenerationConfigUiState.MAX_TOP_P,
                step = 0.01,
                valueText = config.topP.formatSliderValue(digits = 2),
                description =
                    "Nucleus sampling. Slider range: 0.0-1.0. " +
                        "Lower narrows the token pool; 1.0 allows the broadest probability mass.",
                modifier =
                    Modifier
                        .weight(1f)
                        .widthIn(min = 160.dp)
                        .testTag(HomeTags.PARAM_TOP_P),
            )
            IntConfigSlider(
                label = "topK",
                value = config.topK,
                onValueChange = { onConfigChanged(config.copy(topK = it)) },
                enabled = enabled,
                valueRange = GeminiGenerationConfigUiState.MIN_TOP_K..GeminiGenerationConfigUiState.MAX_TOP_K,
                steps = GeminiGenerationConfigUiState.MAX_TOP_K - GeminiGenerationConfigUiState.MIN_TOP_K - 1,
                description =
                    "Top-k sampling. Slider range: 1-40; model support/default can vary. " +
                        "Lower values are more deterministic; examples often use 20-40.",
                modifier =
                    Modifier
                        .weight(1f)
                        .widthIn(min = 160.dp)
                        .testTag(HomeTags.PARAM_TOP_K),
            )
            IntConfigSlider(
                label = "candidateCount",
                value = config.candidateCount,
                onValueChange = { onConfigChanged(config.copy(candidateCount = it)) },
                enabled = enabled,
                valueRange = GeminiGenerationConfigUiState.MIN_CANDIDATE_COUNT..GeminiGenerationConfigUiState.MAX_CANDIDATE_COUNT,
                steps = GeminiGenerationConfigUiState.MAX_CANDIDATE_COUNT - GeminiGenerationConfigUiState.MIN_CANDIDATE_COUNT - 1,
                description =
                    "Number of response variants. Slider range: 1-8; multiple candidates are model-dependent " +
                        "and cost output tokens for every candidate. Default is 1.",
                modifier =
                    Modifier
                        .weight(1f)
                        .widthIn(min = 160.dp)
                        .testTag(HomeTags.PARAM_CANDIDATE_COUNT),
            )
            DoubleConfigSlider(
                label = "presencePenalty",
                value = config.presencePenalty ?: 0.0,
                onValueChange = {},
                enabled = false,
                valueRange = GeminiGenerationConfigUiState.MIN_PENALTY..(GeminiGenerationConfigUiState.MAX_PENALTY - 0.05),
                step = 0.05,
                valueText = "not sent",
                description =
                    "Currently not sent for gemini-3.5-flash: API returns \"Penalty is not enabled for this model\". " +
                        "If a future model enables it, app validation is -2.0 <= value < 2.0.",
                modifier =
                    Modifier
                        .weight(1f)
                        .widthIn(min = 160.dp)
                        .testTag(HomeTags.PARAM_PRESENCE_PENALTY),
            )
            DoubleConfigSlider(
                label = "frequencyPenalty",
                value = config.frequencyPenalty ?: 0.0,
                onValueChange = {},
                enabled = false,
                valueRange = GeminiGenerationConfigUiState.MIN_PENALTY..(GeminiGenerationConfigUiState.MAX_PENALTY - 0.05),
                step = 0.05,
                valueText = "not sent",
                description =
                    "Currently not sent for gemini-3.5-flash: API returns \"Penalty is not enabled for this model\". " +
                        "If a future model enables it, app validation is -2.0 <= value < 2.0.",
                modifier =
                    Modifier
                        .weight(1f)
                        .widthIn(min = 160.dp)
                        .testTag(HomeTags.PARAM_FREQUENCY_PENALTY),
            )
        }
        ConfigTextField(
            label = "stopSequences",
            value = config.stopSequences,
            onValueChange = { onConfigChanged(config.copy(stopSequences = it)) },
            enabled = enabled,
            description =
                "Up to 5 newline-separated stop strings. Boundary: empty = none; generation stops at the first match " +
                    "and the stop text is normally omitted. Example: END or ### on separate lines; if the model emits " +
                    "that marker, output stops before it.",
            modifier = Modifier.testTag(HomeTags.PARAM_STOP_SEQUENCES),
            minLines = 2,
            maxLines = 4,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MimeTypeSelector(
    selectedMimeType: String,
    enabled: Boolean,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "responseMimeType",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text =
                "Supported: text/plain, application/json, text/x.enum. Default here: application/json because " +
                    "responseSchema contains an editable example.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GeminiGenerationConfigUiState.RESPONSE_MIME_TYPES.forEach { mimeType ->
                FilterChip(
                    selected = selectedMimeType == mimeType,
                    onClick = { onSelected(mimeType) },
                    enabled = enabled,
                    label = { Text(mimeType) },
                    modifier = Modifier.testTag("${HomeTags.PARAM_RESPONSE_MIME_TYPE}_$mimeType"),
                )
            }
        }
    }
}

@Composable
private fun IntConfigSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    enabled: Boolean,
    valueRange: IntRange,
    description: String,
    modifier: Modifier = Modifier,
    valueText: String = value.toString(),
    steps: Int = 0,
) {
    ConfigSliderLayout(
        label = label,
        valueText = valueText,
        enabled = enabled,
        description = description,
        modifier = modifier,
    ) {
        Slider(
            value = value.coerceIn(valueRange.first, valueRange.last).toFloat(),
            onValueChange = { sliderValue ->
                onValueChange(sliderValue.roundToInt().coerceIn(valueRange.first, valueRange.last))
            },
            enabled = enabled,
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            steps = steps,
        )
    }
}

@Composable
private fun DoubleConfigSlider(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    enabled: Boolean,
    valueRange: ClosedFloatingPointRange<Double>,
    step: Double,
    description: String,
    modifier: Modifier = Modifier,
    valueText: String = value.formatSliderValue(digits = 2),
) {
    ConfigSliderLayout(
        label = label,
        valueText = valueText,
        enabled = enabled,
        description = description,
        modifier = modifier,
    ) {
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive).toFloat(),
            onValueChange = { sliderValue ->
                onValueChange(sliderValue.toDouble().roundToStep(step).coerceIn(valueRange.start, valueRange.endInclusive))
            },
            enabled = enabled,
            valueRange = valueRange.start.toFloat()..valueRange.endInclusive.toFloat(),
            steps = sliderSteps(valueRange = valueRange, step = step),
        )
    }
}

@Composable
private fun ConfigSliderLayout(
    label: String,
    valueText: String,
    enabled: Boolean,
    description: String,
    modifier: Modifier = Modifier,
    slider: @Composable () -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 116.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color =
                    if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        slider()
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Double.roundToStep(step: Double): Double {
    val multiplier = (1.0 / step).roundToInt().coerceAtLeast(1)
    return (this * multiplier).roundToInt() / multiplier.toDouble()
}

private fun Double.formatSliderValue(digits: Int): String = "%.${digits}f".format(Locale.US, this)

private fun sliderSteps(
    valueRange: ClosedFloatingPointRange<Double>,
    step: Double,
): Int {
    val selectableValues = ((valueRange.endInclusive - valueRange.start) / step).roundToInt() + 1
    return (selectableValues - 2).coerceAtLeast(0)
}

@Composable
private fun ConfigTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    description: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    maxLines: Int = 3,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = if (minLines == 1) 96.dp else 132.dp),
        label = { Text(label) },
        supportingText = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        minLines = minLines,
        maxLines = maxLines,
        singleLine = minLines == 1,
        shape = RoundedCornerShape(8.dp),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
    )
}

@Composable
private fun ResultSection(
    comparisonState: HomeComparisonState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(HomeTags.RESULT_AREA),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Response comparison",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isWide = maxWidth >= 640.dp
            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ResponsePane(
                        title = "With user parameters",
                        state = comparisonState.configured,
                        loadingTag = HomeTags.CONFIGURED_LOADING_INDICATOR,
                        modifier =
                            Modifier
                                .weight(1f)
                                .testTag(HomeTags.CONFIGURED_RESULT),
                    )
                    ResponsePane(
                        title = "Without parameters",
                        state = comparisonState.baseline,
                        loadingTag = HomeTags.BASELINE_LOADING_INDICATOR,
                        modifier =
                            Modifier
                                .weight(1f)
                                .testTag(HomeTags.BASELINE_RESULT),
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ResponsePane(
                        title = "With user parameters",
                        state = comparisonState.configured,
                        loadingTag = HomeTags.CONFIGURED_LOADING_INDICATOR,
                        modifier = Modifier.testTag(HomeTags.CONFIGURED_RESULT),
                    )
                    ResponsePane(
                        title = "Without parameters",
                        state = comparisonState.baseline,
                        loadingTag = HomeTags.BASELINE_LOADING_INDICATOR,
                        modifier = Modifier.testTag(HomeTags.BASELINE_RESULT),
                    )
                }
            }
        }
    }
}

@Composable
private fun ResponsePane(
    title: String,
    state: ResponsePaneState,
    loadingTag: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopStart,
    ) {
        when (state) {
            is ResponsePaneState.Empty ->
                ResponsePanel(
                    title = title,
                    body = state.message,
                )
            ResponsePaneState.Loading -> LoadingPane(title = title, loadingTag = loadingTag)
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

@Composable
private fun LoadingPane(
    title: String,
    loadingTag: String,
) {
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
}

object HomeTags {
    const val PROMPT_INPUT = "home_prompt_input"
    const val AGENT_CHAT_BUTTON = "home_agent_chat_button"
    const val CONTEXT_AGENT_BUTTON = "home_context_agent_button"
    const val PROMPT_LAB_BUTTON = "home_prompt_lab_button"
    const val TEMPERATURE_LAB_BUTTON = "home_temperature_lab_button"
    const val HUGGINGFACE_LAB_BUTTON = "home_huggingface_lab_button"
    const val SEND_BUTTON = "home_send_button"
    const val RESULT_AREA = "home_result_area"
    const val CONFIGURED_RESULT = "home_configured_result"
    const val BASELINE_RESULT = "home_baseline_result"
    const val CONFIGURED_LOADING_INDICATOR = "home_configured_loading_indicator"
    const val BASELINE_LOADING_INDICATOR = "home_baseline_loading_indicator"
    const val PARAM_RESPONSE_MIME_TYPE = "home_param_response_mime_type"
    const val PARAM_RESPONSE_SCHEMA = "home_param_response_schema"
    const val PARAM_MAX_OUTPUT_TOKENS = "home_param_max_output_tokens"
    const val PARAM_STOP_SEQUENCES = "home_param_stop_sequences"
    const val PARAM_TEMPERATURE = "home_param_temperature"
    const val PARAM_TOP_P = "home_param_top_p"
    const val PARAM_TOP_K = "home_param_top_k"
    const val PARAM_CANDIDATE_COUNT = "home_param_candidate_count"
    const val PARAM_PRESENCE_PENALTY = "home_param_presence_penalty"
    const val PARAM_FREQUENCY_PENALTY = "home_param_frequency_penalty"
}

@Preview(showBackground = true, widthDp = 390, heightDp = 900)
@Composable
fun HomeScreenIdlePreview() {
    AIChallengeTheme(dynamicColor = false) {
        HomeScreen(
            state = HomeUiState(),
            onAction = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 900)
@Composable
fun HomeScreenLoadingPreview() {
    AIChallengeTheme(dynamicColor = false) {
        HomeScreen(
            state =
                HomeUiState(
                    prompt = "Summarize Android edge-to-edge layout.",
                    comparisonState = HomeComparisonState.loading(),
                ),
            onAction = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 1100, heightDp = 720)
@Composable
fun HomeScreenSuccessWidePreview() {
    AIChallengeTheme(dynamicColor = false) {
        HomeScreen(
            state =
                HomeUiState(
                    prompt = "Explain unidirectional data flow.",
                    comparisonState =
                        HomeComparisonState(
                            configured = ResponsePaneState.Success("Short JSON-focused answer with constrained length."),
                            baseline =
                                ResponsePaneState.Success(
                                    "State flows down, events flow up, and the ViewModel owns state transitions.",
                                ),
                        ),
                ),
            onAction = {},
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
fun HomeScreenErrorDarkPreview() {
    AIChallengeTheme(dynamicColor = false) {
        HomeScreen(
            state =
                HomeUiState(
                    prompt = "Hello",
                    comparisonState =
                        HomeComparisonState(
                            configured =
                                ResponsePaneState.Error(
                                    "responseSchema requires application/json or text/x.enum responseMimeType.",
                                ),
                            baseline = ResponsePaneState.Success("Baseline answer without additional generationConfig."),
                        ),
                ),
            onAction = {},
        )
    }
}
