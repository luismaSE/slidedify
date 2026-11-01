package com.slidedify.provider.spotify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.slidedify.core.domain.player.PreviewSource
import com.slidedify.core.domain.provider.MusicProvider
import com.slidedify.core.media.PreviewPlayer
import com.slidedify.core.model.ProviderId
import com.slidedify.core.platform.ProviderSignIn
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import com.spotify.sdk.android.auth.PKCEInformation
import com.slidedify.provider.spotify.api.Pkce
import com.slidedify.provider.spotify.api.SPOTIFY
import com.slidedify.provider.spotify.api.SpotifyAuthClient
import com.slidedify.provider.spotify.api.SpotifyAuthConfig
import com.slidedify.provider.spotify.api.SpotifyLibraryWriter
import com.slidedify.provider.spotify.api.SpotifyMusicProvider
import com.slidedify.provider.spotify.api.SpotifyPlaylistRepository
import com.slidedify.provider.spotify.api.SpotifyProviderAuth
import com.slidedify.provider.spotify.api.SpotifyTrackRepository
import com.slidedify.provider.spotify.api.SpotifyWebApi
import com.slidedify.provider.spotify.api.TokenProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the Spotify provider needs, built once. */
@Singleton
class SpotifyComponents @Inject constructor(
    @ApplicationContext context: Context,
    client: OkHttpClient,
    previewSource: PreviewSource,
) {
    val config = SpotifyAuthConfig(BuildConfig.SPOTIFY_CLIENT_ID, BuildConfig.SPOTIFY_REDIRECT_URI)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val tokenStore = SecureTokenStore(context)
    val authClient = SpotifyAuthClient(config, client)
    val tokens = TokenProvider(tokenStore, authClient)
    private val api = SpotifyWebApi(client, tokens)

    /**
     * 30 s previews (Shazam-style). Full-track control of the Spotify app proved unreliable, and a
     * preview is enough to decide where a song goes.
     */
    private val player = PreviewPlayer(previewSource)

    val provider = SpotifyMusicProvider(
        auth = SpotifyProviderAuth(tokens, api),
        tracks = SpotifyTrackRepository(api),
        playlists = SpotifyPlaylistRepository(api),
        writer = SpotifyLibraryWriter(api),
        player = player,
    ).also { scope.launch { it.auth.refresh() } }
}

@Module
@InstallIn(SingletonComponent::class)
object SpotifyModule {
    /** Registers Spotify only when a Client ID was configured at build time. */
    @Provides
    @ElementsIntoSet
    fun spotifyProvider(components: dagger.Lazy<SpotifyComponents>): Set<MusicProvider> =
        if (BuildConfig.SPOTIFY_CLIENT_ID.isBlank()) emptySet() else setOf(components.get().provider)

    @Provides
    @ElementsIntoSet
    fun spotifySignIn(components: dagger.Lazy<SpotifyComponents>): Set<ProviderSignIn> =
        if (BuildConfig.SPOTIFY_CLIENT_ID.isBlank()) emptySet() else setOf(SpotifySignIn(components))
}

/** Starts [SpotifyLoginActivity], which drives the official Spotify auth library. */
class SpotifySignIn(private val components: dagger.Lazy<SpotifyComponents>) : ProviderSignIn {
    override val providerId: ProviderId = SPOTIFY

    override fun start(context: Context) {
        components.get() // Fail fast here, not inside the activity, if DI is misconfigured.
        val intent = Intent(context, SpotifyLoginActivity::class.java)
        // Same task as the app when started from an Activity, so finishing returns to it.
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/**
 * Transparent activity that runs the Spotify login (Authorization Code + PKCE):
 * 1. with the installed Spotify app through the official auth library (native, no browser);
 * 2. if that fails or Spotify isn't installed, in an in-app WebView ([SpotifyWebLoginActivity]).
 * The resulting code is exchanged for tokens by [com.slidedify.provider.spotify.api.TokenProvider].
 */
class SpotifyLoginActivity : Activity() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun spotifyComponents(): SpotifyComponents
    }

