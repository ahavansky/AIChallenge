package com.akhavanskii.aichallenge.feature.agentchat

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.akhavanskii.aichallenge.core.designsystem.AIChallengeTheme
import com.akhavanskii.aichallenge.core.designsystem.ChallengeButton
import com.akhavanskii.aichallenge.core.designsystem.ChallengeMarkdownBody
import com.akhavanskii.aichallenge.core.designsystem.ChallengeMarkdownText
import com.akhavanskii.aichallenge.core.network.GeminiTokenUsage
import java.util.Locale

@Composable
fun AgentChatScreen(
    state: AgentChatUiState,
    onAction: (AgentChatAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentScrollState = rememberScrollState()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(contentScrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Agent Chat",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AgentModelMenu(
                        selectedAgent = state.selectedAgent,
                        enabled = state.canChangeAgent,
                        onSelected = { onAction(AgentChatAction.AgentChanged(it)) },
                    )
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.testTag(AgentChatTags.BACK_BUTTON),
                    ) {
                        Text("Back")
                    }
                }
            }

            TokenUsageSummary(
                selectedAgent = state.selectedAgent,
                tokenUsage = state.latestTokenUsage,
                customTotalTokenLimit = state.customTotalTokenLimit,
                enabled = !state.isLoading,
                onTokenLimitChanged = { onAction(AgentChatAction.TokenLimitChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
            )

            ScenarioControls(
                enabled = !state.isLoading,
                onSelected = { onAction(AgentChatAction.ScenarioSelected(it)) },
                modifier = Modifier.fillMaxWidth(),
            )

            MemoryLayersSummary(
                memory = state.memory,
                modifier = Modifier.fillMaxWidth(),
            )

            ConversationHistory(
                messages = state.messages,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedTextField(
            value = state.input,
            onValueChange = { onAction(AgentChatAction.InputChanged(it)) },
            enabled = !state.isLoading,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 68.dp)
                    .testTag(AgentChatTags.INPUT),
            label = { Text("Message") },
            placeholder = { Text("Ask a follow-up.") },
            minLines = 2,
            maxLines = INPUT_MAX_LINES,
            shape = RoundedCornerShape(8.dp),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChallengeButton(
                onClick = { onAction(AgentChatAction.Submit) },
                enabled = state.canSend,
                modifier = Modifier.testTag(AgentChatTags.SEND_BUTTON),
            ) {
                Text("Send")
            }
            TextButton(
                onClick = { onAction(AgentChatAction.ClearChat) },
                enabled = state.canClear,
                modifier = Modifier.testTag(AgentChatTags.CLEAR_BUTTON),
            ) {
                Text("Clear chat")
            }
            TextButton(
                onClick = { onAction(AgentChatAction.Stop) },
                enabled = state.canStop,
                modifier = Modifier.testTag(AgentChatTags.STOP_BUTTON),
            ) {
                Text("Stop")
            }
        }
    }
}

