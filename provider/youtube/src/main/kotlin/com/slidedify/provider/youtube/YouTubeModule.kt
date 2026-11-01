package com.slidedify.provider.youtube

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.slidedify.core.domain.player.PreviewSource
import com.slidedify.core.domain.provider.MusicProvider
import com.slidedify.core.media.PreviewPlayer
import com.slidedify.core.model.ProviderId
import com.slidedify.core.oauth.AccessTokenSource
import com.slidedify.core.oauth.AuthorizedHttp
import com.slidedify.core.platform.ProviderSignIn
import com.slidedify.provider.youtube.api.YOUTUBE_MUSIC
import com.slidedify.provider.youtube.api.YouTubeApi
import com.slidedify.provider.youtube.api.YouTubeAuth
import com.slidedify.provider.youtube.api.YouTubeEndpoints
import com.slidedify.provider.youtube.api.YouTubeLibraryWriter
import com.slidedify.provider.youtube.api.YouTubeMusicProvider
import com.slidedify.provider.youtube.api.YouTubePlaylistRepository
import com.slidedify.provider.youtube.api.YouTubeTrackRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val Context.youtubePrefs: DataStore<Preferences> by preferencesDataStore(name = "youtube")
private val CONNECTED = booleanPreferencesKey("connected")

internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

internal fun youtubeAuthorizationRequest(): AuthorizationRequest =
    AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(YouTubeEndpoints.SCOPE))).build()

/**
 * Access tokens come from Google Play Services. Once the user granted the YouTube scope,
 * asking again returns a fresh token silently; if it needs UI, the user is signed out.
 */
class PlayServicesTokens(private val context: Context) : AccessTokenSource {
    override suspend fun accessToken(forceRefresh: Boolean): String? {
        if (context.youtubePrefs.data.first()[CONNECTED] != true) return null
        val result: AuthorizationResult = Identity.getAuthorizationClient(context).authorize(youtubeAuthorizationRequest()).await()
        return if (result.hasResolution()) null else result.accessToken
    }

    suspend fun setConnected(connected: Boolean) {
        context.youtubePrefs.edit { it[CONNECTED] = connected }
    }
}

@Singleton
class YouTubeComponents @Inject constructor(
    @ApplicationContext context: Context,
    client: OkHttpClient,
    previewSource: PreviewSource,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val tokens = PlayServicesTokens(context)
    private val api = YouTubeApi(AuthorizedHttp(client, tokens))
    val auth = YouTubeAuth(
        hasToken = { tokens.accessToken(false) != null },
        api = api,
        onSignOut = { tokens.setConnected(false) },
    )
    val provider = YouTubeMusicProvider(
        auth = auth,
        tracks = YouTubeTrackRepository(api),
        playlists = YouTubePlaylistRepository(api),
        writer = YouTubeLibraryWriter(api),
        player = PreviewPlayer(previewSource),
    ).also { scope.launch { auth.refresh() } }
}

@Module
@InstallIn(SingletonComponent::class)
object YouTubeModule {
    @Provides
    @ElementsIntoSet
    fun youtubeProvider(components: dagger.Lazy<YouTubeComponents>): Set<MusicProvider> =
        if (BuildConfig.YOUTUBE_ENABLED) setOf(components.get().provider) else emptySet()

    @Provides
    @ElementsIntoSet
    fun youtubeSignIn(): Set<ProviderSignIn> = if (BuildConfig.YOUTUBE_ENABLED) setOf(YouTubeSignIn) else emptySet()
}

object YouTubeSignIn : ProviderSignIn {
    override val providerId: ProviderId = YOUTUBE_MUSIC

    override fun start(context: Context) {
        val intent = Intent(context, YouTubeLoginActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/** Asks Google for the YouTube scope (account picker + consent the first time). */
class YouTubeLoginActivity : Activity() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun youtubeComponents(): YouTubeComponents
    }

    private fun components() = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).youtubeComponents()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        Identity.getAuthorizationClient(this).authorize(youtubeAuthorizationRequest())
            .addOnSuccessListener { result ->
                val pending = result.pendingIntent
                if (result.hasResolution() && pending != null) {
                    @Suppress("DEPRECATION")
                    startIntentSenderForResult(pending.intentSender, REQUEST, null, 0, 0, 0, null)
                } else {
                    connected()
                }
            }
            .addOnFailureListener { error -> failed(error) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST) return
        try {
            val result = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)
            if (result.accessToken != null) connected() else failed(null)
        } catch (e: Exception) {
            failed(e)
        }
    }

    private fun connected() {
        val c = components()
        c.scope.launch {
            c.tokens.setConnected(true)
            c.auth.refresh()
        }
        finish()
    }

    private fun failed(error: Throwable?) {
        Log.w("Slidedify", "YouTube authorization failed", error)
        Toast.makeText(applicationContext, "YouTube Music: no se pudo conectar", Toast.LENGTH_LONG).show()
        finish()
    }

    private companion object {
        const val REQUEST = 6161
    }
}
