package com.slidedify.core.data

import com.slidedify.core.domain.suggest.TrackFeatures
import org.junit.Test
import kotlin.test.assertEquals

class FeatureStoreTest {
    @Test
    fun `features survive being stored`() {
        val features = TrackFeatures(
            tags = mapOf("chill" to 1f, "indie folk" to 0.4f),
            bpm = 92f,
            audioEnergy = 0.2f,
            brightness = 0.3f,
            loudness = null,
            analyzedBy = setOf("deezer", "audio"),
        )
        assertEquals(features, features.toEntity("bon iver|holocene", now = 1).toFeatures())
    }

    @Test
    fun `odd tag names don't break the format`() {
        val features = TrackFeatures(tags = mapOf("r=b;soul" to 0.5f))
        assertEquals(setOf("r b soul"), features.toEntity("k", 0).toFeatures().tags.keys)
    }

    @Test
    fun `nothing known stays empty`() {
        assertEquals(TrackFeatures(), TrackFeatures().toEntity("k", 0).toFeatures())
    }
}
