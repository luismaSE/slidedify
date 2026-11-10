package com.slidedify.feature.insights

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.slidedify.core.domain.suggest.Reason

/** Why a song fits a playlist, worded in the user's language. */
@Composable
internal fun reasonText(reason: Reason): String = when (reason) {
    is Reason.SameArtist -> pluralStringResource(R.plurals.reason_same_artist, reason.count, reason.count, reason.artist)
    is Reason.SameMood -> {
        val mood = stringResource(
            when (reason.mood) {
                Reason.Mood.Calm -> R.string.reason_calm
                Reason.Mood.Energetic -> R.string.reason_energetic
                Reason.Mood.Similar -> R.string.reason_similar_energy
            },
        )
        if (reason.fromName) stringResource(R.string.reason_mood_name, mood, reason.playlist) else stringResource(R.string.reason_mood_playlist, mood)
    }
    is Reason.SimilarStyle -> stringResource(R.string.reason_style, reason.tags.joinToString(", "))
    is Reason.MatchesName -> stringResource(R.string.reason_name, reason.playlist)
    is Reason.SimilarTempo -> stringResource(R.string.reason_tempo, reason.bpm)
}
