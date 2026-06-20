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
        assertEquals(
            listOf(AgentTaskBranchStatus.RUNNING, AgentTaskBranchStatus.RUNNING),
            result.state.branches.map { it.status },
        )
    }

    @Test
    fun pipelineMovesThroughAllowedStages() {
        val started =
            AgentTaskStateMachine
                .reduce(
                    state = AgentTaskState(),
                    event = AgentTaskEvent.Start(taskId = "task-1", prompt = "Build feature"),
                ).state
        val analyzed =
            AgentTaskStateMachine
                .reduce(
                    state = started,
                    event =
                        AgentTaskEvent.ParallelBranchesFinished(
                            successfulArtifacts =
                                listOf(
                                    AgentTaskBranchArtifact(
                                        AgentTaskBranchId.REQUIREMENTS,
                                        AgentTaskArtifact(AgentTaskArtifactType.REQUIREMENTS_REPORT, "Requirements"),
                                    ),
                                    AgentTaskBranchArtifact(
                                        AgentTaskBranchId.RISKS,
                                        AgentTaskArtifact(AgentTaskArtifactType.RISKS_REPORT, "Risks"),
                                    ),
                                ),
                        ),
                ).state
        val planned =
            AgentTaskStateMachine
                .reduce(
                    state = analyzed,
                    event = AgentTaskEvent.StepSucceeded(AgentTaskArtifact(AgentTaskArtifactType.TASK_SPEC, "Spec")),
                ).state
        val executed =
            AgentTaskStateMachine
                .reduce(
                    state = planned,
                    event =
                        AgentTaskEvent.StepSucceeded(
                            AgentTaskArtifact(
                                AgentTaskArtifactType.EXECUTION_DRAFT,
                                "Draft",
                            ),
                        ),
                ).state
        val validated =
            AgentTaskStateMachine
                .reduce(
                    state = executed,
                    event =
                        AgentTaskEvent.StepSucceeded(
                            AgentTaskArtifact(
                                AgentTaskArtifactType.VALIDATION_REPORT,
                                "Valid",
                            ),
                        ),
                ).state
        val done =
            AgentTaskStateMachine
                .reduce(
                    state = validated,
                    event =
                        AgentTaskEvent.StepSucceeded(
                            AgentTaskArtifact(
                                AgentTaskArtifactType.FINAL_ANSWER,
                                "Final",
                            ),
                        ),
                ).state

        assertEquals(AgentTaskStage.PLANNING, analyzed.stage)
        assertEquals(AgentTaskStep.SYNTHESIZE_TASK_SPEC, analyzed.step)
        assertEquals(AgentTaskStage.EXECUTION, planned.stage)
        assertEquals(AgentTaskStage.VALIDATION, executed.stage)
        assertEquals(AgentTaskStage.DONE, validated.stage)
        assertEquals(AgentTaskStatus.DONE, done.status)
        assertEquals("Final", done.finalAnswer)
    }

    @Test
    fun invalidSequentialArtifactDoesNotChangeParallelState() {
        val started =
            AgentTaskStateMachine
                .reduce(
                    state = AgentTaskState(),
                    event = AgentTaskEvent.Start(taskId = "task-1", prompt = "Build feature"),
                ).state

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
    fun failedPlanningBranchCanRetryWithoutLosingSuccessfulArtifact() {
        val started =
            AgentTaskStateMachine
                .reduce(
                    state = AgentTaskState(),
                    event = AgentTaskEvent.Start(taskId = "task-1", prompt = "Build feature"),
                ).state
        val failed =
            AgentTaskStateMachine
                .reduce(
                    state = started,
                    event =
                        AgentTaskEvent.ParallelBranchesFinished(
                            successfulArtifacts =
                                listOf(
                                    AgentTaskBranchArtifact(
                                        AgentTaskBranchId.REQUIREMENTS,
                                        AgentTaskArtifact(AgentTaskArtifactType.REQUIREMENTS_REPORT, "Requirements"),
                                    ),
                                ),
                            failures =
                                listOf(
                                    AgentTaskBranchFailure(
                                        AgentTaskBranchId.RISKS,
                                        "Risks failed",
                                    ),
                                ),
                        ),
                ).state
        val retried = AgentTaskStateMachine.reduce(failed, AgentTaskEvent.Retry).state

        assertEquals(AgentTaskStatus.FAILED, failed.status)
        assertEquals(AgentTaskBranchStatus.DONE, failed.branches.first { it.id == AgentTaskBranchId.REQUIREMENTS }.status)
        assertEquals(AgentTaskBranchStatus.FAILED, failed.branches.first { it.id == AgentTaskBranchId.RISKS }.status)
        assertEquals("Requirements", failed.artifact(AgentTaskArtifactType.REQUIREMENTS_REPORT)?.text)
        assertEquals(AgentTaskStatus.RUNNING, retried.status)
        assertEquals(AgentTaskBranchStatus.DONE, retried.branches.first { it.id == AgentTaskBranchId.REQUIREMENTS }.status)
        assertEquals(AgentTaskBranchStatus.RUNNING, retried.branches.first { it.id == AgentTaskBranchId.RISKS }.status)
    }

    @Test
    fun pauseAndResumeKeepCurrentParallelBranches() {
        val started =
            AgentTaskStateMachine
                .reduce(
                    state = AgentTaskState(),
                    event = AgentTaskEvent.Start(taskId = "task-1", prompt = "Build feature"),
                ).state

        val paused = AgentTaskStateMachine.reduce(started, AgentTaskEvent.Pause).state
        val resumed = AgentTaskStateMachine.reduce(paused, AgentTaskEvent.Resume).state

        assertEquals(AgentTaskStatus.PAUSED, paused.status)
        assertEquals(AgentTaskStep.PARALLEL_ANALYSIS, paused.step)
        assertTrue(paused.branches.all { it.status == AgentTaskBranchStatus.PAUSED })
        assertEquals(AgentTaskStatus.RUNNING, resumed.status)
        assertEquals(AgentTaskStep.PARALLEL_ANALYSIS, resumed.step)
        assertTrue(resumed.branches.all { it.status == AgentTaskBranchStatus.RUNNING })
    }
}
