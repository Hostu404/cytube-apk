package com.cytube.mobile.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where a remembered login is kept: an ordinary preferences file with each
 * value sealed (AES-256-GCM, see [SealedBox]) under a key that lives in the
 * Android Keystore and never leaves it. In place of androidx.security's
 * EncryptedSharedPreferences, which is deprecated; a login an earlier
 * version saved that way is moved over once (see [LegacyAuthPrefs]).
 *
 * Never throws: a store that can't be read (its key gone after a Keystore
 * reset, say) reads as nothing saved and is cleared, so the user just logs
 * in again, as before.
 */
internal class AuthStore private constructor(
    private val prefs: SharedPreferences,
    private val box: SealedBox
) {
    /** The saved login, or null for none (or one that can't be read). */
    fun load(): AuthRepository.Session? {
        if (!prefs.contains(KEY_NAME) && !prefs.contains(KEY_COOKIE)) return null
        val session = try {
            val name = prefs.getString(KEY_NAME, null)?.let { box.open(it, KEY_NAME) }
            val cookie = prefs.getString(KEY_COOKIE, null)?.let { box.open(it, KEY_COOKIE) }
            if (name != null && cookie != null) AuthRepository.Session(name, cookie) else null
        } catch (_: Exception) {
            null    // the Keystore itself failing
        }
        if (session == null) {
            Log.w(TAG, "saved login unreadable; starting afresh")
            clear()
        }
        return session
    }

    /** Remembers [session]; false if it couldn't be (it then lasts only
     *  until the app closes, as an unremembered login does). */
    fun save(session: AuthRepository.Session): Boolean = try {
        prefs.edit()
            .putString(KEY_NAME, box.seal(session.name, KEY_NAME))
            .putString(KEY_COOKIE, box.seal(session.authCookie, KEY_COOKIE))
            .apply()
        true
    } catch (e: Exception) {
        Log.w(TAG, "login couldn't be remembered (${e.javaClass.simpleName})")
        false
    }

    fun clear() {
        prefs.edit().remove(KEY_NAME).remove(KEY_COOKIE).apply()
    }

    companion object {
        private const val TAG = "CyTubeAuth"
        private const val FILE = "cytube_auth_v2"
        private const val KEY_NAME = "name"
        private const val KEY_COOKIE = "auth_cookie"

        /** Slow the first time (the Keystore, and moving an earlier
         *  version's login), so not on the main thread: see Graph.auth. */
        fun open(context: Context): AuthStore {
            val store = AuthStore(context.getSharedPreferences(FILE, Context.MODE_PRIVATE), SealedBox(AuthKey::get))
            LegacyAuthPrefs.moveInto(context, store)
            return store
        }
    }
}

/**
 * Seals text (AES-GCM) under [key], and opens what it sealed. Each value is
 * sealed with a label, the name it's kept under, which opening must give
 * again: values can't be swapped round in the file. Kept apart from the
 * Android Keystore so it can be unit-tested with any AES key.
 */
internal class SealedBox(private val key: () -> SecretKey) {

    /** [plain], sealed: its IV's length, the IV and the sealed bytes, as
     *  Base64. */
    fun seal(plain: String, label: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(label.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(byteArrayOf(iv.size.toByte()) + iv + sealed)
    }

    /** What [seal] sealed as [label]; null for anything else: tampered with,
     *  sealed under another key or label, or not something it sealed. */
    fun open(sealed: String, label: String): String? = try {
        val bytes = Base64.getDecoder().decode(sealed)
        val ivSize = bytes.firstOrNull()?.toInt() ?: -1
        if (ivSize !in MIN_IV_BYTES..MAX_IV_BYTES || bytes.size < 1 + ivSize + TAG_BYTES) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, bytes, 1, ivSize))
            cipher.updateAAD(label.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(bytes, 1 + ivSize, bytes.size - 1 - ivSize), Charsets.UTF_8)
        }
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null   // not Base64
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BYTES = 16
        const val MIN_IV_BYTES = 12
        const val MAX_IV_BYTES = 16
    }
}

/** The app's own AES key in the Android Keystore, made the first time it's
 *  needed. It can be used only by this app, and can't be read out at all. */
private object AuthKey {
    private const val ALIAS = "cytube_auth_key"
    @Volatile private var cached: SecretKey? = null

    fun get(): SecretKey = cached ?: synchronized(this) { cached ?: load().also { cached = it } }

    private fun load(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}

/**
 * A login remembered by an earlier version, in androidx.security's
 * EncryptedSharedPreferences: moved into [AuthStore] once, after which the
 * old file and its key are deleted. This is the one remaining use of that
 * deprecated library; it, and the security-crypto dependency, can go once
 * everyone has updated past the version that moves it.
 */
internal object LegacyAuthPrefs {
    private const val TAG = "CyTubeAuth"
    private const val FILE = "cytube_auth"
    private const val KEY_NAME = "name"
    private const val KEY_COOKIE = "auth_cookie"
    /** androidx.security's own master key alias. */
    private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"

    fun moveInto(context: Context, store: AuthStore) {
        // Nothing there (a new install, or already moved): nothing to do,
        // and no Keystore work.
        if (context.getSharedPreferences(FILE, Context.MODE_PRIVATE).all.isEmpty()) return
        try {
            val old = openEncrypted(context)
            val name = old.getString(KEY_NAME, null)
            val cookie = old.getString(KEY_COOKIE, null)
            if (name != null && cookie != null && store.load() == null) {
                store.save(AuthRepository.Session(name, cookie))
            }
        } catch (e: Exception) {
            // Its key gone, or the file spoilt: that login can't be had, as
            // before; the user logs in again.
            Log.w(TAG, "an earlier saved login couldn't be read (${e.javaClass.simpleName})")
        }
        context.deleteSharedPreferences(FILE)
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(MASTER_KEY_ALIAS) }
    }

    @Suppress("DEPRECATION")
    private fun openEncrypted(context: Context): SharedPreferences =
        androidx.security.crypto.EncryptedSharedPreferences.create(
            context,
            FILE,
            androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build(),
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
}
