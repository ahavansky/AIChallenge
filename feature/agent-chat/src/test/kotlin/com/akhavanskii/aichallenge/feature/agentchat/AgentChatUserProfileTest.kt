package com.akhavanskii.aichallenge.feature.agentchat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalSerializationApi::class)
class AgentChatUserProfileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun profileParsesEditableText() {
        val profile =
            AgentChatUserProfile.fromEditableText(
                id = CUSTOM_PROFILE_ID,
                fallbackTitle = "Custom user",
                text =
                    """
                    Title: Reviewer
                    Role: Kotlin reviewer
                    Expertise: Senior
                    Style: concise
                    Style: concise
                    Format: findings first
                    Constraint: no RxJava
                    """.trimIndent(),
            )

        assertEquals("Reviewer", profile.title)
        assertEquals("Kotlin reviewer", profile.role)
        assertEquals("Senior", profile.expertiseLevel)
        assertEquals(listOf("concise"), profile.stylePreferences)
        assertEquals(listOf("findings first"), profile.formatPreferences)
        assertEquals(listOf("no RxJava"), profile.constraints)
    }

    @Test
    fun profileStorePersistsActiveProfileAndMergesDefaults() =
        runTest {
            val file = File(temporaryFolder.root, DEFAULT_PROFILE_FILE_NAME)
            val store =
                JsonAgentChatUserProfileStore(
                    profileFile = file,
                    json = json,
                    dispatcher = Dispatchers.Unconfined,
                )
            val snapshot =
                AgentChatProfileSnapshot()
                    .withActiveProfile(ANDROID_BEGINNER_PROFILE_ID)

            store.save(snapshot)
            val restored = store.load()

            assertEquals(ANDROID_BEGINNER_PROFILE_ID, restored.activeProfileId)
            assertTrue(restored.profiles.any { it.id == SENIOR_KOTLIN_PROFILE_ID })
            assertTrue(restored.activeProfile.title.contains("Android beginner"))
        }
}
