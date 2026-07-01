package com.akhavanskii.aichallenge.feature.ragindexing

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.akhavanskii.aichallenge.core.designsystem.AIChallengeTheme
import com.akhavanskii.aichallenge.core.designsystem.ChallengeButton
import com.akhavanskii.aichallenge.core.designsystem.ChallengeMarkdownBody
import com.akhavanskii.aichallenge.feature.common.ResponsePaneState
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun RagIndexingScreen(
    state: RagIndexingUiState,
    onAction: (RagIndexingAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .testTag(RagIndexingTags.SCREEN),
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
            RagIndexingHeader(onBack = onBack)
            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    RagIndexingControls(
                        state = state,
                        onAction = onAction,
                        modifier = Modifier.weight(0.9f),
                    )
                    RagIndexingOutputs(
                        state = state,
                        modifier = Modifier.weight(1.2f),
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    RagIndexingControls(
                        state = state,
                        onAction = onAction,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    RagIndexingOutputs(
                        state = state,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun RagIndexingHeader(onBack: () -> Unit) {
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
                text = "RAG Indexing",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Build local chunk indexes, answer with or without RAG, and compare quality.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onBack,
            modifier = Modifier.testTag(RagIndexingTags.BACK_BUTTON),
        ) {
            Text("Back")
        }
    }
}

@Composable
private fun RagIndexingControls(
    state: RagIndexingUiState,
    onAction: (RagIndexingAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CorpusSelector(
            documents = state.corpusDocuments,
            selectedDocumentIds = state.selectedCorpusDocumentIds,
            enabled = !state.isBusy,
            onDocumentToggled = { documentId, selected ->
                onAction(RagIndexingAction.CorpusDocumentToggled(documentId = documentId, selected = selected))
            },
        )
        RagTextField(
            value = state.endpoint,
            onValueChange = { onAction(RagIndexingAction.EndpointChanged(it)) },
            enabled = !state.isBusy,
            label = "Endpoint",
            placeholder = OllamaEmbeddingClient.DEFAULT_ENDPOINT,
            modifier = Modifier.testTag(RagIndexingTags.ENDPOINT_INPUT),
        )
        RagTextField(
            value = state.model,
            onValueChange = { onAction(RagIndexingAction.ModelChanged(it)) },
            enabled = !state.isBusy,
            label = "Model",
            placeholder = OllamaEmbeddingClient.DEFAULT_MODEL,
            modifier = Modifier.testTag(RagIndexingTags.MODEL_INPUT),
        )
        StrategySelector(
            selectedStrategy = state.selectedStrategy,
            enabled = !state.isBusy,
            onSelected = { onAction(RagIndexingAction.StrategyChanged(it)) },
        )
        LlmModelSelector(
            selectedModel = state.selectedLlmModel,
            enabled = !state.isBusy,
            onSelected = { onAction(RagIndexingAction.LlmModelChanged(it)) },
        )
        RagTextField(
            value = state.query,
            onValueChange = { onAction(RagIndexingAction.QueryChanged(it)) },
            enabled = !state.isBusy,
            label = "Control question",
            placeholder = "Enter your own question for RAG comparison.",
            minLines = 3,
            modifier =
                Modifier
                    .heightIn(min = 112.dp)
                    .testTag(RagIndexingTags.QUERY_INPUT),
        )
        EvaluationExpectationsSection(
            expectedAnswer = state.expectedAnswer,
            expectedSources = state.expectedSources,
            enabled = !state.isBusy,
            onExpectedAnswerChanged = { onAction(RagIndexingAction.ExpectedAnswerChanged(it)) },
            onExpectedSourcesChanged = { onAction(RagIndexingAction.ExpectedSourcesChanged(it)) },
        )
        RetrievalControls(
            topKBeforeFilter = state.topKBeforeFilter,
            topKAfterFilter = state.topKAfterFilter,
            similarityThreshold = state.similarityThreshold,
            enabled = !state.isBusy,
            onTopKBeforeChanged = { onAction(RagIndexingAction.TopKBeforeFilterChanged(it)) },
            onTopKAfterChanged = { onAction(RagIndexingAction.TopKAfterFilterChanged(it)) },
            onSimilarityThresholdChanged = { onAction(RagIndexingAction.SimilarityThresholdChanged(it)) },
        )
        RagActionButtons(
            state = state,
            onAction = onAction,
        )
    }
}

@Composable
private fun EvaluationExpectationsSection(
    expectedAnswer: String,
    expectedSources: String,
    enabled: Boolean,
    onExpectedAnswerChanged: (String) -> Unit,
    onExpectedSourcesChanged: (String) -> Unit,
) {
    val expanded = rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = { expanded.value = !expanded.value },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(RagIndexingTags.EXPECTATIONS_TOGGLE),
        ) {
            Text(if (expanded.value) "Hide expected answer/sources" else "Show expected answer/sources")
        }
        if (expanded.value) {
            RagTextField(
                value = expectedAnswer,
                onValueChange = onExpectedAnswerChanged,
                enabled = enabled,
                label = "Expected answer",
                placeholder = "Facts that should be present in the answer.",
                minLines = 3,
                modifier =
                    Modifier
                        .heightIn(min = 112.dp)
                        .testTag(RagIndexingTags.EXPECTED_ANSWER_INPUT),
            )
            RagTextField(
                value = expectedSources,
                onValueChange = onExpectedSourcesChanged,
                enabled = enabled,
                label = "Expected sources",
                placeholder = "Document names or sections expected in RAG context.",
                minLines = 2,
                modifier =
                    Modifier
                        .heightIn(min = 88.dp)
                        .testTag(RagIndexingTags.EXPECTED_SOURCES_INPUT),
            )
        }
    }
}

@Composable
private fun CorpusSelector(
    documents: List<RagCorpusDocumentUi>,
    selectedDocumentIds: Set<String>,
    enabled: Boolean,
    onDocumentToggled: (String, Boolean) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(RagIndexingTags.CORPUS_SELECTOR),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Corpus",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (documents.isEmpty()) {
            Text(
                text = "Loading corpus documents.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            documents.forEach { document ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("${RagIndexingTags.CORPUS_DOCUMENT_PREFIX}_${document.id}"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Checkbox(
                        checked = document.id in selectedDocumentIds,
                        onCheckedChange = { checked -> onDocumentToggled(document.id, checked) },
                        enabled = enabled,
                        modifier = Modifier.testTag("${RagIndexingTags.CORPUS_CHECKBOX_PREFIX}_${document.id}"),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = document.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${document.source} - ${document.wordCount} words",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LlmModelSelector(
    selectedModel: RagLlmModelOption,
    enabled: Boolean,
    onSelected: (RagLlmModelOption) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "LLM model",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        val expanded = remember { mutableStateOf(false) }
        Box(
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = { expanded.value = true },
                enabled = enabled,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(RagIndexingTags.LLM_MODEL_SELECTOR),
            ) {
                Text(
                    text = "${selectedModel.title} - ${selectedModel.provider.displayName}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(
                expanded = expanded.value,
                onDismissRequest = { expanded.value = false },
                modifier = Modifier.fillMaxWidth(),
            ) {
                RagLlmModelOption.entries.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "${model.title} - ${model.provider.displayName}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            expanded.value = false
                            onSelected(model)
                        },
                        modifier = Modifier.testTag("${RagIndexingTags.LLM_MODEL_PREFIX}_${model.name}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun RagTextField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        minLines = minLines,
        singleLine = minLines == 1,
        shape = RoundedCornerShape(8.dp),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StrategySelector(
    selectedStrategy: RagIndexingStrategy,
    enabled: Boolean,
    onSelected: (RagIndexingStrategy) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Strategy",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RagIndexingStrategy.entries.forEach { strategy ->
                FilterChip(
                    selected = strategy == selectedStrategy,
                    onClick = { onSelected(strategy) },
                    enabled = enabled,
                    label = { Text(strategy.displayName) },
                    modifier = Modifier.testTag("${RagIndexingTags.STRATEGY_PREFIX}_${strategy.name}"),
                )
            }
        }
    }
}

@Composable
private fun RetrievalControls(
    topKBeforeFilter: Int,
    topKAfterFilter: Int,
    similarityThreshold: Double,
    enabled: Boolean,
    onTopKBeforeChanged: (Int) -> Unit,
    onTopKAfterChanged: (Int) -> Unit,
    onSimilarityThresholdChanged: (Double) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Retrieval filter",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        RetrievalSlider(
            label = "Top K before filter",
            valueText = topKBeforeFilter.toString(),
            value = topKBeforeFilter.toFloat(),
            onValueChange = { onTopKBeforeChanged(it.roundToInt()) },
            enabled = enabled,
            valueRange = RagIndexingUiState.MIN_TOP_K.toFloat()..RagIndexingUiState.MAX_TOP_K.toFloat(),
            steps = RagIndexingUiState.MAX_TOP_K - RagIndexingUiState.MIN_TOP_K - 1,
            modifier = Modifier.testTag(RagIndexingTags.TOP_K_BEFORE_FILTER_SLIDER),
        )
        RetrievalSlider(
            label = "Top K after filter",
            valueText = topKAfterFilter.toString(),
            value = topKAfterFilter.toFloat(),
            onValueChange = { onTopKAfterChanged(it.roundToInt()) },
            enabled = enabled,
            valueRange = RagIndexingUiState.MIN_TOP_K.toFloat()..RagIndexingUiState.MAX_TOP_K.toFloat(),
            steps = RagIndexingUiState.MAX_TOP_K - RagIndexingUiState.MIN_TOP_K - 1,
            modifier = Modifier.testTag(RagIndexingTags.TOP_K_AFTER_FILTER_SLIDER),
        )
        RetrievalSlider(
            label = "Similarity threshold",
            valueText = similarityThreshold.format2(),
            value = similarityThreshold.toFloat(),
            onValueChange = { value -> onSimilarityThresholdChanged((value * 20f).roundToInt() / 20.0) },
            enabled = enabled,
            valueRange = RagIndexingUiState.MIN_SIMILARITY_THRESHOLD.toFloat()..RagIndexingUiState.MAX_SIMILARITY_THRESHOLD.toFloat(),
            steps = 19,
            modifier = Modifier.testTag(RagIndexingTags.SIMILARITY_THRESHOLD_SLIDER),
        )
    }
}

