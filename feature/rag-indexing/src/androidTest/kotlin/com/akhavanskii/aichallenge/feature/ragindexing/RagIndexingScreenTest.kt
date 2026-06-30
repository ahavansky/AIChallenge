package com.akhavanskii.aichallenge.feature.ragindexing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.akhavanskii.aichallenge.core.designsystem.AIChallengeTheme
import org.junit.Rule
import org.junit.Test

class RagIndexingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun idleStateRendersControls() {
        var state by mutableStateOf(RagIndexingUiState())
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                RagIndexingScreen(
                    state = state,
                    onAction = { action ->
                        if (action is RagIndexingAction.QueryChanged) {
                            state = state.copy(query = action.query)
                        }
                    },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(RagIndexingTags.ENDPOINT_INPUT).assertIsDisplayed()
        composeRule.onNodeWithTag(RagIndexingTags.MODEL_INPUT).assertIsDisplayed()
        composeRule.onNodeWithTag("${RagIndexingTags.STRATEGY_PREFIX}_${RagIndexingStrategy.FIXED.name}").assertIsDisplayed()
        composeRule.onNodeWithTag(RagIndexingTags.COMPARE_BUTTON).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(RagIndexingTags.QUERY_INPUT).performScrollTo().performTextInput("emulator endpoint")
        composeRule.onNodeWithTag(RagIndexingTags.BUILD_BUTTON).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(RagIndexingTags.COMPARE_BUTTON).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(RagIndexingTags.SEARCH_BUTTON).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(RagIndexingTags.CANCEL_BUTTON).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(RagIndexingTags.OUTPUT_PATHS).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Files will appear after build or compare.").assertIsDisplayed()
    }