@Composable
private fun MemoryLayersSummary(
    memory: AgentChatMemorySnapshot,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(AgentChatTags.MEMORY_LAYERS),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = "Memory layers",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = memory.formatDebugDetails(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TokenUsageSummary(
    selectedAgent: AgentChatAgentOption,
    tokenUsage: GeminiTokenUsage?,
    customTotalTokenLimit: Int?,
    enabled: Boolean,
    onTokenLimitChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(AgentChatTags.TOKEN_USAGE),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Text(
                text = tokenUsage.formatTokenSummary(selectedAgent, customTotalTokenLimit),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = customTotalTokenLimit?.toString().orEmpty(),
            onValueChange = onTokenLimitChanged,
            enabled = enabled,
            singleLine = true,
            modifier =
                Modifier
                    .width(128.dp)
                    .heightIn(min = 52.dp)
                    .testTag(AgentChatTags.TOKEN_LIMIT_INPUT),
            label = { Text("Limit", style = MaterialTheme.typography.labelSmall) },
            placeholder = {
                Text(
                    text = selectedAgent.totalTokenLimit.formatTokenCount(),
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(8.dp),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
        )
    }
}

@Composable
private fun ConversationHistory(
    messages: List<AgentChatMessage>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(AgentChatTags.HISTORY),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (messages.isEmpty()) {
            ChatMessagePanel(
                title = "Gemini",
                body = "No messages yet.",
            )
        } else {
            messages.forEachIndexed { index, message ->
                ChatMessagePanel(
                    title =
                        when {
                            message.role == AgentChatRole.USER -> "You"
                            message.isError -> "Gemini error"
                            else -> "Gemini"
                        },
                    body = message.text,
                    isError = message.isError,
                    modifier = Modifier.testTag("${AgentChatTags.MESSAGE}_$index"),
                )
            }
        }
    }
}

@Composable
private fun ChatMessagePanel(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    var expanded by remember(body) { mutableStateOf(false) }
    val canToggle = body.shouldCollapse()
    val bodyMaxLines = if (canToggle && !expanded) COLLAPSED_MESSAGE_BODY_LINES else Int.MAX_VALUE
    val bodyColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(enabled = canToggle) { expanded = !expanded }
                .semantics {
                    if (canToggle) {
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    }
                },
        shape = RoundedCornerShape(8.dp),
        color =
            if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
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
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            if (expanded) {
                ChallengeMarkdownBody(
                    body = body,
                    color = bodyColor,
                )
            } else {
                ChallengeMarkdownText(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = bodyColor,
                    maxLines = bodyMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScenarioControls(
    enabled: Boolean,
    onSelected: (AgentChatScenario) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(AgentChatTags.TOKEN_SCENARIOS),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Live scenarios",
            modifier = Modifier.align(Alignment.CenterVertically),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        AgentChatScenario.entries.forEach { scenario ->
            TextButton(
                onClick = { onSelected(scenario) },
                enabled = enabled,
                modifier = Modifier.testTag("${AgentChatTags.SCENARIO_PREFIX}_${scenario.name}"),
            ) {
                Text(
                    text = scenario.title,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun AgentModelMenu(
    selectedAgent: AgentChatAgentOption,
    enabled: Boolean,
    onSelected: (AgentChatAgentOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    TextButton(
        onClick = { expanded = true },
        enabled = enabled,
        modifier = Modifier.testTag(AgentChatTags.AGENT_MENU_BUTTON),
    ) {
        Text(
            text = selectedAgent.title,
            style = MaterialTheme.typography.labelMedium,
        )
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
    ) {
        AgentChatAgentOption.entries.forEach { agent ->
            DropdownMenuItem(
                text = { Text(agent.title) },
                enabled = enabled,
                onClick = {
                    expanded = false
                    onSelected(agent)
                },
                modifier = Modifier.testTag("${AgentChatTags.AGENT_PREFIX}_${agent.name}"),
            )
        }
    }
}

object AgentChatTags {
    const val BACK_BUTTON = "agent_chat_back_button"
    const val AGENT_MENU_BUTTON = "agent_chat_agent_menu_button"
    const val AGENT_PREFIX = "agent_chat_agent"
    const val SCENARIO_PREFIX = "agent_chat_scenario"
    const val INPUT = "agent_chat_input"
    const val SEND_BUTTON = "agent_chat_send_button"
    const val CLEAR_BUTTON = "agent_chat_clear_button"
    const val STOP_BUTTON = "agent_chat_stop_button"
    const val HISTORY = "agent_chat_history"
    const val MESSAGE = "agent_chat_message"
    const val TOKEN_USAGE = "agent_chat_token_usage"
    const val TOKEN_LIMIT_INPUT = "agent_chat_token_limit_input"
    const val TOKEN_SCENARIOS = "agent_chat_token_scenarios"
    const val MEMORY_LAYERS = "agent_chat_memory_layers"
}

private fun GeminiTokenUsage?.formatTokenSummary(
    selectedAgent: AgentChatAgentOption,
    customTotalTokenLimit: Int?,
): String {
    val usedTokens = this?.totalTokens
    val effectiveLimit = customTotalTokenLimit ?: selectedAgent.totalTokenLimit
    val limitSource = if (customTotalTokenLimit == null) "model" else "custom"
    val remainingTokens = usedTokens?.let { (effectiveLimit - it).coerceAtLeast(0) }
    val windowStatus =
        if (this?.slidingWindowApplied == true) {
            "sliding applied"
        } else {
            "sliding ready"
        }
    return "Req/history/resp: ${this?.currentRequestTokens.formatTokenCount()} / " +
        "${this?.conversationHistoryTokens.formatTokenCount()} / ${this?.modelResponseTokens.formatTokenCount()}\n" +
        "Total/limit: ${usedTokens.formatTokenCount()} / ${effectiveLimit.formatTokenCount()} ($limitSource), " +
        "left ${remainingTokens.formatTokenCount()}\n" +
        "Window: $windowStatus | max ${selectedAgent.totalTokenLimit.formatTokenCount()}"
}

private fun Int?.formatTokenCount(): String = this?.let { String.format(Locale.US, "%,d", it) } ?: "unknown"

private fun String.shouldCollapse(): Boolean =
    lineSequence().count() > COLLAPSED_MESSAGE_BODY_LINES || length > COLLAPSED_MESSAGE_CHAR_LIMIT

private const val COLLAPSED_MESSAGE_BODY_LINES = 4
private const val COLLAPSED_MESSAGE_CHAR_LIMIT = 220
private const val INPUT_MAX_LINES = 5

@Preview(showBackground = true, widthDp = 390, heightDp = 840)
@Composable
fun AgentChatEmptyPreview() {
    AIChallengeTheme(dynamicColor = false) {
        AgentChatScreen(
            state = AgentChatUiState(),
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
fun AgentChatConversationPreview() {
    AIChallengeTheme(dynamicColor = false) {
        AgentChatScreen(
            state =
                AgentChatUiState(
                    input = "Can you make it shorter?",
                    messages =
                        listOf(
                            AgentChatMessage(role = AgentChatRole.USER, text = "Explain Navigation 3 state."),
                            AgentChatMessage(
                                role = AgentChatRole.MODEL,
                                text = "Navigation state is represented by a back stack of typed keys.",
                                tokenUsage =
                                    GeminiTokenUsage(
                                        currentRequestTokens = 8,
                                        conversationHistoryTokens = 22,
                                        modelResponseTokens = 13,
                                        totalTokens = 35,
                                    ),
                            ),
                        ),
                ),
            onAction = {},
            onBack = {},
        )
    }
}