@Composable
private fun RetrievalSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    enabled: Boolean,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "$label: $valueText",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RagActionButtons(
    state: RagIndexingUiState,
    onAction: (RagIndexingAction) -> Unit,
) {
    val hasCorpusSelection = state.corpusDocuments.isEmpty() || state.selectedCorpusDocumentIds.isNotEmpty()
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ChallengeButton(
            onClick = { onAction(RagIndexingAction.BuildIndex) },
            enabled = !state.isBusy && hasCorpusSelection,
            modifier =
                Modifier
                    .widthIn(min = 136.dp)
                    .testTag(RagIndexingTags.BUILD_BUTTON),
        ) {
            Text("Build Index")
        }
        ChallengeButton(
            onClick = { onAction(RagIndexingAction.CompareStrategies) },
            enabled = !state.isBusy && state.query.isNotBlank() && hasCorpusSelection,
            modifier =
                Modifier
                    .widthIn(min = 172.dp)
                    .testTag(RagIndexingTags.COMPARE_BUTTON),
        ) {
            Text("Compare Strategies")
        }
        ChallengeButton(
            onClick = { onAction(RagIndexingAction.Search) },
            enabled = !state.isBusy && state.query.isNotBlank() && hasCorpusSelection,
            modifier =
                Modifier
                    .widthIn(min = 112.dp)
                    .testTag(RagIndexingTags.SEARCH_BUTTON),
        ) {
            Text("Search")
        }
        ChallengeButton(
            onClick = { onAction(RagIndexingAction.CompareModes) },
            enabled = !state.isBusy && state.query.isNotBlank() && hasCorpusSelection,
            modifier =
                Modifier
                    .widthIn(min = 156.dp)
                    .testTag(RagIndexingTags.COMPARE_MODES_BUTTON),
        ) {
            Text("Compare Modes")
        }
        OutlinedButton(
            onClick = { onAction(RagIndexingAction.Cancel) },
            enabled = state.isBusy,
            modifier =
                Modifier
                    .widthIn(min = 104.dp)
                    .testTag(RagIndexingTags.CANCEL_BUTTON),
        ) {
            Text("Cancel")
        }
    }
}

