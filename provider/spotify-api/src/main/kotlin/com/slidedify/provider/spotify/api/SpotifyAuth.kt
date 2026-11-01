package com.slidedify.provider.spotify.api

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** App registration values from the Spotify Developer Dashboard. No client secret: PKCE. */
data class SpotifyAuthConfig(
    val clientId: String,
    val redirectUri: String,
    val scopes: List<String> = DEFAULT_SCOPES,
) {
    val isConfigured: Boolean get() = clientId.isNotBlank() && redirectUri.isNotBlank()

    companion object {
        val DEFAULT_SCOPES = listOf(
            "user-read-private",
            "user-library-read",
            "user-library-modify",
            "playlist-read-private",
            "playlist-read-collaborative",
            "playlist-modify-private",
            "playlist-modify-public",
            "user-top-read",
            "user-read-playback-state",
            "user-modify-playback-state",
        )
    }
}

/** PKCE pair (RFC 7636). The verifier stays on the device; only the challenge is sent first. */
data class Pkce(val verifier: String, val challenge: String) {
    companion object {
        private val random = SecureRandom()

        fun generate(): Pkce = fromVerifier(randomUrlSafe(64))

        fun fromVerifier(verifier: String): Pkce {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return Pkce(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
        }

        fun randomUrlSafe(bytes: Int): String {
            val buffer = ByteArray(bytes).also(random::nextBytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
        }
    }
}

@Serializable
data class SpotifyTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresInSeconds: Long = 3600,
    val scope: String? = null,
    /** Set locally when the tokens are received. */
    val obtainedAtMs: Long = 0,
) {
    fun isExpired(nowMs: Long, marginMs: Long = 60_000): Boolean = nowMs >= obtainedAtMs + expiresInSeconds * 1000 - marginMs
}

/** Persistence for tokens (encrypted storage on Android, in-memory in tests). */
interface TokenStore {
    suspend fun load(): SpotifyTokens?

    suspend fun save(tokens: SpotifyTokens)

    suspend fun clear()
}

class SpotifyAuthException(message: String) : IOException(message)

/** Talks to accounts.spotify.com: builds the login URL and exchanges/refreshes tokens. */
class SpotifyAuthClient(
    private val config: SpotifyAuthConfig,
    private val client: OkHttpClient,
    private val accountsUrl: HttpUrl = "https://accounts.spotify.com/".toHttpUrl(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** URL to open in a Custom Tab. [state] must be checked when the redirect comes back. */
    fun authorizeUrl(pkce: Pkce, state: String): HttpUrl = accountsUrl.newBuilder()
        .addPathSegment("authorize")
        .addQueryParameter("client_id", config.clientId)
        .addQueryParameter("response_type", "code")
        .addQueryParameter("redirect_uri", config.redirectUri)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("code_challenge", pkce.challenge)
        .addQueryParameter("scope", config.scopes.joinToString(" "))
        .addQueryParameter("state", state)
        .build()

    suspend fun exchangeCode(code: String, verifier: String): SpotifyTokens = token(
        FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", config.redirectUri)
            .add("client_id", config.clientId)
            .add("code_verifier", verifier)
            .build(),
    )

    /** Spotify may or may not return a new refresh token; keep the old one if not. */
    suspend fun refresh(refreshToken: String): SpotifyTokens {
        val tokens = token(
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", config.clientId)
                .build(),
        )
        return if (tokens.refreshToken == null) tokens.copy(refreshToken = refreshToken) else tokens
    }

    private suspend fun token(body: FormBody): SpotifyTokens = withContext(dispatcher) {
        val request = Request.Builder().url(accountsUrl.resolve("api/token")!!).post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw SpotifyAuthException("Token request failed (${response.code}): $text")
            json.decodeFromString(SpotifyTokens.serializer(), text).copy(obtainedAtMs = clock())
        }
    }
}

/**
 * Hands out a valid access token, refreshing it when it is about to expire.
 * A mutex ensures concurrent requests trigger a single refresh.
 */
class TokenProvider(
    private val store: TokenStore,
    private val auth: SpotifyAuthClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun accessToken(forceRefresh: Boolean = false): String? = mutex.withLock {
        val tokens = store.load() ?: return null
        if (!forceRefresh && !tokens.isExpired(clock())) return tokens.accessToken
        val refreshToken = tokens.refreshToken ?: return null
        val refreshed = auth.refresh(refreshToken)
        store.save(refreshed)
        refreshed.accessToken
    }

    suspend fun signIn(code: String, verifier: String) {
        store.save(auth.exchangeCode(code, verifier))
    }

    suspend fun signOut() = store.clear()
}
