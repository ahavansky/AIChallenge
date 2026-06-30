package com.akhavanskii.aichallenge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import com.akhavanskii.aichallenge.feature.agentchat.AgentChatRoute
import com.akhavanskii.aichallenge.feature.contextagent.ContextAgentRoute
import com.akhavanskii.aichallenge.feature.home.HomeRoute
import com.akhavanskii.aichallenge.feature.huggingfacelab.HuggingFaceLabRoute
import com.akhavanskii.aichallenge.feature.promptlab.PromptLabRoute
import com.akhavanskii.aichallenge.feature.ragindexing.RagIndexingRoute
import com.akhavanskii.aichallenge.feature.temperaturelab.TemperatureLabRoute
import kotlinx.serialization.Serializable

@Serializable
data object HomeDestination : NavKey

@Serializable
data object AgentChatDestination : NavKey

@Serializable
data object ContextAgentDestination : NavKey

@Serializable
data object PromptLabDestination : NavKey

@Serializable
data object TemperatureLabDestination : NavKey

@Serializable
data object HuggingFaceLabDestination : NavKey

@Serializable
data object RagIndexingDestination : NavKey

@Composable
fun AIChallengeApp(modifier: Modifier = Modifier) {
    val backStack = remember { mutableStateListOf<NavKey>(HomeDestination) }

    NavDisplay(
        modifier = modifier,
        backStack = backStack,
        onBack = {
            if (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
        },
        entryProvider = { key ->
            when (key) {
                HomeDestination ->
                    NavEntry(key) {
                        HomeRoute(
                            onOpenAgentChat = { backStack.add(AgentChatDestination) },
                            onOpenContextAgent = { backStack.add(ContextAgentDestination) },
                            onOpenPromptLab = { backStack.add(PromptLabDestination) },
                            onOpenTemperatureLab = { backStack.add(TemperatureLabDestination) },
                            onOpenHuggingFaceLab = { backStack.add(HuggingFaceLabDestination) },
                            onOpenRagIndexing = { backStack.add(RagIndexingDestination) },
                        )
                    }
                AgentChatDestination ->
                    NavEntry(key) {
                        AgentChatRoute(
                            onBack = {
                                if (backStack.size > 1) {
                                    backStack.removeLastOrNull()
                                }
                            },
                        )
                    }
                ContextAgentDestination ->
                    NavEntry(key) {
                        ContextAgentRoute(
                            onBack = {
                                if (backStack.size > 1) {
                                    backStack.removeLastOrNull()
                                }
                            },
                        )
                    }
                PromptLabDestination ->
                    NavEntry(key) {
                        PromptLabRoute(
                            onBack = {
                                if (backStack.size > 1) {
                                    backStack.removeLastOrNull()
                                }
                            },
                        )
                    }
                TemperatureLabDestination ->
                    NavEntry(key) {
                        TemperatureLabRoute(
                            onBack = {
                                if (backStack.size > 1) {
                                    backStack.removeLastOrNull()
                                }
                            },
                        )
                    }
                HuggingFaceLabDestination ->
                    NavEntry(key) {
                        HuggingFaceLabRoute(
                            onBack = {
                                if (backStack.size > 1) {
                                    backStack.removeLastOrNull()
                                }
                            },
                        )
                    }
                RagIndexingDestination ->
                    NavEntry(key) {
                        RagIndexingRoute(
                            onBack = {
                                if (backStack.size > 1) {
                                    backStack.removeLastOrNull()
                                }
                            },
                        )
                    }
                else ->
                    NavEntry(key) {
                        HomeRoute(
                            onOpenAgentChat = {},
                            onOpenContextAgent = {},
                            onOpenPromptLab = {},
                            onOpenTemperatureLab = {},
                            onOpenHuggingFaceLab = {},
                            onOpenRagIndexing = {},
                        )
                    }
            }
        },
    )
}
