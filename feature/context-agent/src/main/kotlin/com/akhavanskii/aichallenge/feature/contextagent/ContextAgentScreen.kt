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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    var expandedPanel by remember { mutableStateOf<ExpandedContextPanel?>(null) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
                    selectedModel = state.selectedModel,
                    enabled = state.canChangeModel,
                    onSelected = { onAction(ContextAgentAction.ModelChanged(it)) },
                )
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.testTag(ContextAgentTags.BACK_BUTTON),
                ) {
                    Text("Back")
                }
            }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(contentScrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TokenSavingsSummary(
                tokenUsage = state.latestTokenUsage,
                contextState = state.contextState,
                modifier = Modifier.fillMaxWidth(),
                onExpand = { expandedPanel = it },
            )

            ConversationHistory(
                messages = state.messages,
                modifier = Modifier.fillMaxWidth(),
                onExpand = { expandedPanel = it },
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
            placeholder = { Text("Ask with compressed history.") },
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

    expandedPanel?.let { panel ->
        ExpandedContextPanelDialog(
            panel = panel,
            onDismiss = { expandedPanel = null },
        )
    }
}

@Composable
private fun TokenSavingsSummary(
    tokenUsage: GeminiTokenUsage?,
    contextState: ContextCompressionState,
    modifier: Modifier = Modifier,
    onExpand: (ExpandedContextPanel) -> Unit,
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainer
    val titleColor = MaterialTheme.colorScheme.onSurface
    val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant
    val body = tokenSavingsText(tokenUsage, contextState)

    CompactContextPanel(
        title = "Compression",
        body = body,
        containerColor = containerColor,
        titleColor = titleColor,
        bodyColor = bodyColor,
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(ContextAgentTags.TOKEN_SAVINGS),
        onExpand = {
            onExpand(
                ExpandedContextPanel(
                    title = "Compression",
                    body = body,
                    containerColor = containerColor,
                    titleColor = titleColor,
                    bodyColor = bodyColor,
                ),
            )
        },
    )
}

@Composable
private fun CompactContextPanel(
    title: String,
    body: String,
    containerColor: Color,
    titleColor: Color,
    bodyColor: Color,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .height(ContextAgentPanelPreviewHeight)
                .clickable(onClick = onExpand),
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = titleColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = bodyColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ConversationHistory(
    messages: List<ContextAgentMessage>,
    modifier: Modifier = Modifier,
    onExpand: (ExpandedContextPanel) -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(ContextAgentTags.HISTORY),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (messages.isEmpty()) {
            MessagePanel(
                title = "Context Agent",
                body = "No messages yet.",
                onExpand = onExpand,
            )
        } else {
            messages.forEachIndexed { index, message ->
                MessagePanel(
                    title = message.panelTitle(),
                    body = message.panelBody(),
                    isError = message.isError,
                    modifier = Modifier.testTag("${ContextAgentTags.MESSAGE}_$index"),
                    onExpand = onExpand,
                )
            }
        }
    }
}

@Composable
private fun MessagePanel(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    onExpand: (ExpandedContextPanel) -> Unit,
) {
    val containerColor =
        if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }
    val titleColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    val bodyColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant

    CompactContextPanel(
        title = title,
        body = body,
        containerColor = containerColor,
        titleColor = titleColor,
        bodyColor = bodyColor,
        modifier = modifier,
        onExpand = {
            onExpand(
                ExpandedContextPanel(
                    title = title,
                    body = body,
                    containerColor = containerColor,
                    titleColor = titleColor,
                    bodyColor = bodyColor,
                ),
            )
        },
    )
}

