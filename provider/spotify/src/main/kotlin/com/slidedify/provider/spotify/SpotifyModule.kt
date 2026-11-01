package com.slidedify.provider.spotify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.slidedify.core.domain.provider.MusicProvider
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
import com.slidedify.provider.spotify.api.SpotifyMusicProvider
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
) {
    val config = SpotifyAuthConfig(BuildConfig.SPOTIFY_CLIENT_ID, BuildConfig.SPOTIFY_REDIRECT_URI)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val tokenStore = SecureTokenStore(context)
    val authClient = SpotifyAuthClient(config, client)
    val tokens = TokenProvider(tokenStore, authClient)
    val provider: SpotifyMusicProvider = SpotifyMusicProvider.create(SpotifyWebApi(client, tokens), tokens, scope).also {
        scope.launch { it.auth.refresh() }
    }
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
 * Transparent activity that runs the Spotify login (Authorization Code + PKCE) through the
 * official auth library: it uses the installed Spotify app when available (no browser involved)
 * and falls back to a Custom Tab otherwise. The code is then exchanged for tokens by us.
 */
class SpotifyLoginActivity : Activity() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun spotifyComponents(): SpotifyComponents
    }

    private lateinit var state: String
    private lateinit var verifier: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // Recreated while the login was open: keep waiting for its result.
            state = savedInstanceState.getString(KEY_STATE).orEmpty()
            verifier = savedInstanceState.getString(KEY_VERIFIER).orEmpty()
            return
        }
        val config = components().config
        val pkce = Pkce.generate()
        state = Pkce.randomUrlSafe(16)
        verifier = pkce.verifier
        val request = AuthorizationRequest.Builder(config.clientId, AuthorizationResponse.Type.CODE, config.redirectUri)
            .setScopes(config.scopes.toTypedArray())
            .setState(state)
            .setPkceInformation(PKCEInformation.sha256(pkce.verifier, pkce.challenge))
            .build()
        @Suppress("DEPRECATION") // Plain Activity: no Activity Result API here.
        startActivityForResult(AuthorizationClient.createLoginActivityIntent(this, request), REQUEST_CODE)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_STATE, state)
        outState.putString(KEY_VERIFIER, verifier)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE) return
        val response = AuthorizationClient.getResponse(resultCode, data)
        val components = components()
        val appContext = applicationContext
        val code = response.code
        val immediateMessage = when {
            response.type == AuthorizationResponse.Type.ERROR -> "Spotify: ${response.error ?: "error al iniciar sesión"}"
            response.type != AuthorizationResponse.Type.CODE || code == null -> "Spotify: inicio de sesión cancelado"
            response.state != state -> "Spotify: respuesta inválida, intentá de nuevo"
            else -> null
        }
        if (immediateMessage != null) {
            Log.w("Slidedify", "Spotify login ended: type=${response.type} error=${response.error}")
            Toast.makeText(appContext, immediateMessage, Toast.LENGTH_LONG).show()
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
        const val REQUEST_CODE = 4242
        const val KEY_STATE = "state"
        const val KEY_VERIFIER = "verifier"
    }
}
