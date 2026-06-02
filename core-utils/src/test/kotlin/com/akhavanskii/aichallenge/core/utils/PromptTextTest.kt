package com.akhavanskii.aichallenge.core.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptTextTest {
    @Test
    fun normalizedPromptOrNullTrimsAndCollapsesWhitespace() {
        assertEquals(
            "Summarize Android testing",
            "  Summarize\nAndroid\t testing  ".normalizedPromptOrNull(),
        )
    }

    @Test
    fun normalizedPromptOrNullReturnsNullForBlankInput() {
        assertNull(" \n\t ".normalizedPromptOrNull())
    }
}
