package com.slidedify.provider.spotify

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.slidedify.provider.spotify.api.SpotifyTokens
import com.slidedify.provider.spotify.api.TokenStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets with an AES-GCM key that never leaves the Android Keystore.
 * (Jetpack Security's EncryptedSharedPreferences is deprecated; this is the recommended replacement.)
 */
internal class KeystoreCipher(private val alias: String) {
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
        }
        return String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

private val Context.spotifyStore: DataStore<Preferences> by preferencesDataStore(name = "spotify_auth")

/** Tokens and the in-flight login (PKCE verifier + state), encrypted at rest. */
internal class SecureTokenStore(context: Context) : TokenStore {
    private val store = context.applicationContext.spotifyStore
    private val cipher = KeystoreCipher("slidedify_spotify")
    private val json = Json { ignoreUnknownKeys = true }
    private val tokensKey = stringPreferencesKey("tokens")
    private val pendingKey = stringPreferencesKey("pending_login")

    override suspend fun load(): SpotifyTokens? = read(tokensKey)?.let {
        runCatching { json.decodeFromString(SpotifyTokens.serializer(), it) }.getOrNull()
    }

    override suspend fun save(tokens: SpotifyTokens) = write(tokensKey, json.encodeToString(SpotifyTokens.serializer(), tokens))

    override suspend fun clear() {
        store.edit { it.remove(tokensKey) }
    }

    /** The app may be killed while the browser is open, so the PKCE verifier is persisted. */
    suspend fun savePendingLogin(state: String, verifier: String) = write(pendingKey, "$state|$verifier")

    /** Returns the verifier if [state] matches the one we sent (CSRF protection), then forgets it. */
    suspend fun takePendingVerifier(state: String): String? {
        val pending = read(pendingKey)
        store.edit { it.remove(pendingKey) }
        val (savedState, verifier) = pending?.split('|', limit = 2)?.takeIf { it.size == 2 } ?: return null
        return verifier.takeIf { savedState == state }
    }

    private suspend fun read(key: Preferences.Key<String>): String? {
        val encrypted = store.data.first()[key] ?: return null
        return try {
            cipher.decrypt(encrypted)
        } catch (e: GeneralSecurityException) {
            null // Key lost (e.g. backup restored on another device): behave as signed out.
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private suspend fun write(key: Preferences.Key<String>, value: String) {
        val encrypted = cipher.encrypt(value)
        store.edit { it[key] = encrypted }
    }
}
