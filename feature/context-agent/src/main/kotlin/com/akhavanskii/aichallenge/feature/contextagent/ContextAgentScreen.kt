package com.akhavanskii.aichallenge.feature.contextagent

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.akhavanskii.aichallenge.core.designsystem.AIChallengeTheme
import com.akhavanskii.aichallenge.core.designsystem.ChallengeButton
import com.akhavanskii.aichallenge.core.network.GeminiTokenUsage
import java.util.Locale

@Composable
fun ContextAgentScreen(
    state: ContextAgentUiState,
    onAction: (ContextAgentAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentScrollState = rememberScrollState()
    val latestMessage = state.activeMessages.lastOrNull()

    LaunchedEffect(
        state.selectedStrategy,
        state.branchingState.activeBranchId,
        state.activeMessages.size,
        latestMessage?.text,
        latestMessage?.isLoading,
        latestMessage?.isError,
    ) {
        if (state.activeMessages.isNotEmpty()) {
            withFrameNanos { }
            contentScrollState.animateScrollTo(contentScrollState.maxValue)
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HeaderRow(
            selectedModel = state.selectedModel,
            canChangeModel = state.canChangeModel,
            onModelSelected = { onAction(ContextAgentAction.ModelChanged(it)) },
            onBack = onBack,
        )

        StrategySelector(
            selectedStrategy = state.selectedStrategy,
            enabled = state.canChangeStrategy,
            onSelected = { onAction(ContextAgentAction.StrategyChanged(it)) },
        )

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(contentScrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StrategyStatusPanel(
                state = state,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.selectedStrategy == ContextManagementStrategy.BRANCHING) {
                BranchControls(
                    state = state,
                    onAction = onAction,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            FactsPanel(
                facts = state.facts,
                modifier = Modifier.fillMaxWidth(),
            )

            state.comparison?.let { comparison ->
                ScenarioComparisonPanel(
                    comparison = comparison,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ConversationHistory(
                messages = state.activeMessages,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedTextField(
            value = state.input,
            onValueChange = { onAction(ContextAgentAction.InputChanged(it)) },
            enabled = !state.isLoading,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 68.dp)
                    .testTag(ContextAgentTags.INPUT),
            label = { Text("Message") },
            placeholder = { Text("Ask with ${state.selectedStrategy.title}.") },
            minLines = 2,
            shape = RoundedCornerShape(8.dp),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
        )

        ActionRow(
            state = state,
            onAction = onAction,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HeaderRow(
    selectedModel: ContextAgentModelOption,
    canChangeModel: Boolean,
    onModelSelected: (ContextAgentModelOption) -> Unit,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Context Agent",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ContextModelMenu(
                selectedModel = selectedModel,
                enabled = canChangeModel,
                onSelected = onModelSelected,
            )
            TextButton(
                onClick = onBack,
                modifier = Modifier.testTag(ContextAgentTags.BACK_BUTTON),
            ) {
                Text("Back")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StrategySelector(
    selectedStrategy: ContextManagementStrategy,
    enabled: Boolean,
    onSelected: (ContextManagementStrategy) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        ContextManagementStrategy.entries.forEachIndexed { index, strategy ->
            SegmentedButton(
                selected = strategy == selectedStrategy,
                enabled = enabled,
                onClick = { onSelected(strategy) },
                shape =
                    SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = ContextManagementStrategy.entries.size,
                    ),
                label = {
                    Text(
                        text = strategy.shortTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.testTag("${ContextAgentTags.STRATEGY_PREFIX}_${strategy.name}"),
            )
        }
    }
}

@Composable
private fun StrategyStatusPanel(
    state: ContextAgentUiState,
    modifier: Modifier = Modifier,
) {
    InfoPanel(
        title = state.selectedStrategy.title,
        body = strategyStatusText(state),
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(ContextAgentTags.STRATEGY_STATS),
    )
}

@Composable
private fun BranchControls(
    state: ContextAgentUiState,
    onAction: (ContextAgentAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ContextBranchId.entries.forEach { branchId ->
                TextButton(
                    onClick = { onAction(ContextAgentAction.BranchChanged(branchId)) },
                    enabled = !state.isLoading,
                    modifier = Modifier.testTag("${ContextAgentTags.BRANCH_PREFIX}_${branchId.name}"),
                ) {
                    Text(if (branchId == state.activeBranch.id) "${branchId.title} *" else branchId.title)
                }
            }
            TextButton(
                onClick = { onAction(ContextAgentAction.SaveCheckpoint) },
                enabled = state.canSaveCheckpoint,
                modifier = Modifier.testTag(ContextAgentTags.SAVE_CHECKPOINT_BUTTON),
            ) {
                Text("Checkpoint")
            }
            TextButton(
                onClick = { onAction(ContextAgentAction.CreateBranches) },
                enabled = state.canCreateBranches,
                modifier = Modifier.testTag(ContextAgentTags.CREATE_BRANCHES_BUTTON),
            ) {
                Text("Create branches")
            }
        }
        InfoPanel(
            title = "Branch state",
            body = branchStateText(state.branchingState),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FactsPanel(
    facts: List<ContextFact>,
    modifier: Modifier = Modifier,
) {
    InfoPanel(
        title = "Facts",
        body =
            if (facts.isEmpty()) {
                "No facts captured yet."
            } else {
                facts.joinToString(separator = "\n") { fact -> "- ${fact.key}: ${fact.value}" }
            },
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(ContextAgentTags.FACTS),
    )
}

@Composable
private fun ScenarioComparisonPanel(
    comparison: ContextScenarioComparison,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(ContextAgentTags.SCENARIO_COMPARISON),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InfoPanel(
            title = "Scenario comparison",
            body = comparison.evaluation,
            modifier = Modifier.fillMaxWidth(),
        )
        comparison.reports.forEachIndexed { index, report ->
            InfoPanel(
                title = report.reportTitle(),
                body = report.reportBody(),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("${ContextAgentTags.SCENARIO_REPORT}_$index"),
            )
        }
    }
}

@Composable
private fun ConversationHistory(
    messages: List<ContextAgentMessage>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(ContextAgentTags.HISTORY),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (messages.isEmpty()) {
            InfoPanel(
                title = "Conversation",
                body = "No messages yet.",
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            messages.forEachIndexed { index, message ->
                InfoPanel(
                    title = message.panelTitle(),
                    body = message.text,
                    isError = message.isError,
                    collapsedBodyMaxLines = message.collapsedBodyMaxLines(),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("${ContextAgentTags.MESSAGE}_$index"),
                )
            }
        }
    }
}

@Composable
private fun InfoPanel(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    collapsedBodyMaxLines: Int? = null,
) {
    val containerColor =
        if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }
    val titleColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    val bodyColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    var expanded by remember(body, collapsedBodyMaxLines) { mutableStateOf(false) }
    val canToggle = collapsedBodyMaxLines != null
    val maxBodyLines = collapsedBodyMaxLines?.takeUnless { expanded }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(enabled = canToggle) { expanded = !expanded },
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = titleColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = bodyColor,
                maxLines = maxBodyLines ?: Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(
    state: ContextAgentUiState,
    onAction: (ContextAgentAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ChallengeButton(
            onClick = { onAction(ContextAgentAction.Submit) },
            enabled = state.canSend,
            modifier = Modifier.testTag(ContextAgentTags.SEND_BUTTON),
        ) {
            Text("Send")
        }
        TextButton(
            onClick = { onAction(ContextAgentAction.RunScenarioComparison) },
            enabled = !state.isLoading,
            modifier = Modifier.testTag(ContextAgentTags.RUN_SCENARIO_BUTTON),
        ) {
            Text(if (state.isScenarioRunning) "Running" else "Run scenario")
        }
        TextButton(
            onClick = { onAction(ContextAgentAction.Clear) },
            enabled = state.canClear,
            modifier = Modifier.testTag(ContextAgentTags.CLEAR_BUTTON),
        ) {
            Text("Clear")
        }
        TextButton(
            onClick = { onAction(ContextAgentAction.Stop) },
            enabled = state.canStop,
            modifier = Modifier.testTag(ContextAgentTags.STOP_BUTTON),
        ) {
            Text("Stop")
        }
    }
}

@Composable
private fun ContextModelMenu(
    selectedModel: ContextAgentModelOption,
    enabled: Boolean,
    onSelected: (ContextAgentModelOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    TextButton(
        onClick = { expanded = true },
        enabled = enabled,
        modifier = Modifier.testTag(ContextAgentTags.MODEL_MENU_BUTTON),
    ) {
        Text(
            text = selectedModel.title,
            style = MaterialTheme.typography.labelMedium,
        )
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
    ) {
        ContextAgentModelOption.entries.forEach { model ->
            DropdownMenuItem(
                text = { Text(model.title) },
                enabled = enabled,
                onClick = {
                    expanded = false
                    onSelected(model)
                },
                modifier = Modifier.testTag("${ContextAgentTags.MODEL_PREFIX}_${model.name}"),
            )
        }
    }
}

object ContextAgentTags {
    const val BACK_BUTTON = "context_agent_back_button"
    const val MODEL_MENU_BUTTON = "context_agent_model_menu_button"
    const val MODEL_PREFIX = "context_agent_model"
    const val INPUT = "context_agent_input"
    const val SEND_BUTTON = "context_agent_send_button"
    const val RUN_SCENARIO_BUTTON = "context_agent_run_scenario_button"
    const val CLEAR_BUTTON = "context_agent_clear_button"
    const val STOP_BUTTON = "context_agent_stop_button"
    const val STRATEGY_PREFIX = "context_agent_strategy"
    const val BRANCH_PREFIX = "context_agent_branch"
    const val SAVE_CHECKPOINT_BUTTON = "context_agent_save_checkpoint_button"
    const val CREATE_BRANCHES_BUTTON = "context_agent_create_branches_button"
    const val HISTORY = "context_agent_history"
    const val MESSAGE = "context_agent_message"
    const val FACTS = "context_agent_facts"
    const val STRATEGY_STATS = "context_agent_strategy_stats"
    const val SCENARIO_COMPARISON = "context_agent_scenario_comparison"
    const val SCENARIO_REPORT = "context_agent_scenario_report"
}

private fun strategyStatusText(state: ContextAgentUiState): String {
    val stats = state.strategyStats
    if (stats == null) {
        return state.selectedStrategy.description
    }
    return buildString {
        appendLine("Prompt tokens: ${stats.fullPromptTokens.formatTokenCount()} -> ${stats.strategyPromptTokens.formatTokenCount()}")
        appendLine("Saved: ${stats.savedPromptTokens.formatTokenCount()} (${stats.savedPromptPercent?.let { "$it%" } ?: "unknown"})")
        appendLine(
            "Messages: stored=${stats.storedMessageCount}, request=${stats.requestMessageCount}, " +
                "dropped=${stats.droppedMessageCount}",
        )
        if (stats.factsCount > 0) {
            appendLine("Facts: ${stats.factsCount}")
        }
        stats.activeBranchTitle?.let { branchTitle ->
            append("Active branch: $branchTitle")
        }
    }.trim()
}

private fun branchStateText(branchingState: ContextBranchingState): String =
    buildString {
        appendLine("Checkpoint messages: ${branchingState.checkpointMessages.size}")
        branchingState.branches.forEach { branch ->
            appendLine("${branch.title}: ${branch.messages.size} messages")
        }
        append("Active: ${branchingState.activeBranch.title}")
    }

private fun ContextScenarioStrategyReport.reportTitle(): String = branchTitle?.let { "${strategy.title} - $it" } ?: strategy.title

private fun ContextScenarioStrategyReport.reportBody(): String =
    buildString {
        appendLine("Prompt tokens: ${promptTokens.formatTokenCount()}, request messages: $requestMessageCount")
        appendLine("Quality: $quality")
        appendLine("Stability: $stability")
        appendLine("Token use: $tokenUse")
        appendLine("User convenience: $userConvenience")
        appendLine()
        append(answer)
    }

private fun ContextAgentMessage.panelTitle(): String =
    when {
        role == ContextAgentRole.USER -> "You"
        isError -> "Agent error"
        isLoading -> "Context Agent"
        else -> "Context Agent"
    }

private fun ContextAgentMessage.collapsedBodyMaxLines(): Int? =
    if (role == ContextAgentRole.MODEL && !isLoading) {
        CONTEXT_AGENT_COLLAPSED_MESSAGE_LINES
    } else {
        null
    }

private fun Int?.formatTokenCount(): String = this?.let { String.format(Locale.US, "%,d", it) } ?: "unknown"

private const val CONTEXT_AGENT_COLLAPSED_MESSAGE_LINES = 4

@Preview(showBackground = true, widthDp = 390, heightDp = 840)
@Composable
fun ContextAgentEmptyPreview() {
    AIChallengeTheme(dynamicColor = false) {
        ContextAgentScreen(
            state = ContextAgentUiState(),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview(
    showBackground = true,
    widthDp = 390,
    heightDp = 840,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun ContextAgentConversationPreview() {
    AIChallengeTheme(dynamicColor = false) {
        ContextAgentScreen(
            state =
                ContextAgentUiState(
                    input = "What changed?",
                    selectedStrategy = ContextManagementStrategy.STICKY_FACTS,
                    facts =
                        listOf(
                            ContextFact("goal", "Collect requirements for a delivery app."),
                            ContextFact("constraints", "No payments in MVP."),
                        ),
                    strategyStats =
                        ContextStrategyStats(
                            strategy = ContextManagementStrategy.STICKY_FACTS,
                            fullPromptTokens = 1_200,
                            strategyPromptTokens = 420,
                            savedPromptTokens = 780,
                            savedPromptPercent = 65,
                            storedMessageCount = 12,
                            requestMessageCount = 9,
                            droppedMessageCount = 4,
                            factsCount = 2,
                        ),
                    messages =
                        listOf(
                            ContextAgentMessage(role = ContextAgentRole.USER, text = "Goal: collect requirements."),
                            ContextAgentMessage(
                                role = ContextAgentRole.MODEL,
                                text = "Facts updated and the latest window was sent with durable key-value memory.",
                                tokenUsage =
                                    GeminiTokenUsage(
                                        conversationHistoryTokens = 420,
                                        modelResponseTokens = 44,
                                        totalTokens = 464,
                                    ),
                            ),
                        ),
                ),
            onAction = {},
            onBack = {},
        )
    }
}
