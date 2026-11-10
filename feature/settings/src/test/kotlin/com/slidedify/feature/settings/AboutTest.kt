package com.slidedify.feature.settings

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AboutTest {
    @Test
    fun `privacy text keeps paragraphs and bullets and unwraps lines`() {
        val text = "First line\ncontinues here.\n\nList:\n- one\n  item\n- two\n"
        assertEquals(listOf("First line continues here.", "List:\n• one   item\n• two"), privacyParagraphs(text))
    }

    @Test
    fun `every library has a license and a link`() {
        assertTrue(OpenSourceLibraries.isNotEmpty())
        assertTrue(OpenSourceLibraries.all { it.license.isNotBlank() && it.url.startsWith("https://") })
    }
}
