package com.slidedify.core.datastore

import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.domain.settings.ButtonMode
import com.slidedify.core.domain.settings.RecentSource
import com.slidedify.core.domain.settings.SortSettings
import com.slidedify.core.model.GestureMap
import com.slidedify.core.model.PlaylistId
import com.slidedify.core.model.ProviderId
import org.junit.Test
import kotlin.test.assertEquals

class SettingsMapperTest {

    @Test
    fun `round trip keeps every field`() {
        val settings = SortSettings(
            gestureMaps = mapOf(
                ProviderId("spotify") to GestureMap(listOf(setOf(PlaylistId("a"), PlaylistId("b")), setOf(PlaylistId("c")))),
            ),
            holdTimeoutMs = 450,
            leftHanded = true,
            haptics = false,
            autoplay = false,
            buttonMode = ButtonMode.On,
            tutorialCompleted = true,
            suggestions = false,
            recent = listOf(
                RecentSource(ProviderId("spotify"), TrackSource.FromPlaylist(PlaylistId("gym")), "Gym", "https://img", 120, 1_000),
                RecentSource(ProviderId("demo"), TrackSource.LikedSongs, "Me gusta", openedAt = 500),
            ),
            lastProvider = ProviderId("spotify"),
        )
        assertEquals(settings, SettingsMapper.decode(SettingsMapper.encode(settings)))
    }

    @Test
    fun `missing or corrupted data falls back to defaults`() {
        assertEquals(SortSettings(), SettingsMapper.decode(null))
        assertEquals(SortSettings(), SettingsMapper.decode("{not json"))
    }

    @Test
    fun `old format, empty destinations and unknown modes are ignored`() {
        val raw = """{"version":1,"gestureMaps":{"p":{"UP":["x"]}},"destinations":{"p":[[],["y"]]},"buttonMode":"Maybe","extra":1}"""
        val decoded = SettingsMapper.decode(raw)
        assertEquals(GestureMap(listOf(setOf(PlaylistId("y")))), decoded.gestureMaps[ProviderId("p")])
        assertEquals(ButtonMode.Auto, decoded.buttonMode)
    }
}