    @Test
    fun outputPathsRenderCompactByDefaultAndExpandFullPaths() {
        val fixedFullPath = "/data/user/0/com.akhavanskii.aichallenge/files/rag-index/fixed/index.json"
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                RagIndexingScreen(
                    state =
                        RagIndexingUiState(
                            phase = RagIndexingPhase.SUCCESS,
                            progress =
                                RagIndexingProgress(
                                    outputPaths =
                                        RagIndexingOutputPaths(
                                            fixedIndex = fixedFullPath,
                                            comparisonMarkdown =
                                                "/data/user/0/com.akhavanskii.aichallenge/files/rag-index/comparison.md",
                                        ),
                                ),
                        ),
                    onAction = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(RagIndexingTags.OUTPUT_PATHS).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Generated files").assertIsDisplayed()
        composeRule.onNodeWithTag("${RagIndexingTags.OUTPUT_PATH_PREFIX}_0").assertIsDisplayed()
        composeRule.onNodeWithText("rag-index/fixed/index.json").assertIsDisplayed()
        composeRule.onNodeWithText("rag-index/comparison.md").assertIsDisplayed()
        composeRule.onAllNodesWithText("/data/user/0", substring = true).assertCountEquals(0)

        composeRule.onNodeWithTag(RagIndexingTags.OUTPUT_PATHS_TOGGLE).performClick()

        composeRule.onNodeWithTag(RagIndexingTags.OUTPUT_PATHS_FULL).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(fixedFullPath, substring = true).assertIsDisplayed()
    }

    @Test
    fun buildingStateRendersProgressAndCancel() {
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                RagIndexingScreen(
                    state =
                        RagIndexingUiState(
                            phase = RagIndexingPhase.BUILDING,
                            progress =
                                RagIndexingProgress(
                                    currentStrategy = RagIndexingStrategy.FIXED,
                                    embedded = 3,
                                    total = 10,
                                    cachedCount = 2,
                                ),
                        ),
                    onAction = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(RagIndexingTags.BUILD_BUTTON).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(RagIndexingTags.COMPARE_BUTTON).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(RagIndexingTags.SEARCH_BUTTON).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(RagIndexingTags.CANCEL_BUTTON).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(RagIndexingTags.PROGRESS_INDICATOR).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Embedded 3/10").assertIsDisplayed()
        composeRule.onNodeWithText("Cached embeddings: 2").assertIsDisplayed()
    }

    @Test
    fun errorStateRendersMessage() {
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                RagIndexingScreen(
                    state =
                        RagIndexingUiState(
                            phase = RagIndexingPhase.ERROR,
                            userFacingError = "Ollama is not reachable.",
                        ),
                    onAction = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(RagIndexingTags.ERROR).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Ollama is not reachable.").assertIsDisplayed()
    }

    @Test
    fun comparisonStateRendersStatsAndExamples() {
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                RagIndexingScreen(
                    state =
                        RagIndexingUiState(
                            phase = RagIndexingPhase.SUCCESS,
                            comparisonSummary =
                                RagComparisonSummary(
                                    queryCount = 1,
                                    fixedChunkCount = 10,
                                    structureChunkCount = 7,
                                    fixedAverageTokens = 128.0,
                                    structureAverageTokens = 190.5,
                                    jsonPath = "/files/rag-index/comparison.json",
                                    markdownPath = "/files/rag-index/comparison.md",
                                ),
                            comparisonReport = comparisonReport(),
                        ),
                    onAction = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(RagIndexingTags.COMPARISON).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("queries=1, fixed_chunks=10, structure_chunks=7").assertIsDisplayed()
        composeRule
            .onNodeWithText("Who is Captain Ahab searching for?")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("fixed_0001", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("structure_0003", substring = true).assertIsDisplayed()
    }

    @Test
    fun searchStateRendersTopKResults() {
        composeRule.setContent {
            AIChallengeTheme(dynamicColor = false) {
                RagIndexingScreen(
                    state =
                        RagIndexingUiState(
                            phase = RagIndexingPhase.SUCCESS,
                            query = "emulator endpoint",
                            topK = 2,
                            searchResults =
                                listOf(
                                    RagSearchResultUi(
                                        chunkId = "fixed_0001",
                                        score = 0.8123,
                                        title = "Android emulator endpoints",
                                        section = "Local services",
                                        source = "rag_course_2026_06_29.md",
                                        preview = "Use 10.0.2.2 from Android emulator to reach host services.",
                                    ),
                                    RagSearchResultUi(
                                        chunkId = "fixed_0002",
                                        score = 0.4061,
                                        title = "Embedding Cache",
                                        section = "Cache reuse",
                                        source = "rag_course_2026_06_29.md",
                                        preview = "Embedding calls are expensive and should be reused when possible.",
                                    ),
                                ),
                        ),
                    onAction = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(RagIndexingTags.SEARCH_RESULTS).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(RagIndexingTags.SEARCH_SCORE_CHART).assertIsDisplayed()
        composeRule.onNodeWithTag("${RagIndexingTags.SEARCH_SCORE_BAR_PREFIX}_0").assertIsDisplayed()
        composeRule.onNodeWithTag("${RagIndexingTags.SEARCH_SCORE_BAR_PREFIX}_1").assertIsDisplayed()
        composeRule.onNodeWithTag("${RagIndexingTags.SEARCH_RESULT_DETAILS_PREFIX}_0").assertIsDisplayed()
        composeRule.onNodeWithText("chunk_id: fixed_0001").assertIsDisplayed()
    }

    private fun comparisonReport(): RagComparisonReport =
        RagComparisonReport(
            model = OllamaEmbeddingClient.DEFAULT_MODEL,
            strategies =
                listOf(
                    RagComparisonStrategyStats(
                        strategy = "fixed",
                        chunkCount = 10,
                        embeddingCount = 10,
                        averageTokens = 128.0,
                        minTokens = 80,
                        maxTokens = 210,
                    ),
                    RagComparisonStrategyStats(
                        strategy = "structure",
                        chunkCount = 7,
                        embeddingCount = 7,
                        averageTokens = 190.5,
                        minTokens = 110,
                        maxTokens = 300,
                    ),
                ),
            queries =
                listOf(
                    RagComparisonQueryReport(
                        query = "Who is Captain Ahab searching for?",
                        fixed =
                            listOf(
                                RagComparisonHit(
                                    chunkId = "fixed_0001",
                                    score = 0.8123,
                                    title = "Ahab",
                                    source = "moby_dick.md",
                                    preview = "Ahab searches for the white whale.",
                                ),
                            ),
                        structure =
                            listOf(
                                RagComparisonHit(
                                    chunkId = "structure_0003",
                                    score = 0.8344,
                                    title = "Moby-Dick",
                                    section = "Captain Ahab",
                                    source = "moby_dick.md",
                                    preview = "Captain Ahab follows the whale across the sea.",
                                ),
                            ),
                    ),
                ),
        )
}
