package com.slidedify.provider.tidal

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
import com.slidedify.core.oauth.AuthorizedHttp
import com.slidedify.core.oauth.OAuthClient
import com.slidedify.core.oauth.Pkce
import com.slidedify.core.oauth.RefreshingTokens
import com.slidedify.core.platform.EncryptedTokenStore
import com.slidedify.core.platform.OAuthWebLoginActivity
import com.slidedify.core.platform.ProviderSignIn
import com.slidedify.provider.tidal.api.TIDAL
import com.slidedify.provider.tidal.api.TidalApi
import com.slidedify.provider.tidal.api.TidalAuth
import com.slidedify.provider.tidal.api.TidalEndpoints
import com.slidedify.provider.tidal.api.TidalLibraryWriter
import com.slidedify.provider.tidal.api.TidalMusicProvider
import com.slidedify.provider.tidal.api.TidalPlaylistRepository
import com.slidedify.provider.tidal.api.TidalTrackRepository
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
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TidalComponents @Inject constructor(
    @ApplicationContext context: Context,
    client: OkHttpClient,
    previewSource: PreviewSource,
) {
    val config = TidalEndpoints.config(BuildConfig.TIDAL_CLIENT_ID, BuildConfig.TIDAL_REDIRECT_URI)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val oauth = OAuthClient(config, client)
    val tokens = RefreshingTokens(EncryptedTokenStore(context, "tidal"), oauth)
    private val api = TidalApi(
        http = AuthorizedHttp(client, tokens, contentType = "application/vnd.api+json"),
        tokens = tokens,
        // Catalog availability depends on the country; use the phone's.
        countryCode = Locale.getDefault().country.ifBlank { "US" },
    )
    val auth = TidalAuth(tokens, api)
    val provider = TidalMusicProvider(
        auth = auth,
        tracks = TidalTrackRepository(api),
        playlists = TidalPlaylistRepository(api),
        writer = TidalLibraryWriter(api),
        player = PreviewPlayer(previewSource),
    ).also { scope.launch { auth.refresh() } }
}

@Module
@InstallIn(SingletonComponent::class)
object TidalModule {
    /** Registered only when a TIDAL client id was configured at build time. */
    @Provides
    @ElementsIntoSet
    fun tidalProvider(components: dagger.Lazy<TidalComponents>): Set<MusicProvider> =
        if (BuildConfig.TIDAL_CLIENT_ID.isBlank()) emptySet() else setOf(components.get().provider)

    @Provides
    @ElementsIntoSet
    fun tidalSignIn(): Set<ProviderSignIn> =
        if (BuildConfig.TIDAL_CLIENT_ID.isBlank()) emptySet() else setOf(TidalSignIn)
}

object TidalSignIn : ProviderSignIn {
    override val providerId: ProviderId = TIDAL

    override fun start(context: Context) {
        val intent = Intent(context, TidalLoginActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/** Authorization Code + PKCE in the in-app WebView, then token exchange. */
class TidalLoginActivity : Activity() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun tidalComponents(): TidalComponents
    }

    private var state = ""
    private var verifier = ""

    private fun components() = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).tidalComponents()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            state = savedInstanceState.getString("state").orEmpty()
            verifier = savedInstanceState.getString("verifier").orEmpty()
            return
        }
        val c = components()
        val pkce = Pkce.generate()
        state = Pkce.randomUrlSafe(16)
        verifier = pkce.verifier
        val intent = Intent(this, OAuthWebLoginActivity::class.java)
            .putExtra(OAuthWebLoginActivity.EXTRA_AUTHORIZE_URL, c.oauth.authorizeUrl(pkce, state).toString())
            .putExtra(OAuthWebLoginActivity.EXTRA_REDIRECT_URI, c.config.redirectUri)
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("state", state)
        outState.putString("verifier", verifier)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST) return
        val code = data?.getStringExtra(OAuthWebLoginActivity.RESULT_CODE)
        val returnedState = data?.getStringExtra(OAuthWebLoginActivity.RESULT_STATE)
        val appContext = applicationContext
        if (resultCode != RESULT_OK || code == null || returnedState != state) {
            Toast.makeText(appContext, "TIDAL: inicio de sesión cancelado", Toast.LENGTH_LONG).show()
        } else {
            val c = components()
            val verifier = verifier
            c.scope.launch {
                try {
                    c.tokens.signIn(code, verifier)
                    c.auth.refresh()
                } catch (e: java.io.IOException) {
                    Log.w("Slidedify", "TIDAL token exchange failed", e)
                    launch(Dispatchers.Main) { Toast.makeText(appContext, "TIDAL: no se pudo iniciar sesión", Toast.LENGTH_LONG).show() }
                }
            }
        }
        finish()
    }

    private companion object {
        const val REQUEST = 5151
    }
}