@Composable
private fun RagIndexingOutputs(
    state: RagIndexingUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(RagIndexingTags.OUTPUTS),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RagStatus(state = state)
        state.userFacingError?.let { error ->
            ErrorMessage(message = error)
        }
        RagAgentSection(state = state)
        OutputPaths(paths = state.progress.outputPaths)
        IndexSummaries(summaries = state.indexSummaries)
        ComparisonSection(
            summary = state.comparisonSummary,
            report = state.comparisonReport,
        )
        SearchResults(
            results = state.searchResults,
            stats = state.searchRetrievalStats,
        )
    }
}

@Composable
private fun RagStatus(state: RagIndexingUiState) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(RagIndexingTags.STATUS),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.isBusy) {
                CircularProgressIndicator(
                    modifier =
                        Modifier
                            .size(18.dp)
                            .testTag(RagIndexingTags.PROGRESS_INDICATOR),
                    strokeWidth = 2.dp,
                )
            }
            Text(
                text = "Status: ${state.phase.displayName()}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = "Embedded ${state.progress.embedded}/${state.progress.total}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(RagIndexingTags.PROGRESS),
        )
        Text(
            text = "Cached embeddings: ${state.progress.cachedCount}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(RagIndexingTags.CACHED_COUNT),
        )
        state.progress.currentStrategy?.let { strategy ->
            Text(
                text = "Current strategy: ${strategy.displayName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorMessage(message: String) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(RagIndexingTags.ERROR),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HorizontalDivider()
        Text(
            text = "Error",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun RagAgentSection(state: RagIndexingUiState) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(RagIndexingTags.AGENT_SECTION),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider()
        Text(
            text = "RAG agent",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        AgentResponsePane(
            title = "Answer without RAG",
            state = state.noRagAnswerState,
            modifier = Modifier.testTag(RagIndexingTags.NO_RAG_ANSWER),
        )
        AgentResponsePane(
            title = "Baseline RAG answer",
            state = state.baselineRagAnswerState,
            modifier = Modifier.testTag(RagIndexingTags.BASELINE_RAG_ANSWER),
        )
        RetrievedContext(
            title = "Baseline context",
            results = state.baselineRagContextResults,
            stats = state.baselineRetrievalStats,
            emptyMessage = "No baseline context yet.",
            resultTagPrefix = RagIndexingTags.BASELINE_CONTEXT_RESULT_PREFIX,
            modifier = Modifier.testTag(RagIndexingTags.BASELINE_CONTEXT),
        )
        QueryRewriteSummary(
            rewrittenQuery = state.rewrittenQuery,
            note = state.queryRewriteNote,
        )
        AgentResponsePane(
            title = "Improved RAG answer",
            state = state.improvedRagAnswerState,
            modifier = Modifier.testTag(RagIndexingTags.IMPROVED_RAG_ANSWER),
        )
        RetrievedContext(
            title = "Improved context after filter",
            results = state.improvedRagContextResults,
            stats = state.improvedRetrievalStats,
            emptyMessage = "No improved context yet.",
            resultTagPrefix = RagIndexingTags.IMPROVED_CONTEXT_RESULT_PREFIX,
            modifier = Modifier.testTag(RagIndexingTags.IMPROVED_CONTEXT),
        )
        AgentResponsePane(
            title = "Quality comparison",
            state = state.qualityEvaluationState,
            modifier = Modifier.testTag(RagIndexingTags.QUALITY_EVALUATION),
        )
    }
}

@Composable
private fun AgentResponsePane(
    title: String,
    state: ResponsePaneState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        when (state) {
            is ResponsePaneState.Empty ->
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            ResponsePaneState.Loading ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "Waiting for LLM.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            is ResponsePaneState.Success ->
                SelectionContainer {
                    ChallengeMarkdownBody(
                        body = state.response,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            is ResponsePaneState.Error ->
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
        }
    }
}

@Composable
private fun QueryRewriteSummary(
    rewrittenQuery: String?,
    note: String?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(RagIndexingTags.QUERY_REWRITE),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Query rewrite",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (rewrittenQuery.isNullOrBlank()) {
            Text(
                text = "No rewritten query yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LabeledValue(label = "rewritten_query", value = rewrittenQuery)
        }
        note?.takeIf { it.isNotBlank() }?.let { value ->
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RetrievedContext(
    title: String,
    results: List<RagSearchResultUi>,
    stats: RagRetrievalStatsUi?,
    emptyMessage: String,
    resultTagPrefix: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        stats?.let { retrievalStats ->
            RetrievalStatsLine(stats = retrievalStats)
        }
        if (results.isEmpty()) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            results.forEachIndexed { index, result ->
                SearchResultDetails(
                    result = result,
                    modifier = Modifier.testTag("${resultTagPrefix}_$index"),
                )
            }
        }
    }
}

@Composable
private fun RetrievalStatsLine(stats: RagRetrievalStatsUi) {
    val threshold =
        stats.similarityThreshold?.let { value -> ", threshold=${value.format2()}" }.orEmpty()
    Text(
        text =
            "candidates=${stats.candidateCount} -> filtered=${stats.filteredCount} -> used=${stats.usedCount} " +
                "(before=${stats.topKBeforeFilter}, after=${stats.topKAfterFilter}$threshold)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun OutputPaths(paths: RagIndexingOutputPaths) {
    val items = paths.items()
    val fullPathsVisible = remember { mutableStateOf(false) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(RagIndexingTags.OUTPUT_PATHS),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider()
        Text(
            text = "Generated files",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (items.isEmpty()) {
            Text(
                text = "Files will appear after build or compare.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEachIndexed { index, item ->
                    Text(
                        text = item.compactPath(),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("${RagIndexingTags.OUTPUT_PATH_PREFIX}_$index"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(
                onClick = { fullPathsVisible.value = !fullPathsVisible.value },
                modifier = Modifier.testTag(RagIndexingTags.OUTPUT_PATHS_TOGGLE),
            ) {
                Text(if (fullPathsVisible.value) "Hide full paths" else "Show full paths")
            }
            if (fullPathsVisible.value) {
                SelectionContainer {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(RagIndexingTags.OUTPUT_PATHS_FULL),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items.forEach { (label, path) ->
                            LabeledValue(
                                label = label,
                                value = path,
                                monospace = true,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun Pair<String, String>.compactPath(): String {
    val (label, path) = this
    val normalizedPath = path.replace('\\', '/')
    val ragIndexStart = normalizedPath.indexOf(RAG_INDEX_PATH_PREFIX)
    return if (ragIndexStart >= 0) {
        normalizedPath.substring(ragIndexStart)
    } else {
        "$RAG_INDEX_PATH_PREFIX$label"
    }
}

@Composable
private fun IndexSummaries(summaries: List<RagIndexSummary>) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(RagIndexingTags.INDEX_SUMMARIES),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider()
        Text(
            text = "Index summaries",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (summaries.isEmpty()) {
            Text(
                text = "No built indexes yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            summaries.forEach { summary ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = summary.strategy.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text =
                            "chunks=${summary.chunkCount}, embeddings=${summary.embeddingCount}, " +
                                "documents=${summary.documentCount}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LabeledValue(label = "source_hash", value = summary.sourceHash, monospace = true)
                    LabeledValue(label = "path", value = summary.outputPath, monospace = true)
                }
            }
        }
    }
}

@Composable
private fun ComparisonSection(
    summary: RagComparisonSummary?,
    report: RagComparisonReport?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(RagIndexingTags.COMPARISON),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider()
        Text(
            text = "Comparison",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (summary == null) {
            Text(
                text = "No comparison yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text =
                    "queries=${summary.queryCount}, fixed_chunks=${summary.fixedChunkCount}, " +
                        "structure_chunks=${summary.structureChunkCount}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text =
                    "avg_tokens fixed=${summary.fixedAverageTokens.format1()}, " +
                        "structure=${summary.structureAverageTokens.format1()}",
                style = MaterialTheme.typography.bodyMedium,
            )
            report?.let { value ->
                Text(
                    text =
                        "filter before=${value.settings.topKBeforeFilter}, " +
                            "after=${value.settings.topKAfterFilter}, " +
                            "threshold=${value.settings.similarityThreshold.format2()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LabeledValue(label = "comparison.json", value = summary.jsonPath, monospace = true)
            LabeledValue(label = "comparison.md", value = summary.markdownPath, monospace = true)
            report?.queries?.forEachIndexed { index, query ->
                ComparisonQuery(
                    query = query,
                    modifier = Modifier.testTag("${RagIndexingTags.COMPARISON_QUERY_PREFIX}_$index"),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComparisonQuery(
    query: RagComparisonQueryReport,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = query.originalQuery,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        query.rewrittenQuery?.takeIf { value -> value.isNotBlank() }?.let { rewritten ->
            LabeledValue(label = "rewritten_query", value = rewritten)
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 2,
        ) {
            ComparisonRetrieval(
                title = "Fixed",
                report = query.fixed,
                modifier =
                    Modifier
                        .weight(1f)
                        .widthIn(min = 240.dp),
            )
            ComparisonRetrieval(
                title = "Structure",
                report = query.structure,
                modifier =
                    Modifier
                        .weight(1f)
                        .widthIn(min = 240.dp),
            )
        }
    }
}

@Composable
private fun ComparisonRetrieval(
    title: String,
    report: RagComparisonRetrievalReport,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text =
                "baseline=${report.baselineHits.size}, " +
                    "candidates=${report.improvedCandidates.size}, " +
                    "filtered=${report.filteredHits.size}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
        ComparisonHits(title = "Baseline", hits = report.baselineHits)
        ComparisonHits(title = "Filtered", hits = report.filteredHits)
    }
}

@Composable
private fun ComparisonHits(
    title: String,
    hits: List<RagComparisonHit>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hits.isEmpty()) {
            Text(
                text = "No hits.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            hits.forEach { hit ->
                Text(
                    text = "${hit.score.format4()} ${hit.chunkId} ${hit.title}${hit.sectionSuffix()}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = hit.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SearchResults(
    results: List<RagSearchResultUi>,
    stats: RagRetrievalStatsUi?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(RagIndexingTags.SEARCH_RESULTS),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider()
        Text(
            text = "Search results",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (results.isEmpty()) {
            Text(
                text = "No search results yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            stats?.let { retrievalStats ->
                RetrievalStatsLine(stats = retrievalStats)
            }
            SearchScoreChart(results = results)
            Text(
                text = "Result details",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            results.forEachIndexed { index, result ->
                SearchResultDetails(
                    result = result,
                    modifier = Modifier.testTag("${RagIndexingTags.SEARCH_RESULT_DETAILS_PREFIX}_$index"),
                )
            }
        }
    }
}

@Composable
private fun SearchScoreChart(results: List<RagSearchResultUi>) {
    val bestScore = results.maxOfOrNull { result -> result.score.coerceAtLeast(0.0) }?.takeIf { it > 0.0 } ?: 1.0
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(RagIndexingTags.SEARCH_SCORE_CHART),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        results.forEachIndexed { index, result ->
            SearchScoreBar(
                rank = index + 1,
                result = result,
                bestScore = bestScore,
                modifier = Modifier.testTag("${RagIndexingTags.SEARCH_SCORE_BAR_PREFIX}_$index"),
            )
        }
    }
}

@Composable
private fun SearchScoreBar(
    rank: Int,
    result: RagSearchResultUi,
    bestScore: Double,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "#$rank",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = result.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = result.score.format4(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        result.section?.takeIf { it.isNotBlank() }?.let { section ->
            Text(
                text = section,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ScoreBar(score = result.score, bestScore = bestScore)
    }
}

@Composable
private fun ScoreBar(
    score: Double,
    bestScore: Double,
) {
    val normalized =
        if (score > 0.0 && bestScore > 0.0) {
            (score / bestScore).coerceIn(MIN_VISIBLE_SCORE_BAR_FRACTION, 1.0)
        } else {
            MIN_VISIBLE_SCORE_BAR_FRACTION
        }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(normalized.toFloat())
                    .height(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun SearchResultDetails(
    result: RagSearchResultUi,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = result.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = result.score.format4(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        LabeledValue(label = "chunk_id", value = result.chunkId, monospace = true)
        result.section?.takeIf { it.isNotBlank() }?.let { section ->
            LabeledValue(label = "section", value = section)
        }
        LabeledValue(label = "source", value = result.source)
        Text(
            text = result.preview,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LabeledValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = "$label: $value",
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun RagIndexingPhase.displayName(): String =
    when (this) {
        RagIndexingPhase.IDLE -> "Idle"
        RagIndexingPhase.LOADING_CORPUS -> "Loading corpus"
        RagIndexingPhase.BUILDING -> "Building"
        RagIndexingPhase.COMPARING -> "Comparing"
        RagIndexingPhase.SEARCHING -> "Searching"
        RagIndexingPhase.ANSWERING -> "Answering"
        RagIndexingPhase.EVALUATING -> "Evaluating"
        RagIndexingPhase.SUCCESS -> "Success"
        RagIndexingPhase.ERROR -> "Error"
        RagIndexingPhase.CANCELLED -> "Cancelled"
    }

private fun RagIndexingOutputPaths.items(): List<Pair<String, String>> =
    buildList {
        fixedIndex?.let { add("fixed/index.json" to it) }
        structureIndex?.let { add("structure/index.json" to it) }
        embeddingCache?.let { add("cache/embeddings.json" to it) }
        comparisonJson?.let { add("comparison.json" to it) }
        comparisonMarkdown?.let { add("comparison.md" to it) }
    }

private fun RagComparisonHit.sectionSuffix(): String = section?.takeIf { it.isNotBlank() }?.let { " / $it" }.orEmpty()

private fun Double.format1(): String = String.format(Locale.US, "%.1f", this)

private fun Double.format2(): String = String.format(Locale.US, "%.2f", this)

private fun Double.format4(): String = String.format(Locale.US, "%.4f", this)

private const val MIN_VISIBLE_SCORE_BAR_FRACTION = 0.06
private const val RAG_INDEX_PATH_PREFIX = "rag-index/"

object RagIndexingTags {
    const val SCREEN = "rag_indexing_screen"
    const val BACK_BUTTON = "rag_indexing_back_button"
    const val CORPUS_SELECTOR = "rag_indexing_corpus_selector"
    const val CORPUS_DOCUMENT_PREFIX = "rag_indexing_corpus_document"
    const val CORPUS_CHECKBOX_PREFIX = "rag_indexing_corpus_checkbox"
    const val ENDPOINT_INPUT = "rag_indexing_endpoint_input"
    const val MODEL_INPUT = "rag_indexing_model_input"
    const val STRATEGY_PREFIX = "rag_indexing_strategy"
    const val LLM_MODEL_SELECTOR = "rag_indexing_llm_model_selector"
    const val LLM_MODEL_PREFIX = "rag_indexing_llm_model"
    const val QUERY_INPUT = "rag_indexing_query_input"
    const val EXPECTATIONS_TOGGLE = "rag_indexing_expectations_toggle"
    const val EXPECTED_ANSWER_INPUT = "rag_indexing_expected_answer_input"
    const val EXPECTED_SOURCES_INPUT = "rag_indexing_expected_sources_input"
    const val TOP_K_BEFORE_FILTER_SLIDER = "rag_indexing_top_k_before_filter_slider"
    const val TOP_K_AFTER_FILTER_SLIDER = "rag_indexing_top_k_after_filter_slider"
    const val SIMILARITY_THRESHOLD_SLIDER = "rag_indexing_similarity_threshold_slider"
    const val BUILD_BUTTON = "rag_indexing_build_button"
    const val COMPARE_BUTTON = "rag_indexing_compare_button"
    const val SEARCH_BUTTON = "rag_indexing_search_button"
    const val COMPARE_MODES_BUTTON = "rag_indexing_compare_modes_button"
    const val CANCEL_BUTTON = "rag_indexing_cancel_button"
    const val OUTPUTS = "rag_indexing_outputs"
    const val STATUS = "rag_indexing_status"
    const val PROGRESS = "rag_indexing_progress"
    const val PROGRESS_INDICATOR = "rag_indexing_progress_indicator"
    const val CACHED_COUNT = "rag_indexing_cached_count"
    const val ERROR = "rag_indexing_error"
    const val OUTPUT_PATHS = "rag_indexing_output_paths"
    const val OUTPUT_PATHS_TOGGLE = "rag_indexing_output_paths_toggle"
    const val OUTPUT_PATHS_FULL = "rag_indexing_output_paths_full"
    const val OUTPUT_PATH_PREFIX = "rag_indexing_output_path"
    const val INDEX_SUMMARIES = "rag_indexing_index_summaries"
    const val COMPARISON = "rag_indexing_comparison"
    const val COMPARISON_QUERY_PREFIX = "rag_indexing_comparison_query"
    const val AGENT_SECTION = "rag_indexing_agent_section"
    const val NO_RAG_ANSWER = "rag_indexing_no_rag_answer"
    const val BASELINE_RAG_ANSWER = "rag_indexing_baseline_rag_answer"
    const val IMPROVED_RAG_ANSWER = "rag_indexing_improved_rag_answer"
    const val QUALITY_EVALUATION = "rag_indexing_quality_evaluation"
    const val QUERY_REWRITE = "rag_indexing_query_rewrite"
    const val BASELINE_CONTEXT = "rag_indexing_baseline_context"
    const val BASELINE_CONTEXT_RESULT_PREFIX = "rag_indexing_baseline_context_result"
    const val IMPROVED_CONTEXT = "rag_indexing_improved_context"
    const val IMPROVED_CONTEXT_RESULT_PREFIX = "rag_indexing_improved_context_result"
    const val SEARCH_RESULTS = "rag_indexing_search_results"
    const val SEARCH_SCORE_CHART = "rag_indexing_search_score_chart"
    const val SEARCH_SCORE_BAR_PREFIX = "rag_indexing_search_score_bar"
    const val SEARCH_RESULT_DETAILS_PREFIX = "rag_indexing_search_result_details"
}

@Preview(showBackground = true, widthDp = 390, heightDp = 900)
@Composable
private fun RagIndexingScreenIdlePreview() {
    AIChallengeTheme(dynamicColor = false) {
        RagIndexingScreen(
            state = RagIndexingUiState(),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 960, heightDp = 780, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RagIndexingScreenResultsPreview() {
    AIChallengeTheme(dynamicColor = false) {
        RagIndexingScreen(
            state =
                RagIndexingUiState(
                    query = "How does emulator reach host services?",
                    expectedAnswer = "The Android emulator should use 10.0.2.2 to reach host services.",
                    expectedSources = "rag_course_2026_06_29.md",
                    corpusDocuments = sampleCorpusDocuments(),
                    selectedCorpusDocumentIds = setOf("rag_course_2026_06_29"),
                    phase = RagIndexingPhase.SUCCESS,
                    progress =
                        RagIndexingProgress(
                            embedded = 42,
                            total = 42,
                            cachedCount = 18,
                            outputPaths =
                                RagIndexingOutputPaths(
                                    fixedIndex = "/data/user/0/app/files/rag-index/fixed/index.json",
                                    structureIndex = "/data/user/0/app/files/rag-index/structure/index.json",
                                    embeddingCache = "/data/user/0/app/files/rag-index/cache/embeddings.json",
                                    comparisonJson = "/data/user/0/app/files/rag-index/comparison.json",
                                    comparisonMarkdown = "/data/user/0/app/files/rag-index/comparison.md",
                                ),
                        ),
                    indexSummaries =
                        listOf(
                            RagIndexSummary(
                                strategy = RagIndexingStrategy.FIXED,
                                chunkCount = 24,
                                embeddingCount = 24,
                                documentCount = 2,
                                sourceHash = "abc123",
                                outputPath = "/data/user/0/app/files/rag-index/fixed/index.json",
                            ),
                            RagIndexSummary(
                                strategy = RagIndexingStrategy.STRUCTURE,
                                chunkCount = 18,
                                embeddingCount = 18,
                                documentCount = 2,
                                sourceHash = "abc123",
                                outputPath = "/data/user/0/app/files/rag-index/structure/index.json",
                            ),
                        ),
                    comparisonSummary =
                        RagComparisonSummary(
                            queryCount = 1,
                            fixedChunkCount = 24,
                            structureChunkCount = 18,
                            fixedAverageTokens = 156.4,
                            structureAverageTokens = 211.2,
                            jsonPath = "/data/user/0/app/files/rag-index/comparison.json",
                            markdownPath = "/data/user/0/app/files/rag-index/comparison.md",
                        ),
                    comparisonReport = sampleComparisonReport(),
                    searchResults = sampleSearchResults(),
                    searchRetrievalStats = sampleRetrievalStats(),
                    baselineRagContextResults = sampleSearchResults().take(2),
                    improvedRagCandidateResults = sampleSearchResults(),
                    improvedRagContextResults = sampleSearchResults().take(2),
                    baselineRetrievalStats = sampleRetrievalStats(similarityThreshold = null),
                    improvedRetrievalStats = sampleRetrievalStats(),
                    rewrittenQuery = "Android emulator host service endpoint",
                    queryRewriteNote = "Query rewrite applied.",
                    noRagAnswerState = ResponsePaneState.Success("Use localhost from the emulator."),
                    baselineRagAnswerState =
                        ResponsePaneState.Success(
                            "Use `10.0.2.2` from the Android emulator to reach host services. Source: [S1] `fixed_0001`.",
                        ),
                    improvedRagAnswerState =
                        ResponsePaneState.Success(
                            "Use `10.0.2.2` from the Android emulator to reach services running on the host machine. Source: [S1] `fixed_0001`.",
                        ),
                    qualityEvaluationState =
                        ResponsePaneState.Success(
                            "| mode | accuracy | source_grounding |\n|---|---:|---:|\n| WITHOUT_RAG | 2 | 1 |\n| BASELINE_RAG | 4 | 4 |\n| IMPROVED_RAG | 5 | 5 |\n\nbaseline_vs_improved: IMPROVED_RAG.",
                        ),
                ),
            onAction = {},
            onBack = {},
        )
    }
}

private fun sampleCorpusDocuments(): List<RagCorpusDocumentUi> =
    listOf(
        RagCorpusDocumentUi(
            id = "rag_course_2026_06_29",
            title = "RAG: Эмбеддинги, векторный поиск и чанкинг (Неделя 2)",
            source = "assets/rag/rag_course_2026_06_29.md",
            wordCount = 3_200,
            selectedByDefault = true,
        ),
        RagCorpusDocumentUi(
            id = "moby-dick",
            title = "Moby-Dick",
            source = "assets/rag/moby-dick.md",
            wordCount = 210_000,
            selectedByDefault = false,
        ),
    )

private fun sampleRetrievalStats(similarityThreshold: Double? = RagIndexingUiState.DEFAULT_SIMILARITY_THRESHOLD): RagRetrievalStatsUi =
    RagRetrievalStatsUi(
        candidateCount = 5,
        filteredCount = if (similarityThreshold == null) 5 else 3,
        usedCount = if (similarityThreshold == null) 5 else 2,
        topKBeforeFilter = RagIndexingUiState.DEFAULT_TOP_K_BEFORE_FILTER,
        topKAfterFilter = RagIndexingUiState.DEFAULT_TOP_K_AFTER_FILTER,
        similarityThreshold = similarityThreshold,
    )

private fun sampleSearchResults(): List<RagSearchResultUi> =
    listOf(
        RagSearchResultUi(
            chunkId = "fixed_0001",
            score = 0.8123,
            title = "Android emulator endpoints",
            section = "Local services",
            source = "rag_course_2026_06_29.md",
            preview = "Use 10.0.2.2 from Android emulator to reach services running on the host machine.",
        ),
        RagSearchResultUi(
            chunkId = "fixed_0002",
            score = 0.7761,
            title = "Embedding Cache",
            section = "Cache reuse",
            source = "rag_course_2026_06_29.md",
            preview = "Embedding calls are expensive and should be reused when model, source hash, strategy, and chunk text match.",
        ),
        RagSearchResultUi(
            chunkId = "fixed_0003",
            score = 0.7028,
            title = "AIChallenge README Snapshot",
            section = "Architecture",
            source = "README_snapshot_2026_06_29.md",
            preview = "AIChallenge is an Android 12+ Kotlin app using Jetpack Compose, Material 3, Hilt, coroutines, and Navigation 3.",
        ),
        RagSearchResultUi(
            chunkId = "fixed_0004",
            score = 0.6114,
            title = "Goal",
            section = "RAG setup",
            source = "rag_course_2026_06_29.md",
            preview = "Retrieval augmented generation needs deterministic chunking, metadata, cache keys, and ranking primitives.",
        ),
        RagSearchResultUi(
            chunkId = "fixed_0005",
            score = 0.4018,
            title = "Corpus",
            section = "Assets",
            source = "rag_course_2026_06_29.md",
            preview = "The corpus lives in bundled Markdown assets and is indexed into app-local JSON files.",
        ),
    )

private fun sampleComparisonReport(): RagComparisonReport =
    RagComparisonReport(
        model = OllamaEmbeddingClient.DEFAULT_MODEL,
        settings =
            RagRetrievalSettings(
                topKBeforeFilter = RagIndexingUiState.DEFAULT_TOP_K_BEFORE_FILTER,
                topKAfterFilter = RagIndexingUiState.DEFAULT_TOP_K_AFTER_FILTER,
                similarityThreshold = RagIndexingUiState.DEFAULT_SIMILARITY_THRESHOLD,
            ),
        strategies =
            listOf(
                RagComparisonStrategyStats(
                    strategy = "fixed",
                    chunkCount = 24,
                    embeddingCount = 24,
                    averageTokens = 156.4,
                    minTokens = 80,
                    maxTokens = 220,
                ),
                RagComparisonStrategyStats(
                    strategy = "structure",
                    chunkCount = 18,
                    embeddingCount = 18,
                    averageTokens = 211.2,
                    minTokens = 120,
                    maxTokens = 320,
                ),
            ),
        queries =
            listOf(
                RagComparisonQueryReport(
                    originalQuery = "Who is Captain Ahab searching for?",
                    rewrittenQuery = null,
                    fixed =
                        RagComparisonRetrievalReport(
                            baselineHits = listOf(sampleComparisonHit("fixed_0001", "Ahab", 0.8123)),
                            improvedCandidates =
                                listOf(
                                    sampleComparisonHit("fixed_0001", "Ahab", 0.8123),
                                    sampleComparisonHit("fixed_0002", "Whale", 0.4012),
                                ),
                            filteredHits = listOf(sampleComparisonHit("fixed_0001", "Ahab", 0.8123)),
                        ),
                    structure =
                        RagComparisonRetrievalReport(
                            baselineHits = listOf(sampleComparisonHit("structure_0003", "Moby-Dick", 0.8344, "Captain Ahab")),
                            improvedCandidates = listOf(sampleComparisonHit("structure_0003", "Moby-Dick", 0.8344, "Captain Ahab")),
                            filteredHits = listOf(sampleComparisonHit("structure_0003", "Moby-Dick", 0.8344, "Captain Ahab")),
                        ),
                ),
            ),
    )

private fun sampleComparisonHit(
    chunkId: String,
    title: String,
    score: Double,
    section: String? = null,
): RagComparisonHit =
    RagComparisonHit(
        chunkId = chunkId,
        score = score,
        title = title,
        section = section,
        source = "moby_dick.md",
        preview = "Captain Ahab follows the whale across the sea.",
    )