    private var state: String = ""
    private var verifier: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // Recreated while a login screen was open: keep waiting for its result.
            state = savedInstanceState.getString(KEY_STATE).orEmpty()
            verifier = savedInstanceState.getString(KEY_VERIFIER).orEmpty()
            return
        }
        if (isSpotifyInstalled()) startNativeLogin() else startWebLogin()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_STATE, state)
        outState.putString(KEY_VERIFIER, verifier)
    }

    private fun isSpotifyInstalled(): Boolean = packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE) != null

    private fun newPkce(): Pkce = Pkce.generate().also {
        verifier = it.verifier
        state = Pkce.randomUrlSafe(16)
    }

    private fun startNativeLogin() {
        val config = components().config
        val pkce = newPkce()
        val request = AuthorizationRequest.Builder(config.clientId, AuthorizationResponse.Type.CODE, config.redirectUri)
            .setScopes(config.scopes.toTypedArray())
            .setState(state)
            .setPkceInformation(PKCEInformation.sha256(pkce.verifier, pkce.challenge))
            .build()
        @Suppress("DEPRECATION") // Plain Activity: no Activity Result API here.
        startActivityForResult(AuthorizationClient.createLoginActivityIntent(this, request), REQUEST_NATIVE)
    }

    private fun startWebLogin() {
        val components = components()
        val pkce = newPkce()
        val intent = Intent(this, SpotifyWebLoginActivity::class.java)
            .putExtra(SpotifyWebLoginActivity.EXTRA_AUTHORIZE_URL, components.authClient.authorizeUrl(pkce, state).toString())
            .putExtra(SpotifyWebLoginActivity.EXTRA_REDIRECT_URI, components.config.redirectUri)
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_WEB)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_NATIVE -> {
                val response = AuthorizationClient.getResponse(resultCode, data)
                when (response.type) {
                    AuthorizationResponse.Type.CODE -> finishWith(response.code, response.state, null)
                    AuthorizationResponse.Type.ERROR -> {
                        // e.g. AUTHENTICATION_SERVICE_UNAVAILABLE when the app isn't registered for the SDK yet.
                        Log.w("Slidedify", "Native Spotify login failed (${response.error}), falling back to WebView")
                        startWebLogin()
                    }
                    else -> finishWith(null, null, "cancelado")
                }
            }

            REQUEST_WEB -> finishWith(
                data?.getStringExtra(SpotifyWebLoginActivity.RESULT_CODE),
                data?.getStringExtra(SpotifyWebLoginActivity.RESULT_STATE),
                data?.getStringExtra(SpotifyWebLoginActivity.RESULT_ERROR) ?: if (resultCode != RESULT_OK) "cancelado" else null,
            )
        }
    }

    private fun finishWith(code: String?, returnedState: String?, error: String?) {
        val components = components()
        val appContext = applicationContext
        val problem = when {
            error != null -> "Spotify: inicio de sesión $error"
            code == null -> "Spotify: no se recibió el código de acceso"
            returnedState != state -> "Spotify: respuesta inválida, intentá de nuevo"
            else -> null
        }
        if (problem != null) {
            Toast.makeText(appContext, problem, Toast.LENGTH_LONG).show()
        } else {
            val verifier = verifier
            components.scope.launch {
                try {
                    components.provider.auth.completeSignIn(code!!, verifier)
                } catch (e: java.io.IOException) {
                    Log.w("Slidedify", "Spotify token exchange failed", e)
                    launch(Dispatchers.Main) {
                        Toast.makeText(appContext, "Spotify: no se pudo iniciar sesión (${e.message})", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        finish()
    }

    private fun components() =
        EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).spotifyComponents()

    private companion object {
        const val SPOTIFY_PACKAGE = "com.spotify.music"
        const val REQUEST_NATIVE = 4242
        const val REQUEST_WEB = 4243
        const val KEY_STATE = "state"
        const val KEY_VERIFIER = "verifier"
    }
}
