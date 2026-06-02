package com.akhavanskii.aichallenge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import com.akhavanskii.aichallenge.feature.home.HomeRoute
import kotlinx.serialization.Serializable

@Serializable
data object HomeDestination : NavKey

@Composable
fun AIChallengeApp(modifier: Modifier = Modifier) {
    val backStack = remember { mutableStateListOf<NavKey>(HomeDestination) }

    NavDisplay(
        modifier = modifier,
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = { key -> NavEntry(key) { HomeRoute() } },
    )
}
