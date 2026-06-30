package com.akhavanskii.aichallenge

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.akhavanskii.aichallenge.feature.home.HomeTags
import com.akhavanskii.aichallenge.feature.ragindexing.RagIndexingTags
import org.junit.Rule
import org.junit.Test

class AIChallengeAppNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun tappingRagIndexButtonOpensRagIndexingScreen() {
        composeRule
            .onNodeWithTag(HomeTags.RAG_INDEX_BUTTON)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithTag(RagIndexingTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithText("RAG Indexing").assertIsDisplayed()
    }
}
