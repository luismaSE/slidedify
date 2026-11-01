package com.slidedify.core.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.slidedify.core.oauth.OAuthTokens
import com.slidedify.core.oauth.TokenStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM with a key that never leaves the Android Keystore. */
class KeystoreCipher(private val alias: String) {
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
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE)) }
        return String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

private val Context.oauthStore: DataStore<Preferences> by preferencesDataStore(name = "oauth_tokens")

/** OAuth tokens of one provider ([name]), encrypted at rest. */
class EncryptedTokenStore(context: Context, private val name: String) : TokenStore {
    private val store = context.applicationContext.oauthStore
    private val cipher = KeystoreCipher("slidedify_oauth_$name")
    private val key = stringPreferencesKey("tokens_$name")
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): OAuthTokens? {
        val encrypted = store.data.first()[key] ?: return null
        return try {
            json.decodeFromString(OAuthTokens.serializer(), cipher.decrypt(encrypted))
        } catch (e: GeneralSecurityException) {
            null // Key lost (e.g. restored backup on another device): behave as signed out.
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    override suspend fun save(tokens: OAuthTokens) {
        val encrypted = cipher.encrypt(json.encodeToString(OAuthTokens.serializer(), tokens))
        store.edit { it[key] = encrypted }
    }

    override suspend fun clear() {
        store.edit { it.remove(key) }
    }
}