@Composable
private fun ExpandedContextPanelDialog(
    panel: ExpandedContextPanel,
    onDismiss: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag(ContextAgentTags.EXPANDED_PANEL),
            color = panel.containerColor,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(WindowInsets.safeDrawing.asPaddingValues())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = panel.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = panel.titleColor,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag(ContextAgentTags.EXPANDED_PANEL_CLOSE),
                    ) {
                        Text(
                            text = "Close",
                            color = panel.titleColor,
                        )
                    }
                }
                Text(
                    text = panel.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = panel.bodyColor,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(scrollState)
                            .testTag(ContextAgentTags.EXPANDED_PANEL_BODY),
                )
            }
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
            onClick = { onAction(ContextAgentAction.RunComparison) },
            enabled = !state.isLoading,
            modifier = Modifier.testTag(ContextAgentTags.COMPARE_BUTTON),
        ) {
            Text("Compare modes")
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
    const val COMPARE_BUTTON = "context_agent_compare_button"
    const val CLEAR_BUTTON = "context_agent_clear_button"
    const val STOP_BUTTON = "context_agent_stop_button"
    const val HISTORY = "context_agent_history"
    const val MESSAGE = "context_agent_message"
    const val TOKEN_SAVINGS = "context_agent_token_savings"
    const val COMPARISON = "context_agent_comparison"
    const val EXPANDED_PANEL = "context_agent_expanded_panel"
    const val EXPANDED_PANEL_BODY = "context_agent_expanded_panel_body"
    const val EXPANDED_PANEL_CLOSE = "context_agent_expanded_panel_close"
}

private data class ExpandedContextPanel(
    val title: String,
    val body: String,
    val containerColor: Color,
    val titleColor: Color,
    val bodyColor: Color,
)

private val ContextAgentPanelPreviewHeight = 104.dp

private fun tokenSavingsText(
    tokenUsage: GeminiTokenUsage?,
    contextState: ContextCompressionState,
): String =
    buildString {
        append(contextState.latestStats.formatStats())
        appendLine()
        append(
            "Latest request total: ${tokenUsage?.totalTokens.formatTokenCount()}, " +
                "prompt: ${tokenUsage?.conversationHistoryTokens.formatTokenCount()}, " +
                "response: ${tokenUsage?.modelResponseTokens.formatTokenCount()}",
        )
        if (contextState.summary.isNotBlank()) {
            appendLine()
            appendLine()
            append("Stored summary: ${contextState.summary}")
        }
    }

private fun ContextAgentMessage.panelTitle(): String =
    when {
        role == ContextAgentRole.USER -> "You"
        isError -> "Agent error"
        else -> text.knownPanelHeadingOrNull() ?: "Context Agent"
    }

private fun ContextAgentMessage.panelBody(): String =
    if (role == ContextAgentRole.MODEL && !isError && text.knownPanelHeadingOrNull() != null) {
        text.substringAfter("\n\n", missingDelimiterValue = text)
    } else {
        text
    }

private fun String.knownPanelHeadingOrNull(): String? {
    val heading = substringBefore("\n\n")
    return heading.takeIf {
        it == "Answer without compression" ||
            it == "Answer with compression" ||
            it == "Quality comparison"
    }
}

private fun ContextCompressionStats?.formatStats(): String {
    if (this == null) {
        return "Keeps the latest $CONTEXT_AGENT_RECENT_MESSAGE_COUNT messages as-is; " +
            "older turns become summary batches of $CONTEXT_AGENT_SUMMARY_BATCH_SIZE."
    }
    return "Prompt tokens: ${fullPromptTokens.formatTokenCount()} -> ${compressedPromptTokens.formatTokenCount()}, " +
        "saved ${savedPromptTokens.formatTokenCount()} (${savedPromptPercent?.let { "$it%" } ?: "unknown"}).\n" +
        "Context: $summarizedMessageCount summarized, $rawMessageCount raw, $requestMessageCount request messages."
}

private fun Int?.formatTokenCount(): String = this?.let { String.format(Locale.US, "%,d", it) } ?: "unknown"

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
                    contextState =
                        ContextCompressionState(
                            summary = "User wants a separate compressed-history agent.",
                            summarizedMessageCount = 10,
                            latestStats =
                                ContextCompressionStats(
                                    fullPromptTokens = 1_200,
                                    compressedPromptTokens = 420,
                                    savedPromptTokens = 780,
                                    savedPromptPercent = 65,
                                    summarizedMessageCount = 10,
                                    rawMessageCount = 8,
                                    requestMessageCount = 9,
                                ),
                        ),
                    messages =
                        listOf(
                            ContextAgentMessage(role = ContextAgentRole.USER, text = "Keep the latest messages raw."),
                            ContextAgentMessage(
                                role = ContextAgentRole.MODEL,
                                text = "The compressed agent keeps recent messages raw and summarizes older turns.",
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
