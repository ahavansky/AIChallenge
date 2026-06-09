package com.akhavanskii.aichallenge.feature.agentchat

import android.content.res.Configuration
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
import androidx.compose.material3.FilterChip
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
fun AgentChatScreen(
    state: AgentChatUiState,
    onAction: (AgentChatAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Agent Chat",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(
                onClick = onBack,
                modifier = Modifier.testTag(AgentChatTags.BACK_BUTTON),
            ) {
                Text("Back")
            }
        }

        AgentSelector(
            selectedAgent = state.selectedAgent,
            enabled = state.canChangeAgent,
            onSelected = { onAction(AgentChatAction.AgentChanged(it)) },
        )

        ConversationHistory(
            messages = state.messages,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.input,
            onValueChange = { onAction(AgentChatAction.InputChanged(it)) },
            enabled = !state.isLoading,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .testTag(AgentChatTags.INPUT),
            label = { Text("Message") },
            placeholder = { Text("Ask a follow-up question.") },
            minLines = 4,
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
        }
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
            ResponsePanel(
                title = "Gemini",
                body = "No messages yet.",
            )
        } else {
            messages.forEachIndexed { index, message ->
                ResponsePanel(
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentSelector(
    selectedAgent: AgentChatAgentOption,
    enabled: Boolean,
    onSelected: (AgentChatAgentOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(AgentChatTags.AGENT_SELECTOR),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Agent",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AgentChatAgentOption.entries.forEach { agent ->
                FilterChip(
                    selected = agent == selectedAgent,
                    onClick = { onSelected(agent) },
                    enabled = enabled,
                    label = { Text(agent.title) },
                    modifier = Modifier.testTag("${AgentChatTags.AGENT_PREFIX}_${agent.name}"),
                )
            }
        }
        Text(
            text = "${selectedAgent.modelName}: ${selectedAgent.description}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

object AgentChatTags {
    const val BACK_BUTTON = "agent_chat_back_button"
    const val AGENT_SELECTOR = "agent_chat_agent_selector"
    const val AGENT_PREFIX = "agent_chat_agent"
    const val INPUT = "agent_chat_input"
    const val SEND_BUTTON = "agent_chat_send_button"
    const val CLEAR_BUTTON = "agent_chat_clear_button"
    const val HISTORY = "agent_chat_history"
    const val MESSAGE = "agent_chat_message"
}

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
                            ),
                        ),
                ),
            onAction = {},
            onBack = {},
        )
    }
}
