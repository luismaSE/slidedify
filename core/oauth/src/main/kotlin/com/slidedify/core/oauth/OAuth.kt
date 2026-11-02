package com.slidedify.core.oauth

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** OAuth 2.0 public client (Authorization Code + PKCE, no secret), as used by mobile apps. */
data class OAuthConfig(
    val clientId: String,
    val redirectUri: String,
    val authorizeUrl: String,
    val tokenUrl: String,
    val scopes: List<String>,
    /** Extra query parameters some services require on the authorize URL. */
    val extraAuthorizeParams: Map<String, String> = emptyMap(),
) {
    val isConfigured: Boolean get() = clientId.isNotBlank()
}

/** PKCE pair (RFC 7636): the verifier stays on the device, only the challenge is sent first. */
data class Pkce(val verifier: String, val challenge: String) {
    companion object {
        private val random = SecureRandom()

        fun generate(): Pkce = fromVerifier(randomUrlSafe(64))

        fun fromVerifier(verifier: String): Pkce {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return Pkce(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
        }

        fun randomUrlSafe(bytes: Int): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))
    }
}

@Serializable
data class OAuthTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresInSeconds: Long = 3600,
    /** Some services return the user id with the token (e.g. Tidal). */
    @SerialName("user_id") val userId: String? = null,
    val obtainedAtMs: Long = 0,
) {
    fun isExpired(nowMs: Long, marginMs: Long = 60_000): Boolean = nowMs >= obtainedAtMs + expiresInSeconds * 1000 - marginMs
}

interface TokenStore {
    suspend fun load(): OAuthTokens?

    suspend fun save(tokens: OAuthTokens)

    suspend fun clear()
}

class InMemoryTokenStore : TokenStore {
    private var tokens: OAuthTokens? = null

    override suspend fun load() = tokens

    override suspend fun save(tokens: OAuthTokens) {
        this.tokens = tokens
    }

    override suspend fun clear() {
        tokens = null
    }
}

class OAuthException(message: String) : IOException(message)

/** Builds the login URL and exchanges/refreshes tokens against the service's token endpoint. */
class OAuthClient(
    private val config: OAuthConfig,
    private val client: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun authorizeUrl(pkce: Pkce, state: String): HttpUrl = config.authorizeUrl.toHttpUrl().newBuilder()
        .addQueryParameter("client_id", config.clientId)
        .addQueryParameter("response_type", "code")
        .addQueryParameter("redirect_uri", config.redirectUri)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("code_challenge", pkce.challenge)
        .addQueryParameter("scope", config.scopes.joinToString(" "))
        .addQueryParameter("state", state)
        .apply { config.extraAuthorizeParams.forEach { (k, v) -> addQueryParameter(k, v) } }
        .build()

    suspend fun exchangeCode(code: String, verifier: String): OAuthTokens = token(
        FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", config.redirectUri)
            .add("client_id", config.clientId)
            .add("code_verifier", verifier)
            .build(),
    )

    suspend fun refresh(refreshToken: String): OAuthTokens {
        val tokens = token(
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", config.clientId)
                .build(),
        )
        return if (tokens.refreshToken == null) tokens.copy(refreshToken = refreshToken) else tokens
    }

    private suspend fun token(body: FormBody): OAuthTokens = withContext(dispatcher) {
        val request = Request.Builder().url(config.tokenUrl).post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw OAuthException("Token request failed (${response.code}): ${text.take(200)}")
            json.decodeFromString(OAuthTokens.serializer(), text).copy(obtainedAtMs = clock())
        }
    }
}

/** Where an API client gets its bearer token from (OAuth refresh, Google Play Services, ...). */
fun interface AccessTokenSource {
    suspend fun accessToken(forceRefresh: Boolean): String?
}

/** Valid access token from a [TokenStore], refreshed when about to expire (single-flight). */
class RefreshingTokens(
    private val store: TokenStore,
    private val oauth: OAuthClient,
    private val clock: () -> Long = System::currentTimeMillis,
) : AccessTokenSource {
    private val mutex = Mutex()

    override suspend fun accessToken(forceRefresh: Boolean): String? = mutex.withLock {
        val tokens = store.load() ?: return null
        if (!forceRefresh && !tokens.isExpired(clock())) return tokens.accessToken
        val refreshToken = tokens.refreshToken ?: return null
        val refreshed = oauth.refresh(refreshToken).let { if (it.userId == null) it.copy(userId = tokens.userId) else it }
        store.save(refreshed)
        refreshed.accessToken
    }

    suspend fun signIn(code: String, verifier: String) = store.save(oauth.exchangeCode(code, verifier))

    suspend fun signOut() = store.clear()

    suspend fun userId(): String? = store.load()?.userId
}

class RateLimitException(val retryAfterSeconds: Long) : IOException("Rate limited, retry after ${retryAfterSeconds}s")

class ApiException(val code: Int, message: String) : IOException("HTTP $code: $message")

class NotSignedInException : IOException("Not signed in")

/** JSON-over-HTTP with a bearer token: one retry after 401 with a fresh token, typed 429. */
class AuthorizedHttp(
    private val client: OkHttpClient,
    private val tokens: AccessTokenSource,
    private val contentType: String = "application/json",
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Body text, or null for 204. */
    suspend fun request(method: String, url: HttpUrl, body: String? = null): String? = withContext(dispatcher) {
        var token = tokens.accessToken(false) ?: throw NotSignedInException()
        repeat(2) { attempt ->
            val requestBody = body?.toRequestBody(contentType.toMediaType())
                ?: if (method == "GET" || method == "DELETE") null else ByteArray(0).toRequestBody(null)
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
                .header("Accept", contentType)
                .method(method, requestBody)
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                when {
                    response.code == 204 -> return@withContext null
                    response.isSuccessful -> return@withContext text
                    response.code == 401 && attempt == 0 -> token = tokens.accessToken(true) ?: throw NotSignedInException()
                    response.code == 429 -> throw RateLimitException(response.header("Retry-After")?.toLongOrNull() ?: 5)
                    else -> throw ApiException(response.code, text.take(300))
                }
            }
        }
        throw NotSignedInException()
    }
}
