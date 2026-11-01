package com.slidedify.provider.spotify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import com.slidedify.core.domain.provider.MusicProvider
import com.slidedify.core.model.ProviderId
import com.slidedify.core.platform.ProviderSignIn
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

/** Opens the Spotify login in a Custom Tab (Authorization Code + PKCE). */
class SpotifySignIn(private val components: dagger.Lazy<SpotifyComponents>) : ProviderSignIn {
    override val providerId: ProviderId = SPOTIFY

    override fun start(context: Context) {
        val c = components.get()
        val pkce = Pkce.generate()
        val state = Pkce.randomUrlSafe(16)
        c.scope.launch {
            c.tokenStore.savePendingLogin(state, pkce.verifier)
            val url = Uri.parse(c.authClient.authorizeUrl(pkce, state).toString())
            val intent = CustomTabsIntent.Builder().build().apply {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            intent.launchUrl(context, url)
        }
    }
}

/** Receives `slidedify://spotify-callback?code=...&state=...`, finishes the login and returns to the app. */
class SpotifyAuthCallbackActivity : Activity() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun spotifyComponents(): SpotifyComponents
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val code = data?.getQueryParameter("code")
        val state = data?.getQueryParameter("state")
        val error = data?.getQueryParameter("error")
        val components = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).spotifyComponents()
        val appContext = applicationContext

        components.scope.launch {
            val message = when {
                error != null -> "Spotify: inicio de sesión cancelado ($error)"
                code == null || state == null -> "Spotify: respuesta inválida"
                else -> {
                    val verifier = components.tokenStore.takePendingVerifier(state)
                    if (verifier == null) {
                        "Spotify: la sesión expiró, intentá de nuevo"
                    } else {
                        try {
                            components.provider.auth.completeSignIn(code, verifier)
                            null
                        } catch (e: java.io.IOException) {
                            Log.w("Slidedify", "Spotify sign-in failed", e)
                            "Spotify: no se pudo iniciar sesión"
                        }
                    }
                }
            }
            if (message != null) {
                launch(Dispatchers.Main) { Toast.makeText(appContext, message, Toast.LENGTH_LONG).show() }
            }
        }

        // Back to the app's main screen; the source screen reacts to the auth state change.
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(launch)
        }
        finish()
    }
}
