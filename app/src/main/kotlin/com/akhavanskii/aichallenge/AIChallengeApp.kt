package com.akhavanskii.aichallenge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import com.akhavanskii.aichallenge.feature.home.HomeRoute
import com.akhavanskii.aichallenge.feature.huggingfacelab.HuggingFaceLabRoute
import com.akhavanskii.aichallenge.feature.promptlab.PromptLabRoute
import com.akhavanskii.aichallenge.feature.temperaturelab.TemperatureLabRoute
import kotlinx.serialization.Serializable

@Serializable
data object HomeDestination : NavKey

@Serializable
data object PromptLabDestination : NavKey

@Serializable
data object TemperatureLabDestination : NavKey

@Serializable
data object HuggingFaceLabDestination : NavKey

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
                            onOpenPromptLab = { backStack.add(PromptLabDestination) },
                            onOpenTemperatureLab = { backStack.add(TemperatureLabDestination) },
                            onOpenHuggingFaceLab = { backStack.add(HuggingFaceLabDestination) },
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
                else ->
                    NavEntry(key) {
                        HomeRoute(
                            onOpenPromptLab = {},
                            onOpenTemperatureLab = {},
                            onOpenHuggingFaceLab = {},
                        )
                    }
            }
        },
    )
}
