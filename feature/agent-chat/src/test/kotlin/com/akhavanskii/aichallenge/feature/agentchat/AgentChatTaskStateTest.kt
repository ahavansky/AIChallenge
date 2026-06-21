package com.akhavanskii.aichallenge.feature.agentchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChatTaskStateTest {
    @Test
    fun startCreatesParallelPlanningTask() {
        val result =
            AgentTaskStateMachine.reduce(
                state = AgentTaskState(),
                event = AgentTaskEvent.Start(taskId = "task-1", prompt = "Build feature"),
            )

        assertTrue(result.isAccepted)
        assertEquals(AgentTaskStage.PLANNING, result.state.stage)
        assertEquals(AgentTaskStep.PARALLEL_ANALYSIS, result.state.step)
        assertEquals(AgentTaskStatus.RUNNING, result.state.status)
        assertEquals("Build feature", result.state.originalPrompt)
        assertEquals(specialistBranchIds, result.state.branches.map { it.id })
        assertTrue(result.state.branches.all { it.status == AgentTaskBranchStatus.RUNNING })
    }

    @Test
    fun pipelineMovesThroughApprovalGatedStages() {
        val started = startTask()
        val planned = completeSpecialists(started, specialistArtifacts("planning"))
        val waitingForPlanApproval =
            AgentTaskStateMachine
                .reduce(
                    state = planned,
                    event = AgentTaskEvent.StepSucceeded(AgentTaskArtifact(AgentTaskArtifactType.TASK_SPEC, "Spec")),
                ).state
        val executionStarted = AgentTaskStateMachine.reduce(waitingForPlanApproval, AgentTaskEvent.ApprovePlan).state
        val executionReady = completeSpecialists(executionStarted, specialistArtifacts("execution"))
        val draftReady =
            AgentTaskStateMachine
                .reduce(
                    state = executionReady,
                    event = AgentTaskEvent.StepSucceeded(AgentTaskArtifact(AgentTaskArtifactType.EXECUTION_DRAFT, "Draft")),
                ).state
        val validationReady = completeSpecialists(draftReady, specialistArtifacts("validation"))
        val waitingForValidationApproval =
            AgentTaskStateMachine
                .reduce(
                    state = validationReady,
                    event =
                        AgentTaskEvent.StepSucceeded(
                            AgentTaskArtifact(
                                AgentTaskArtifactType.VALIDATION_REPORT,
                                "Validation outcome: PASS\nValid",
                            ),
                        ),
                ).state
        val finalizationStarted = AgentTaskStateMachine.reduce(waitingForValidationApproval, AgentTaskEvent.AcceptValidation).state
        val finalizationReady = completeSpecialists(finalizationStarted, specialistArtifacts("finalization"))
        val done =
            AgentTaskStateMachine
                .reduce(
                    state = finalizationReady,
                    event = AgentTaskEvent.StepSucceeded(AgentTaskArtifact(AgentTaskArtifactType.FINAL_ANSWER, "Final")),
                ).state

        assertEquals(AgentTaskStage.PLANNING, planned.stage)
        assertEquals(AgentTaskStep.SYNTHESIZE_TASK_SPEC, planned.step)
        assertEquals(AgentTaskStatus.WAITING_FOR_USER, waitingForPlanApproval.status)
        assertEquals(AgentTaskWaitingReason.PLAN_APPROVAL, waitingForPlanApproval.waitingReason)
        assertEquals(AgentTaskStage.EXECUTION, executionStarted.stage)
        assertEquals(AgentTaskStep.PARALLEL_ANALYSIS, executionStarted.step)
        assertEquals(AgentTaskStep.CREATE_DRAFT, executionReady.step)
        assertEquals(AgentTaskStage.VALIDATION, draftReady.stage)
        assertEquals(AgentTaskStep.PARALLEL_ANALYSIS, draftReady.step)
        assertEquals(AgentTaskStep.VALIDATE_DRAFT, validationReady.step)
        assertEquals(AgentTaskStatus.WAITING_FOR_USER, waitingForValidationApproval.status)
        assertEquals(AgentValidationOutcome.PASS, waitingForValidationApproval.validationOutcome)
        assertEquals(AgentTaskStage.DONE, finalizationStarted.stage)
        assertEquals(AgentTaskStep.PARALLEL_ANALYSIS, finalizationStarted.step)
        assertEquals(AgentTaskStep.FINALIZE_ANSWER, finalizationReady.step)
        assertEquals(AgentTaskStatus.DONE, done.status)
        assertEquals("Final", done.finalAnswer)
    }

    @Test
    fun invalidSequentialArtifactDoesNotChangeParallelState() {
        val started = startTask()

        val result =
            AgentTaskStateMachine.reduce(
                state = started,
                event =
                    AgentTaskEvent.StepSucceeded(
                        AgentTaskArtifact(
                            AgentTaskArtifactType.FINAL_ANSWER,
                            "Too early",
                        ),
                    ),
            )

        assertFalse(result.isAccepted)
        assertEquals(started, result.state)
    }

    @Test
    fun planRevisionRestartsPlanningSpecialists() {
        val waitingForPlanApproval =
            AgentTaskStateMachine
                .reduce(
                    state = completeSpecialists(startTask(), specialistArtifacts("planning")),
                    event = AgentTaskEvent.StepSucceeded(AgentTaskArtifact(AgentTaskArtifactType.TASK_SPEC, "Spec")),
                ).state

        val result = AgentTaskStateMachine.reduce(waitingForPlanApproval, AgentTaskEvent.RequestPlanRevision)

        assertTrue(result.isAccepted)
        assertEquals(AgentTaskStage.PLANNING, result.state.stage)
        assertEquals(AgentTaskStep.PARALLEL_ANALYSIS, result.state.step)
        assertEquals(AgentTaskStatus.RUNNING, result.state.status)
        assertEquals(AgentTaskWaitingReason.NONE, result.state.waitingReason)
        assertEquals(specialistBranchIds, result.state.branches.map { it.id })
        assertTrue(result.state.branches.all { it.status == AgentTaskBranchStatus.RUNNING })
    }

    @Test
    fun executionCannotStartBeforePlanApproval() {
        val waitingForPlanApproval =
            AgentTaskStateMachine
                .reduce(
                    state = completeSpecialists(startTask(), specialistArtifacts("planning")),
                    event = AgentTaskEvent.StepSucceeded(AgentTaskArtifact(AgentTaskArtifactType.TASK_SPEC, "Spec")),
                ).state

        val result =
            AgentTaskStateMachine.reduce(
                state = waitingForPlanApproval,
                event = AgentTaskEvent.StepSucceeded(AgentTaskArtifact(AgentTaskArtifactType.EXECUTION_DRAFT, "Draft")),
            )

        assertFalse(result.isAccepted)
        assertEquals(waitingForPlanApproval, result.state)
    }

    @Test
    fun finalAnswerCannotStartBeforeValidationPassIsAccepted() {
        val waitingForValidationApproval =
            AgentTaskState(
                taskId = "task-1",
                originalPrompt = "Build feature",
                stage = AgentTaskStage.VALIDATION,
                step = AgentTaskStep.APPROVE_VALIDATION,
                status = AgentTaskStatus.WAITING_FOR_USER,
                waitingReason = AgentTaskWaitingReason.VALIDATION_APPROVAL,
                validationOutcome = AgentValidationOutcome.NEEDS_REVISION,
                artifacts =
                    listOf(
                        AgentTaskArtifact(AgentTaskArtifactType.TASK_SPEC, "Spec"),
                        AgentTaskArtifact(AgentTaskArtifactType.EXECUTION_DRAFT, "Draft"),
                        AgentTaskArtifact(AgentTaskArtifactType.VALIDATION_REPORT, "Validation outcome: NEEDS_REVISION"),
                    ),
            )

        val accepted = AgentTaskStateMachine.reduce(waitingForValidationApproval, AgentTaskEvent.AcceptValidation)
        val finalized =
            AgentTaskStateMachine.reduce(
                state = waitingForValidationApproval,
                event = AgentTaskEvent.StepSucceeded(AgentTaskArtifact(AgentTaskArtifactType.FINAL_ANSWER, "Final")),
            )

        assertFalse(accepted.isAccepted)
        assertFalse(finalized.isAccepted)
        assertEquals(waitingForValidationApproval, accepted.state)
        assertEquals(waitingForValidationApproval, finalized.state)
    }

    @Test
    fun failedPlanningBranchCanRetryWithoutLosingSuccessfulArtifact() {
        val failed =
            AgentTaskStateMachine
                .reduce(
                    state = startTask(),
                    event =
                        AgentTaskEvent.ParallelBranchesFinished(
                            successfulArtifacts =
                                listOf(
                                    AgentTaskBranchArtifact(
                                        AgentTaskBranchId.INTENT,
                                        AgentTaskArtifact(AgentTaskArtifactType.INTENT_REPORT, "Intent"),
                                    ),
                                ),
                            failures =
                                listOf(
                                    AgentTaskBranchFailure(
                                        AgentTaskBranchId.REVIEW,
                                        "Review failed",
                                    ),
                                ),
                        ),
                ).state
        val retried = AgentTaskStateMachine.reduce(failed, AgentTaskEvent.Retry).state

        assertEquals(AgentTaskStatus.FAILED, failed.status)
        assertEquals(AgentTaskBranchStatus.DONE, failed.branches.first { it.id == AgentTaskBranchId.INTENT }.status)
        assertEquals(AgentTaskBranchStatus.FAILED, failed.branches.first { it.id == AgentTaskBranchId.REVIEW }.status)
        assertEquals("Intent", failed.artifact(AgentTaskArtifactType.INTENT_REPORT)?.text)
        assertEquals(AgentTaskStatus.RUNNING, retried.status)
        assertEquals(AgentTaskBranchStatus.DONE, retried.branches.first { it.id == AgentTaskBranchId.INTENT }.status)
        assertEquals(AgentTaskBranchStatus.RUNNING, retried.branches.first { it.id == AgentTaskBranchId.REVIEW }.status)
    }

    @Test
    fun duplicateBranchCompletionIsRejected() {
        val started = startTask()

        val result =
            AgentTaskStateMachine.reduce(
                state = started,
                event =
                    AgentTaskEvent.ParallelBranchesFinished(
                        successfulArtifacts =
                            listOf(
                                AgentTaskBranchArtifact(
                                    AgentTaskBranchId.INTENT,
                                    AgentTaskArtifact(AgentTaskArtifactType.INTENT_REPORT, "First"),
                                ),
                                AgentTaskBranchArtifact(
                                    AgentTaskBranchId.INTENT,
                                    AgentTaskArtifact(AgentTaskArtifactType.INTENT_REPORT, "Second"),
                                ),
                            ),
                    ),
            )

        assertFalse(result.isAccepted)
        assertEquals(started, result.state)
    }

    @Test
    fun pauseAndResumeKeepCurrentParallelBranches() {
        val started = startTask()

        val paused = AgentTaskStateMachine.reduce(started, AgentTaskEvent.Pause).state
        val resumed = AgentTaskStateMachine.reduce(paused, AgentTaskEvent.Resume).state

        assertEquals(AgentTaskStatus.PAUSED, paused.status)
        assertEquals(AgentTaskStep.PARALLEL_ANALYSIS, paused.step)
        assertTrue(paused.branches.all { it.status == AgentTaskBranchStatus.PAUSED })
        assertEquals(AgentTaskStatus.RUNNING, resumed.status)
        assertEquals(AgentTaskStep.PARALLEL_ANALYSIS, resumed.step)
        assertTrue(resumed.branches.all { it.status == AgentTaskBranchStatus.RUNNING })
    }

    private fun startTask(): AgentTaskState =
        AgentTaskStateMachine
            .reduce(
                state = AgentTaskState(),
                event = AgentTaskEvent.Start(taskId = "task-1", prompt = "Build feature"),
            ).state

    private fun completeSpecialists(
        state: AgentTaskState,
        artifacts: List<AgentTaskBranchArtifact>,
    ): AgentTaskState =
        AgentTaskStateMachine
            .reduce(
                state = state,
                event = AgentTaskEvent.ParallelBranchesFinished(successfulArtifacts = artifacts),
            ).state

    private fun specialistArtifacts(prefix: String): List<AgentTaskBranchArtifact> =
        listOf(
            AgentTaskBranchArtifact(
                AgentTaskBranchId.INTENT,
                AgentTaskArtifact(AgentTaskArtifactType.INTENT_REPORT, "$prefix intent"),
            ),
            AgentTaskBranchArtifact(
                AgentTaskBranchId.CONSTRAINTS,
                AgentTaskArtifact(AgentTaskArtifactType.CONSTRAINTS_REPORT, "$prefix constraints"),
            ),
            AgentTaskBranchArtifact(
                AgentTaskBranchId.CONTEXT,
                AgentTaskArtifact(AgentTaskArtifactType.CONTEXT_REPORT, "$prefix context"),
            ),
            AgentTaskBranchArtifact(
                AgentTaskBranchId.SOLUTION,
                AgentTaskArtifact(AgentTaskArtifactType.SOLUTION_REPORT, "$prefix solution"),
            ),
            AgentTaskBranchArtifact(
                AgentTaskBranchId.REVIEW,
                AgentTaskArtifact(AgentTaskArtifactType.REVIEW_REPORT, "$prefix review"),
            ),
        )

    private val specialistBranchIds =
        listOf(
            AgentTaskBranchId.INTENT,
            AgentTaskBranchId.CONSTRAINTS,
            AgentTaskBranchId.CONTEXT,
            AgentTaskBranchId.SOLUTION,
            AgentTaskBranchId.REVIEW,
        )
}
