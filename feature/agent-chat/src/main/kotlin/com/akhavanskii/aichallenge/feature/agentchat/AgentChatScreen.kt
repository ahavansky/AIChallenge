package com.akhavanskii.aichallenge.feature.agentchat

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.focus.onFocusChanged
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
    var isInlineEditorFocused by remember { mutableStateOf(false) }
    val shouldHideComposer = isInlineEditorFocused && WindowInsets.isImeVisible

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .safeDrawingPadding()
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
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AgentChatModelMenu(
                    selectedModel = state.selectedModel,
                    enabled = !state.isLoading,
                    onSelected = { onAction(AgentChatAction.ModelChanged(it)) },
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
                onInlineEditorFocusChanged = { isInlineEditorFocused = it },
                modifier = Modifier.fillMaxWidth(),
            )

            InvariantGuardPanel(
                invariants = state.invariants,
                invariantsInput = state.invariantsInput,
                lastCheck = state.lastInvariantCheck,
                isInvariantsDirty = state.isInvariantsDirty,
                enabled = !state.isLoading,
                canSaveInvariants = state.canSaveInvariants,
                onInvariantsChanged = { onAction(AgentChatAction.InvariantsChanged(it)) },
                onSaveInvariants = { onAction(AgentChatAction.SaveInvariants) },
                onUseSafeAlternative = { onAction(AgentChatAction.InputChanged(it)) },
                onInlineEditorFocusChanged = { isInlineEditorFocused = it },
                modifier = Modifier.fillMaxWidth(),
            )

            TaskStatePanel(
                taskState = state.memory.taskState,
                canPauseTask = state.canPauseTask,
                canResumeTask = state.canResumeTask,
                canRetryTask = state.canRetryTask,
                canApprovePlan = state.canApprovePlan,
                canRequestPlanRevision = state.canRequestPlanRevision,
                canAcceptValidation = state.canAcceptValidation,
                canRequestExecutionRevision = state.canRequestExecutionRevision,
                canResetTask = state.canResetTask,
                onPauseTask = { onAction(AgentChatAction.PauseTask) },
                onResumeTask = { onAction(AgentChatAction.ResumeTask) },
                onRetryTask = { onAction(AgentChatAction.RetryTask) },
                onApprovePlan = { onAction(AgentChatAction.ApprovePlan) },
                onRequestPlanRevision = { onAction(AgentChatAction.RequestPlanRevision) },
                onAcceptValidation = { onAction(AgentChatAction.AcceptValidation) },
                onRequestExecutionRevision = { onAction(AgentChatAction.RequestExecutionRevision) },
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

        if (!shouldHideComposer) {
            OutlinedTextField(
                value = state.input,
                onValueChange = { onAction(AgentChatAction.InputChanged(it)) },
                enabled = !state.isLoading,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 68.dp, max = 112.dp)
                        .testTag(AgentChatTags.INPUT)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                isInlineEditorFocused = false
                            }
                        },
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskStatePanel(
    taskState: AgentTaskState,
    canPauseTask: Boolean,
    canResumeTask: Boolean,
    canRetryTask: Boolean,
    canApprovePlan: Boolean,
    canRequestPlanRevision: Boolean,
    canAcceptValidation: Boolean,
    canRequestExecutionRevision: Boolean,
    canResetTask: Boolean,
    onPauseTask: () -> Unit,
    onResumeTask: () -> Unit,
    onRetryTask: () -> Unit,
    onApprovePlan: () -> Unit,
    onRequestPlanRevision: () -> Unit,
    onAcceptValidation: () -> Unit,
    onRequestExecutionRevision: () -> Unit,
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
                    onClick = onApprovePlan,
                    enabled = canApprovePlan,
                    modifier = Modifier.testTag(AgentChatTags.APPROVE_PLAN_BUTTON),
                ) {
                    Text("Approve plan")
                }
                TextButton(
                    onClick = onRequestPlanRevision,
                    enabled = canRequestPlanRevision,
                    modifier = Modifier.testTag(AgentChatTags.REQUEST_PLAN_REVISION_BUTTON),
                ) {
                    Text("Revise plan")
                }
                TextButton(
                    onClick = onAcceptValidation,
                    enabled = canAcceptValidation,
                    modifier = Modifier.testTag(AgentChatTags.ACCEPT_VALIDATION_BUTTON),
                ) {
                    Text("Accept validation")
                }
                TextButton(
                    onClick = onRequestExecutionRevision,
                    enabled = canRequestExecutionRevision,
                    modifier = Modifier.testTag(AgentChatTags.REQUEST_EXECUTION_REVISION_BUTTON),
                ) {
                    Text("Revise draft")
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
                val expandedArtifacts = remember(taskState.taskId) { mutableStateOf(setOf<Int>()) }
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(AgentChatTags.TASK_ARTIFACTS),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    taskState.artifacts.forEachIndexed { index, artifact ->
                        val isExpanded = index in expandedArtifacts.value
                        Surface(
                            onClick = {
                                expandedArtifacts.value =
                                    if (isExpanded) {
                                        expandedArtifacts.value - index
                                    } else {
                                        expandedArtifacts.value + index
                                    }
                            },
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
                                    maxLines = if (isExpanded) Int.MAX_VALUE else COLLAPSED_ARTIFACT_LINES,
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
    onInlineEditorFocusChanged: (Boolean) -> Unit,
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
                    text =
                        memory.formatDebugDetails(
                            chatMessages = messages,
                            activeProfile = activeProfile,
                        ),
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
                                .onFocusChanged { onInlineEditorFocusChanged(it.isFocused) }
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
                                .onFocusChanged { onInlineEditorFocusChanged(it.isFocused) }
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
                                .onFocusChanged { onInlineEditorFocusChanged(it.isFocused) }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InvariantGuardPanel(
    invariants: AgentChatInvariantSet,
    invariantsInput: String,
    lastCheck: AgentChatInvariantCheckSnapshot,
    isInvariantsDirty: Boolean,
    enabled: Boolean,
    canSaveInvariants: Boolean,
    onInvariantsChanged: (String) -> Unit,
    onSaveInvariants: () -> Unit,
    onUseSafeAlternative: (String) -> Unit,
    onInlineEditorFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isExpanded by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(InvariantGuardTab.RULES) }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(AgentChatTags.INVARIANT_GUARD),
        shape = RoundedCornerShape(8.dp),
        color = lastCheck.status.containerColor(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Invariant Guard",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = lastCheck.status.contentColor(),
                )
                Text(
                    text = " · ",
                    style = MaterialTheme.typography.labelSmall,
                    color = lastCheck.status.contentColor(),
                )
                Text(
                    text =
                        buildString {
                            append("${invariants.invariants.size} rules")
                            append(" · ${invariants.hardCount} hard")
                            append(if (isInvariantsDirty) " · Unsaved" else " · Saved")
                            append(" · Last: ${lastCheck.status.title}")
                        },
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(end = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = lastCheck.status.contentColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (isExpanded) "Hide" else "Details",
                    modifier =
                        Modifier
                            .clickable { isExpanded = !isExpanded }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                            .testTag(AgentChatTags.INVARIANT_GUARD_TOGGLE),
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
                    InvariantGuardTab.entries.forEach { tab ->
                        TextButton(
                            onClick = { selectedTab = tab },
                            modifier = Modifier.testTag("${AgentChatTags.INVARIANT_TAB_PREFIX}_${tab.name.lowercase()}"),
                        ) {
                            Text(
                                text = tab.title,
                                fontWeight = if (selectedTab == tab) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }

                when (selectedTab) {
                    InvariantGuardTab.RULES ->
                        InvariantRulesTab(
                            invariants = invariants,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    InvariantGuardTab.TEST ->
                        InvariantTestTab(
                            invariants = invariants,
                            enabled = enabled,
                            onInlineEditorFocusChanged = onInlineEditorFocusChanged,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    InvariantGuardTab.EDITOR ->
                        InvariantEditorTab(
                            invariants = invariants,
                            invariantsInput = invariantsInput,
                            isInvariantsDirty = isInvariantsDirty,
                            enabled = enabled,
                            canSaveInvariants = canSaveInvariants,
                            onInvariantsChanged = onInvariantsChanged,
                            onSaveInvariants = onSaveInvariants,
                            onInlineEditorFocusChanged = onInlineEditorFocusChanged,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    InvariantGuardTab.LAST_CHECK ->
                        InvariantLastCheckTab(
                            lastCheck = lastCheck,
                            onUseSafeAlternative = onUseSafeAlternative,
                            modifier = Modifier.fillMaxWidth(),
                        )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InvariantRulesTab(
    invariants: AgentChatInvariantSet,
    modifier: Modifier = Modifier,
) {
    val report = invariants.parseReport
    Column(
        modifier = modifier.testTag(AgentChatTags.INVARIANT_RULES),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "${report.invariants.size} valid · ${report.ignoredCount} ignored",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (report.invariants.isEmpty()) {
            Text(
                text = "No valid invariant rules.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        report.invariants.forEach { invariant ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = invariant.title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = " · ${invariant.severity.name.lowercase()} · ${invariant.type.ifBlank { "general" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "Rule: ${invariant.rule}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (invariant.reject.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        invariant.reject.forEach { rejected ->
                            InvariantChip(text = "Reject: $rejected")
                        }
                    }
                }
                if (invariant.alternative.isNotBlank()) {
                    Text(
                        text = "Alternative: ${invariant.alternative}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        report.ignoredBlocks.forEach { ignored ->
            Text(
                text = "Ignored block: ${ignored.title}. Missing: ${ignored.missingFields.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun InvariantTestTab(
    invariants: AgentChatInvariantSet,
    enabled: Boolean,
    onInlineEditorFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var testInput by remember { mutableStateOf("") }
    var testResult by remember(invariants.markdown) { mutableStateOf<AgentChatInvariantCheckResult?>(null) }

    Column(
        modifier = modifier.testTag(AgentChatTags.INVARIANT_TEST),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = testInput,
            onValueChange = {
                testInput = it
                testResult = null
            },
            enabled = enabled,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onFocusChanged { onInlineEditorFocusChanged(it.isFocused) }
                    .testTag(AgentChatTags.INVARIANT_TEST_INPUT),
            label = { Text("Test request", style = MaterialTheme.typography.labelSmall) },
            minLines = 2,
            maxLines = 3,
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(8.dp),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
        )
        ChallengeButton(
            onClick = { testResult = AgentChatInvariantChecker.check(testInput, invariants) },
            enabled = enabled && testInput.isNotBlank(),
            modifier = Modifier.testTag(AgentChatTags.INVARIANT_TEST_BUTTON),
        ) {
            Text("Check")
        }
        testResult?.let { result ->
            InvariantCheckResultDetails(
                title = if (result.hasHardViolations) "Blocked" else "Passed",
                violation = result.hardViolations.firstOrNull(),
            )
        }
    }
}

@Composable
private fun InvariantEditorTab(
    invariants: AgentChatInvariantSet,
    invariantsInput: String,
    isInvariantsDirty: Boolean,
    enabled: Boolean,
    canSaveInvariants: Boolean,
    onInvariantsChanged: (String) -> Unit,
    onSaveInvariants: () -> Unit,
    onInlineEditorFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val previewSet = invariants.copy(markdown = invariantsInput)
    val report = previewSet.parseReport
    Column(
        modifier = modifier.testTag(AgentChatTags.INVARIANT_EDITOR),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "${report.invariants.size} valid · ${report.ignoredCount} ignored",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = invariantsInput,
            onValueChange = onInvariantsChanged,
            enabled = enabled,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 132.dp)
                    .onFocusChanged { onInlineEditorFocusChanged(it.isFocused) }
                    .testTag(AgentChatTags.INVARIANTS_INPUT),
            label = {
                Text(
                    text = invariants.fileName,
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            minLines = 5,
            maxLines = INVARIANTS_MAX_LINES,
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(8.dp),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChallengeButton(
                onClick = onSaveInvariants,
                enabled = canSaveInvariants,
                modifier = Modifier.testTag(AgentChatTags.SAVE_INVARIANTS_BUTTON),
            ) {
                Text("Save invariants.md")
            }
            Text(
                text = if (isInvariantsDirty) "Unsaved" else "Saved",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        report.ignoredBlocks.forEach { ignored ->
            Text(
                text = "Ignored block: ${ignored.title}. Missing: ${ignored.missingFields.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun InvariantLastCheckTab(
    lastCheck: AgentChatInvariantCheckSnapshot,
    onUseSafeAlternative: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.testTag(AgentChatTags.INVARIANT_LAST_CHECK),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Status: ${lastCheck.status.title}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Stage: ${lastCheck.stage.title}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InvariantCheckLine("Prompt layer", if (lastCheck.promptLayerIncluded) "included" else "not included")
        InvariantCheckLine("Repair", if (lastCheck.repairAttempted) "attempted" else "not attempted")
        InvariantCheckLine("Artifact stored", if (lastCheck.artifactStored) "yes" else "no")
        if (lastCheck.invariantTitle.isNotBlank()) {
            InvariantCheckResultDetails(
                title = "Violated invariant",
                violation = lastCheck.toViolationOrNull(),
            )
        }
        if (lastCheck.safeAlternativePrompt.isNotBlank()) {
            TextButton(
                onClick = { onUseSafeAlternative(lastCheck.safeAlternativePrompt) },
                modifier = Modifier.testTag(AgentChatTags.INVARIANT_SAFE_ALTERNATIVE_BUTTON),
            ) {
                Text("Use safe alternative")
            }
        }
    }
}

@Composable
private fun InvariantCheckLine(
    label: String,
    value: String,
) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InvariantCheckResultDetails(
    title: String,
    violation: AgentChatInvariantViolation?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (violation == null) {
            Text(
                text = "No hard invariant conflicts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "Invariant: ${violation.invariant.title}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Conflict: ${violation.matchedText}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (violation.invariant.reason.isNotBlank()) {
                Text(
                    text = "Reason: ${violation.invariant.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (violation.invariant.alternative.isNotBlank()) {
                Text(
                    text = "Alternative: ${violation.invariant.alternative}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InvariantChip(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun AgentChatInvariantCheckSnapshot.toViolationOrNull(): AgentChatInvariantViolation? =
    invariantTitle
        .takeIf { it.isNotBlank() }
        ?.let { title ->
            AgentChatInvariantViolation(
                invariant =
                    AgentChatInvariant(
                        title = title,
                        rule = reason.ifBlank { title },
                        reason = reason,
                        alternative = alternative,
                    ),
                matchedText = conflict,
            )
        }

@Composable
private fun AgentChatInvariantCheckStatus.containerColor() =
    when (this) {
        AgentChatInvariantCheckStatus.NOT_RUN -> MaterialTheme.colorScheme.surfaceContainer
        AgentChatInvariantCheckStatus.PASSED -> MaterialTheme.colorScheme.tertiaryContainer
        AgentChatInvariantCheckStatus.BLOCKED -> MaterialTheme.colorScheme.errorContainer
        AgentChatInvariantCheckStatus.REPAIRED -> MaterialTheme.colorScheme.secondaryContainer
        AgentChatInvariantCheckStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
    }

@Composable
private fun AgentChatInvariantCheckStatus.contentColor() =
    when (this) {
        AgentChatInvariantCheckStatus.NOT_RUN -> MaterialTheme.colorScheme.onSurfaceVariant
        AgentChatInvariantCheckStatus.PASSED -> MaterialTheme.colorScheme.onTertiaryContainer
        AgentChatInvariantCheckStatus.BLOCKED -> MaterialTheme.colorScheme.onErrorContainer
        AgentChatInvariantCheckStatus.REPAIRED -> MaterialTheme.colorScheme.onSecondaryContainer
        AgentChatInvariantCheckStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
    }

private enum class InvariantGuardTab(
    val title: String,
) {
    RULES("Rules"),
    TEST("Test"),
    EDITOR("Editor"),
    LAST_CHECK("Last check"),
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

@Composable
private fun AgentChatModelMenu(
    selectedModel: AgentChatModelOption,
    enabled: Boolean,
    onSelected: (AgentChatModelOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    TextButton(
        onClick = { expanded = true },
        enabled = enabled,
        modifier =
            modifier
                .width(156.dp)
                .testTag(AgentChatTags.MODEL_MENU_BUTTON),
    ) {
        Text(
            text = "Model: ${selectedModel.compactTitle}",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
    ) {
        AgentChatModelOption.entries.forEach { model ->
            DropdownMenuItem(
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(model.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = model.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = {
                    expanded = false
                    onSelected(model)
                },
                modifier = Modifier.testTag("${AgentChatTags.MODEL_PREFIX}_${model.name}"),
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
    const val MODEL_MENU_BUTTON = "agent_chat_model_menu_button"
    const val MODEL_PREFIX = "agent_chat_model"
    const val RUN_TASK_BUTTON = "agent_chat_run_task_button"
    const val CLEAR_BUTTON = "agent_chat_clear_button"
    const val STOP_BUTTON = "agent_chat_stop_button"
    const val TASK_STATE = "agent_chat_task_state"
    const val PAUSE_TASK_BUTTON = "agent_chat_pause_task_button"
    const val RESUME_TASK_BUTTON = "agent_chat_resume_task_button"
    const val RETRY_TASK_BUTTON = "agent_chat_retry_task_button"
    const val APPROVE_PLAN_BUTTON = "agent_chat_approve_plan_button"
    const val REQUEST_PLAN_REVISION_BUTTON = "agent_chat_request_plan_revision_button"
    const val ACCEPT_VALIDATION_BUTTON = "agent_chat_accept_validation_button"
    const val REQUEST_EXECUTION_REVISION_BUTTON = "agent_chat_request_execution_revision_button"
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
    const val INVARIANT_GUARD = "agent_chat_invariant_guard"
    const val INVARIANT_GUARD_TOGGLE = "agent_chat_invariant_guard_toggle"
    const val INVARIANT_TAB_PREFIX = "agent_chat_invariant_tab"
    const val INVARIANT_RULES = "agent_chat_invariant_rules"
    const val INVARIANT_TEST = "agent_chat_invariant_test"
    const val INVARIANT_TEST_INPUT = "agent_chat_invariant_test_input"
    const val INVARIANT_TEST_BUTTON = "agent_chat_invariant_test_button"
    const val INVARIANT_EDITOR = "agent_chat_invariant_editor"
    const val INVARIANT_LAST_CHECK = "agent_chat_invariant_last_check"
    const val INVARIANT_SAFE_ALTERNATIVE_BUTTON = "agent_chat_invariant_safe_alternative_button"
    const val INVARIANTS_EDITOR_TOGGLE = "agent_chat_invariants_editor_toggle"
    const val INVARIANTS_INPUT = "agent_chat_invariants_input"
    const val SAVE_INVARIANTS_BUTTON = "agent_chat_save_invariants_button"
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
private const val INVARIANTS_MAX_LINES = 10
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
