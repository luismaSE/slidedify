package com.slidedify.core.media

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.slidedify.core.domain.player.Player
import com.slidedify.core.domain.player.PlayerError
import com.slidedify.core.domain.player.PlayerState
import com.slidedify.core.domain.player.PreviewSource
import com.slidedify.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays short previews (Shazam-style) from a [PreviewSource] with the platform MediaPlayer.
 * Works for any provider and without Premium. Must be used from the main thread.
 */
class PreviewPlayer(private val source: PreviewSource) : Player {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var ticker: Job? = null
    private var requestId = 0

    override suspend fun play(track: Track) = withContext(Dispatchers.Main) {
        val id = ++requestId
        release()
        _state.value = PlayerState(track = track, isPlaying = false, isPreview = true)
        val url = source.previewUrl(track)
        if (id != requestId) return@withContext // The user already moved to another track.
        if (url == null) {
            _state.update { it.copy(error = PlayerError.Disconnected("No hay vista previa para esta canción")) }
            return@withContext
        }
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            setOnPreparedListener { player ->
                if (id != requestId) return@setOnPreparedListener
                player.start()
                _state.update { it.copy(isPlaying = true, playingDurationMs = player.duration.toLong(), error = null) }
                startTicking()
            }
            setOnCompletionListener {
                ticker?.cancel()
                _state.update { it.copy(isPlaying = false, positionMs = it.playingDurationMs ?: it.positionMs) }
            }
            setOnErrorListener { _, _, _ ->
                _state.update { it.copy(isPlaying = false, error = PlayerError.Disconnected("No se pudo reproducir la vista previa")) }
                true
            }
            setDataSource(url)
            prepareAsync()
        }
    }

    override suspend fun pause() = withContext(Dispatchers.Main) {
        mediaPlayer?.takeIf { it.isPlaying }?.pause()
        ticker?.cancel()
        _state.update { it.copy(isPlaying = false) }
    }

    override suspend fun resume() = withContext(Dispatchers.Main) {
        val player = mediaPlayer ?: return@withContext
        // After the clip ended, play it again from the start.
        if (_state.value.positionMs >= (_state.value.playingDurationMs ?: Long.MAX_VALUE)) player.seekTo(0)
        player.start()
        _state.update { it.copy(isPlaying = true) }
        startTicking()
    }

    private fun startTicking() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                mediaPlayer?.let { player -> _state.update { it.copy(positionMs = player.currentPosition.toLong()) } }
                delay(250)
            }
        }
    }

    private fun release() {
        ticker?.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
