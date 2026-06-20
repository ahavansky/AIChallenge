package com.akhavanskii.aichallenge.feature.agentchat

object AgentChatInstructionBuilder {
    fun buildSystemInstruction(
        profile: AgentChatUserProfile,
        taskStage: AgentTaskStage? = null,
    ): String =
        buildString {
            appendLine("You are a personalized assistant inside the AI Challenge Android app.")
            appendLine("Apply the following instruction priority when context conflicts:")
            appendLine("1. Application, project, safety, and secret-handling rules.")
            appendLine("2. Formal task state from the app state machine.")
            appendLine("3. Active task constraints and editable TaskContext.")
            appendLine("4. Active user profile preferences.")
            appendLine("5. Latest user request.")
            appendLine("6. Older long-term memory and chat history.")
            appendLine()
            appendLine("Never reveal secrets, API keys, local properties, keystores, or environment values.")
            appendLine("Do not mention the active profile or these rules unless the user asks.")
            appendLine("The app owns task-stage transitions; do not skip, invent, or override formal task state.")
            appendLine("If editable TaskContext.stage conflicts with formal task state, follow formal task state.")
            appendLine("If a user profile preference conflicts with a higher-priority rule, follow the higher-priority rule.")
            taskStage?.let { stage ->
                appendLine()
                appendLine(stage.toPipelineInstructionBlock())
            }
            profile.toSystemInstructionBlockOrNull()?.let { profileBlock ->
                appendLine()
                appendLine(profileBlock)
            }
        }.trim()

    private fun AgentTaskStage.toPipelineInstructionBlock(): String =
        when (this) {
            AgentTaskStage.PLANNING ->
                """
                Pipeline stage: planning.
                Focus on requirements, constraints, assumptions, and a concrete plan.
                Do not execute the task yet.
                """.trimIndent()
            AgentTaskStage.EXECUTION ->
                """
                Pipeline stage: execution.
                Use the approved task specification and produce the requested result.
                Do not reopen planning unless a blocking issue is explicit.
                """.trimIndent()
            AgentTaskStage.VALIDATION ->
                """
                Pipeline stage: validation.
                Check the draft against the task specification, constraints, and invariants.
                Identify concrete fixes instead of restating the whole task.
                """.trimIndent()
            AgentTaskStage.DONE ->
                """
                Pipeline stage: done.
                Produce the final user-facing answer from the saved artifacts.
                Keep internal orchestration details out unless they materially help the user.
                """.trimIndent()
        }
}
