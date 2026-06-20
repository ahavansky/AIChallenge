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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.akhavanskii.aichallenge.core.designsystem.AIChallengeTheme
import com.akhavanskii.aichallenge.core.designsystem.ChallengeButton
import com.akhavanskii.aichallenge.core.designsystem.ChallengeMarkdownBody
import com.akhavanskii.aichallenge.core.designsystem.ChallengeMarkdownText

@OptIn(ExperimentalLayoutApi::class)
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
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.testTag(AgentChatTags.BACK_BUTTON),
                ) {
                    Text("Back")
                }
            }

            MemoryLayersSummary(
                messages = state.messages,
                memory = state.memory,
                activeProfile = state.activeProfile,
                profiles = state.profiles,
                profileInput = state.profileInput,
                taskContextInput = state.taskContextInput,
                isLongTermMemoryDirty = state.isLongTermMemoryDirty,
                enabled = !state.isLoading,
                canCompareProfiles = state.canCompareProfiles,
                canSaveLongTermMemory = state.canSaveLongTermMemory,
                canClearTaskContext = state.canClearTaskContext,
                onProfileSelected = { onAction(AgentChatAction.ProfileChanged(it)) },
                onProfileInputChanged = { onAction(AgentChatAction.ProfileInputChanged(it)) },
                onCompareProfiles = { onAction(AgentChatAction.CompareProfiles) },
                onTaskContextChanged = { onAction(AgentChatAction.TaskContextChanged(it)) },
                onLongTermMemoryChanged = { onAction(AgentChatAction.LongTermMemoryChanged(it)) },
                onSaveLongTermMemory = { onAction(AgentChatAction.SaveLongTermMemory) },
                onClearTaskContext = { onAction(AgentChatAction.ClearTaskContext) },
                modifier = Modifier.fillMaxWidth(),
            )

            TaskStatePanel(
                taskState = state.memory.taskState,
                canPauseTask = state.canPauseTask,
                canResumeTask = state.canResumeTask,
                canRetryTask = state.canRetryTask,
                canResetTask = state.canResetTask,
                onPauseTask = { onAction(AgentChatAction.PauseTask) },
                onResumeTask = { onAction(AgentChatAction.ResumeTask) },
                onRetryTask = { onAction(AgentChatAction.RetryTask) },
                onResetTask = { onAction(AgentChatAction.ResetTask) },
                modifier = Modifier.fillMaxWidth(),
            )

            ConversationHistory(
                messages = state.messages,
                modifier = Modifier.fillMaxWidth(),
            )

            ProfileCompareResults(
                results = state.compareResults,
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
                    .heightIn(min = 68.dp, max = 112.dp)
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

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ChallengeButton(
                onClick = { onAction(AgentChatAction.StartTask) },
                enabled = state.canRunTask,
                modifier = Modifier.testTag(AgentChatTags.RUN_TASK_BUTTON),
            ) {
                Text("Run task")
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskStatePanel(
    taskState: AgentTaskState,
    canPauseTask: Boolean,
    canResumeTask: Boolean,
    canRetryTask: Boolean,
    canResetTask: Boolean,
    onPauseTask: () -> Unit,
    onResumeTask: () -> Unit,
    onRetryTask: () -> Unit,
    onResetTask: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var artifactsExpanded by remember(taskState.taskId, taskState.artifacts.size) { mutableStateOf(false) }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(AgentChatTags.TASK_STATE),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Task state",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AgentTaskStage.entries.forEach { stage ->
                    val isReached = taskState.hasActiveTask && stage.ordinal <= taskState.stage.ordinal
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color =
                            if (isReached) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                    ) {
                        Text(
                            text = stage.title,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (isReached) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
            }
            Text(
                text = taskState.formatDebugDetails(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (taskState.branches.isNotEmpty()) {
                FlowRow(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(AgentChatTags.TASK_BRANCHES),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    taskState.branches.forEach { branch ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = branch.status.containerColor(),
                        ) {
                            Text(
                                text = "${branch.id.title}: ${branch.status.title}",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = branch.status.contentColor(),
                            )
                        }
                    }
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = onPauseTask,
                    enabled = canPauseTask,
                    modifier = Modifier.testTag(AgentChatTags.PAUSE_TASK_BUTTON),
                ) {
                    Text("Pause task")
                }
                TextButton(
                    onClick = onResumeTask,
                    enabled = canResumeTask,
                    modifier = Modifier.testTag(AgentChatTags.RESUME_TASK_BUTTON),
                ) {
                    Text("Continue task")
                }
                TextButton(
                    onClick = onRetryTask,
                    enabled = canRetryTask,
                    modifier = Modifier.testTag(AgentChatTags.RETRY_TASK_BUTTON),
                ) {
                    Text("Retry step")
                }
                TextButton(
                    onClick = onResetTask,
                    enabled = canResetTask,
                    modifier = Modifier.testTag(AgentChatTags.RESET_TASK_BUTTON),
                ) {
                    Text("Reset task")
                }
            }
            if (taskState.artifacts.isNotEmpty()) {
                TextButton(
                    onClick = { artifactsExpanded = !artifactsExpanded },
                    modifier = Modifier.testTag(AgentChatTags.TASK_ARTIFACTS_TOGGLE),
                ) {
                    Text(if (artifactsExpanded) "Hide artifacts" else "Show artifacts")
                }
            }
            if (artifactsExpanded) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(AgentChatTags.TASK_ARTIFACTS),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    taskState.artifacts.forEach { artifact ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = artifact.type.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                ChallengeMarkdownText(
                                    text = artifact.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = COLLAPSED_ARTIFACT_LINES,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryLayersSummary(
    messages: List<AgentChatMessage>,
    memory: AgentChatMemorySnapshot,
    activeProfile: AgentChatUserProfile,
    profiles: List<AgentChatUserProfile>,
    profileInput: String,
    taskContextInput: String,
    isLongTermMemoryDirty: Boolean,
    enabled: Boolean,
    canCompareProfiles: Boolean,
    canSaveLongTermMemory: Boolean,
    canClearTaskContext: Boolean,
    onProfileSelected: (String) -> Unit,
    onProfileInputChanged: (String) -> Unit,
    onCompareProfiles: () -> Unit,
    onTaskContextChanged: (String) -> Unit,
    onLongTermMemoryChanged: (String) -> Unit,
    onSaveLongTermMemory: () -> Unit,
    onClearTaskContext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isExpanded by remember { mutableStateOf(false) }
    var isProfileEditorVisible by remember { mutableStateOf(false) }
    var isTaskContextEditorVisible by remember { mutableStateOf(false) }
    var isLongTermMemoryEditorVisible by remember { mutableStateOf(false) }

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
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Memory layers",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = " · ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text =
                        memory.formatCompactMemoryDetails(
                            activeProfile = activeProfile,
                            isLongTermMemoryDirty = isLongTermMemoryDirty,
                        ),
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(end = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (isExpanded) "Hide" else "Details",
                    modifier =
                        Modifier
                            .clickable { isExpanded = !isExpanded }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                            .testTag(AgentChatTags.MEMORY_LAYERS_TOGGLE),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (isExpanded) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ProfileMenu(
                        profiles = profiles,
                        activeProfile = activeProfile,
                        enabled = enabled,
                        onSelected = onProfileSelected,
                    )
                    TextButton(
                        onClick = { isProfileEditorVisible = !isProfileEditorVisible },
                        enabled = enabled,
                        modifier = Modifier.testTag(AgentChatTags.PROFILE_EDITOR_TOGGLE),
                    ) {
                        Text(if (isProfileEditorVisible) "Hide profile" else "Edit profile")
                    }
                    TextButton(
                        onClick = onCompareProfiles,
                        enabled = canCompareProfiles,
                        modifier = Modifier.testTag(AgentChatTags.COMPARE_PROFILES_BUTTON),
                    ) {
                        Text("Compare profiles")
                    }
                    TextButton(
                        onClick = { isTaskContextEditorVisible = !isTaskContextEditorVisible },
                        enabled = enabled,
                        modifier = Modifier.testTag(AgentChatTags.TASK_CONTEXT_EDITOR_TOGGLE),
                    ) {
                        Text(if (isTaskContextEditorVisible) "Hide TaskContext" else "Edit TaskContext")
                    }
                    TextButton(
                        onClick = { isLongTermMemoryEditorVisible = !isLongTermMemoryEditorVisible },
                        enabled = enabled,
                        modifier = Modifier.testTag(AgentChatTags.LONG_TERM_MEMORY_EDITOR_TOGGLE),
                    ) {
                        Text(if (isLongTermMemoryEditorVisible) "Hide memory.md" else "Edit memory.md")
                    }
                }
                Text(
                    text = memory.formatDebugDetails(messages, activeProfile = activeProfile),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isProfileEditorVisible) {
                    OutlinedTextField(
                        value = profileInput,
                        onValueChange = onProfileInputChanged,
                        enabled = enabled,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp)
                                .testTag(AgentChatTags.PROFILE_INPUT),
                        label = { Text("User profile", style = MaterialTheme.typography.labelSmall) },
                        minLines = 5,
                        maxLines = PROFILE_MAX_LINES,
                        textStyle = MaterialTheme.typography.bodySmall,
                        shape = RoundedCornerShape(8.dp),
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                    )
                }
                if (isTaskContextEditorVisible) {
                    OutlinedTextField(
                        value = taskContextInput,
                        onValueChange = onTaskContextChanged,
                        enabled = enabled,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 96.dp)
                                .testTag(AgentChatTags.TASK_CONTEXT_INPUT),
                        label = { Text("TaskContext", style = MaterialTheme.typography.labelSmall) },
                        minLines = 4,
                        maxLines = TASK_CONTEXT_MAX_LINES,
                        textStyle = MaterialTheme.typography.bodySmall,
                        shape = RoundedCornerShape(8.dp),
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                    )
                    TextButton(
                        onClick = onClearTaskContext,
                        enabled = canClearTaskContext,
                        modifier = Modifier.testTag(AgentChatTags.CLEAR_TASK_CONTEXT_BUTTON),
                    ) {
                        Text("Clear task")
                    }
                }
                if (isLongTermMemoryEditorVisible) {
                    OutlinedTextField(
                        value = memory.longTermMarkdown.markdown,
                        onValueChange = onLongTermMemoryChanged,
                        enabled = enabled,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 132.dp)
                                .testTag(AgentChatTags.LONG_TERM_MEMORY_INPUT),
                        label = {
                            Text(
                                text = memory.longTermMarkdown.fileName,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        minLines = 5,
                        maxLines = LONG_TERM_MEMORY_MAX_LINES,
                        textStyle = MaterialTheme.typography.bodySmall,
                        shape = RoundedCornerShape(8.dp),
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                    )
                }
                if (isLongTermMemoryEditorVisible || isLongTermMemoryDirty) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChallengeButton(
                            onClick = onSaveLongTermMemory,
                            enabled = canSaveLongTermMemory,
                            modifier = Modifier.testTag(AgentChatTags.SAVE_LONG_TERM_MEMORY_BUTTON),
                        ) {
                            Text("Save memory.md")
                        }
                        Text(
                            text = if (isLongTermMemoryDirty) "Unsaved" else "Saved",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun AgentChatMemorySnapshot.formatCompactMemoryDetails(
    activeProfile: AgentChatUserProfile,
    isLongTermMemoryDirty: Boolean,
): String {
    val lastRequestLayers =
        lastRequest
            ?.includedLayers
            ?.joinToString(separator = ", ") { it.title }
            ?.ifBlank { "latest message only" }
            ?: "no request yet"
    return buildString {
        append("Profile: ${activeProfile.title}")
        if (taskState.hasActiveTask) {
            append(" · Task: ${taskState.stage.title}/${taskState.status.title}")
        }
        append(" · Prompt: $lastRequestLayers")
        if (isLongTermMemoryDirty) {
            append(" · memory.md unsaved")
        }
    }
}

@Composable
private fun AgentTaskBranchStatus.containerColor() =
    when (this) {
        AgentTaskBranchStatus.PENDING -> MaterialTheme.colorScheme.surface
        AgentTaskBranchStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer
        AgentTaskBranchStatus.PAUSED -> MaterialTheme.colorScheme.secondaryContainer
        AgentTaskBranchStatus.DONE -> MaterialTheme.colorScheme.tertiaryContainer
        AgentTaskBranchStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
    }

@Composable
private fun AgentTaskBranchStatus.contentColor() =
    when (this) {
        AgentTaskBranchStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
        AgentTaskBranchStatus.RUNNING -> MaterialTheme.colorScheme.onPrimaryContainer
        AgentTaskBranchStatus.PAUSED -> MaterialTheme.colorScheme.onSecondaryContainer
        AgentTaskBranchStatus.DONE -> MaterialTheme.colorScheme.onTertiaryContainer
        AgentTaskBranchStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
    }

@Composable
private fun ProfileMenu(
    profiles: List<AgentChatUserProfile>,
    activeProfile: AgentChatUserProfile,
    enabled: Boolean,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    TextButton(
        onClick = { expanded = true },
        enabled = enabled,
        modifier = modifier.testTag(AgentChatTags.PROFILE_MENU_BUTTON),
    ) {
        Text(
            text = activeProfile.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
    ) {
        profiles.forEach { profile ->
            DropdownMenuItem(
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(profile.title, style = MaterialTheme.typography.bodyMedium)
                        if (profile.role.isNotBlank()) {
                            Text(
                                text = profile.role,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                onClick = {
                    expanded = false
                    onSelected(profile.id)
                },
                modifier = Modifier.testTag("${AgentChatTags.PROFILE_PREFIX}_${profile.id}"),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileCompareResults(
    results: List<AgentChatProfileCompareResult>,
    modifier: Modifier = Modifier,
) {
    if (results.isEmpty()) return

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(AgentChatTags.PROFILE_COMPARE_RESULTS),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Profile comparison",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                results.forEach { result ->
                    var expanded by remember(result.profileId, result.text) { mutableStateOf(false) }
                    Surface(
                        modifier =
                            Modifier
                                .width(260.dp)
                                .clickable { expanded = !expanded }
                                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                                .testTag("${AgentChatTags.PROFILE_COMPARE_RESULT_PREFIX}_${result.profileId}"),
                        shape = RoundedCornerShape(8.dp),
                        color =
                            if (result.isError) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = result.profileTitle,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            val bodyColor =
                                if (result.isError) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            if (expanded) {
                                ChallengeMarkdownBody(
                                    body = result.text,
                                    color = bodyColor,
                                )
                            } else {
                                ChallengeMarkdownText(
                                    text = result.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = bodyColor,
                                    maxLines = COLLAPSED_PROFILE_COMPARE_LINES,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
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

object AgentChatTags {
    const val BACK_BUTTON = "agent_chat_back_button"
    const val INPUT = "agent_chat_input"
    const val RUN_TASK_BUTTON = "agent_chat_run_task_button"
    const val CLEAR_BUTTON = "agent_chat_clear_button"
    const val STOP_BUTTON = "agent_chat_stop_button"
    const val TASK_STATE = "agent_chat_task_state"
    const val PAUSE_TASK_BUTTON = "agent_chat_pause_task_button"
    const val RESUME_TASK_BUTTON = "agent_chat_resume_task_button"
    const val RETRY_TASK_BUTTON = "agent_chat_retry_task_button"
    const val RESET_TASK_BUTTON = "agent_chat_reset_task_button"
    const val TASK_BRANCHES = "agent_chat_task_branches"
    const val TASK_ARTIFACTS_TOGGLE = "agent_chat_task_artifacts_toggle"
    const val TASK_ARTIFACTS = "agent_chat_task_artifacts"
    const val COMPARE_PROFILES_BUTTON = "agent_chat_compare_profiles_button"
    const val HISTORY = "agent_chat_history"
    const val MESSAGE = "agent_chat_message"
    const val MEMORY_LAYERS = "agent_chat_memory_layers"
    const val MEMORY_LAYERS_TOGGLE = "agent_chat_memory_layers_toggle"
    const val PROFILE_MENU_BUTTON = "agent_chat_profile_menu_button"
    const val PROFILE_PREFIX = "agent_chat_profile"
    const val PROFILE_EDITOR_TOGGLE = "agent_chat_profile_editor_toggle"
    const val PROFILE_INPUT = "agent_chat_profile_input"
    const val PROFILE_COMPARE_RESULTS = "agent_chat_profile_compare_results"
    const val PROFILE_COMPARE_RESULT_PREFIX = "agent_chat_profile_compare_result"
    const val TASK_CONTEXT_EDITOR_TOGGLE = "agent_chat_task_context_editor_toggle"
    const val TASK_CONTEXT_INPUT = "agent_chat_task_context_input"
    const val CLEAR_TASK_CONTEXT_BUTTON = "agent_chat_clear_task_context_button"
    const val LONG_TERM_MEMORY_EDITOR_TOGGLE = "agent_chat_long_term_memory_editor_toggle"
    const val LONG_TERM_MEMORY_INPUT = "agent_chat_long_term_memory_input"
    const val SAVE_LONG_TERM_MEMORY_BUTTON = "agent_chat_save_long_term_memory_button"
}

private fun String.shouldCollapse(): Boolean =
    lineSequence().count() > COLLAPSED_MESSAGE_BODY_LINES || length > COLLAPSED_MESSAGE_CHAR_LIMIT

private const val COLLAPSED_MESSAGE_BODY_LINES = 4
private const val COLLAPSED_ARTIFACT_LINES = 3
private const val COLLAPSED_PROFILE_COMPARE_LINES = 3
private const val COLLAPSED_MESSAGE_CHAR_LIMIT = 220
private const val INPUT_MAX_LINES = 3
private const val PROFILE_MAX_LINES = 8
private const val TASK_CONTEXT_MAX_LINES = 8
private const val LONG_TERM_MEMORY_MAX_LINES = 10

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
