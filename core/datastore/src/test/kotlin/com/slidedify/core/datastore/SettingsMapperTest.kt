package com.slidedify.core.datastore

import com.slidedify.core.domain.settings.ButtonMode
import com.slidedify.core.domain.settings.SortSettings
import com.slidedify.core.model.GestureMap
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.ProviderId
import com.slidedify.core.model.Slot
import org.junit.Test
import kotlin.test.assertEquals

class SettingsMapperTest {

    @Test
    fun `round trip keeps every field`() {
        val settings = SortSettings(
            gestureMaps = mapOf(
                ProviderId("spotify") to GestureMap(mapOf(Slot.UP to setOf(PlaylistId("a"), PlaylistId("b")))),
            ),
            holdTimeoutMs = 450,
            leftHanded = true,
            haptics = false,
            autoplay = false,
            buttonMode = ButtonMode.On,
            tutorialCompleted = true,
        )
        assertEquals(settings, SettingsMapper.decode(SettingsMapper.encode(settings)))
    }

    @Test
    fun `missing or corrupted data falls back to defaults`() {
        assertEquals(SortSettings(), SettingsMapper.decode(null))
        assertEquals(SortSettings(), SettingsMapper.decode("{not json"))
    }

    @Test
    fun `unknown slots and modes are ignored`() {
        val raw = """{"gestureMaps":{"p":{"SIDEWAYS":["x"],"DOWN":["y"]}},"buttonMode":"Maybe","extra":1}"""
        val decoded = SettingsMapper.decode(raw)
        assertEquals(GestureMap(mapOf(Slot.DOWN to setOf(PlaylistId("y")))), decoded.gestureMaps[ProviderId("p")])
        assertEquals(ButtonMode.Auto, decoded.buttonMode)
    }
}
