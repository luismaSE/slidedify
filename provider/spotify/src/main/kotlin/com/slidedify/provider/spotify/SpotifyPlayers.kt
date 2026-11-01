package com.slidedify.provider.spotify

import android.content.Context
import android.util.Log
import com.slidedify.core.domain.player.Player
import com.slidedify.core.domain.player.PlayerState
import com.slidedify.core.model.Track
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.client.CallResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun <T> CallResult<T>.await(): T = suspendCancellableCoroutine { cont ->
    setResultCallback { cont.resume(it) }
    setErrorCallback { cont.resumeWithException(it) }
}

/**
 * Full-track playback through the Spotify app installed on the phone (App Remote SDK).
 * Unlike the Web API, this controls the local app directly, so it doesn't depend on Spotify
 * Connect considering the phone "active". Requires Premium for on-demand playback.
 */
class AppRemotePlayer(
    private val context: Context,
    private val clientId: String,
    private val redirectUri: String,
    private val scope: CoroutineScope,
) : Player {
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var remote: SpotifyAppRemote? = null
    private var expectedUri: String? = null
    private var ticker: Job? = null

    private suspend fun connected(): SpotifyAppRemote {
        remote?.takeIf { it.isConnected }?.let { return it }
        val params = ConnectionParams.Builder(clientId)
            .setRedirectUri(redirectUri)
            .showAuthView(true) // First time: Spotify asks the user to allow app-remote-control.
            .build()
        val connected = withTimeout(CONNECT_TIMEOUT_MS) { suspendCancellableCoroutine { cont ->
            SpotifyAppRemote.connect(
                context,
                params,
                object : Connector.ConnectionListener {
                    override fun onConnected(appRemote: SpotifyAppRemote) = cont.resume(appRemote)

                    override fun onFailure(error: Throwable) = cont.resumeWithException(error)
                },
            )
        } }
        remote = connected
        connected.playerApi.subscribeToPlayerState().setEventCallback { playerState ->
            val playing = playerState.track?.uri
            if (playing == null || playing != expectedUri) return@setEventCallback
            _state.update {
                it.copy(isPlaying = !playerState.isPaused, positionMs = playerState.playbackPosition, error = null)
            }
            if (playerState.isPaused) ticker?.cancel() else startTicking()
        }
        return connected
    }

    override suspend fun play(track: Track) {
        val uri = "spotify:track:${track.id.value}"
        expectedUri = uri
        _state.value = PlayerState(track = track, isPlaying = true)
        connected().playerApi.play(uri).await()
        startTicking()
    }

    override suspend fun pause() {
        ticker?.cancel()
        _state.update { it.copy(isPlaying = false) }
        remote?.takeIf { it.isConnected }?.playerApi?.pause()?.await()
    }

    override suspend fun resume() {
        _state.update { it.copy(isPlaying = true) }
        connected().playerApi.resume().await()
        startTicking()
    }

    /** Smooth progress between the (sparse) player state events. */
    private fun startTicking() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && _state.value.isPlaying) {
                delay(250)
                _state.update { it.copy(positionMs = it.positionMs + 250) }
            }
        }
    }

    fun disconnect() {
        remote?.let(SpotifyAppRemote::disconnect)
        remote = null
    }

    private companion object {
        /** Includes the time the user needs to accept the Spotify authorization screen the first time. */
        const val CONNECT_TIMEOUT_MS = 20_000L
    }
}

/**
 * Spotify playback with a graceful fallback: full track through the Spotify app when possible,
 * otherwise a short preview (Shazam-style) so sorting by ear always works.
 * After a failure, the full-track path is retried only every [retryAfterMs] to avoid delays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyPlaybackChain(
    private val full: AppRemotePlayer,
    private val preview: Player,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryAfterMs: Long = 60_000,
) : Player {
    private val active = MutableStateFlow<Player>(full)
    private val fallbackReason = MutableStateFlow<String?>(null)
    private var lastFailureAt = Long.MIN_VALUE / 2

    override val state: StateFlow<PlayerState> =
        combine(active.flatMapLatest { it.state }, fallbackReason) { state, reason ->
            if (state.isPreview && reason != null) state.copy(notice = "Vista previa · Spotify: $reason") else state
        }.stateIn(scope, SharingStarted.Eagerly, PlayerState())

    override suspend fun play(track: Track) {
        if (clock() - lastFailureAt >= retryAfterMs) {
            try {
                preview.pause()
                full.play(track)
                active.value = full
                fallbackReason.value = null
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w("Slidedify", "Full playback unavailable, using previews", e)
                lastFailureAt = clock()
                fallbackReason.value = describe(e)
            }
        }
        active.value = preview
        preview.play(track)
    }

    override suspend fun pause() = active.value.pause()

    override suspend fun resume() = active.value.resume()

    private fun describe(error: Throwable): String = when (error) {
        is CouldNotFindSpotifyApp -> "la app no está instalada"
        is NotLoggedInException -> "iniciá sesión en la app de Spotify"
        is UserNotAuthorizedException -> "falta autorizar (${error.message})"
        else -> error.message ?: error.javaClass.simpleName
    }
}
