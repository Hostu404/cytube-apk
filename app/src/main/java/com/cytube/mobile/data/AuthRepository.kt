package com.cytube.mobile.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.cytube.mobile.net.CyTubeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.security.KeyStore

/**
 * Login strategy, and why it is this way.
 *
 * CyTube offers two auth paths. The socket "login" frame takes a plaintext
 * password and would have to be replayed on every reconnect — which means
 * persisting the password to support "stay logged in". That is the thing the
 * brief explicitly rules out.
 *
 * So the preferred path is the web one: fetch /login for its CSRF token, POST
 * the credentials, and keep only the resulting signed `auth` cookie. The
 * password is discarded the moment the POST returns. The cookie is then
 * presented on the Socket.IO handshake, where ioserver.js authUserMiddleware
 * verifies it — no login frame at all.
 *
 * "Remember me" only controls whether the cookie survives a process
 * restart, via EncryptedSharedPreferences — a successful login always
 * counts for the rest of THIS process's lifetime regardless (see
 * inMemorySession below); only the durable copy is conditional.
 */
class AuthRepository(context: Context, private val http: OkHttpClient, private val baseUrl: String) {

    /** Null only if encrypted storage can't be opened at all, even fresh:
     *  logins then still work, they just aren't remembered. */
    private val prefs: SharedPreferences? = openPrefs(context)

    /**
     * Opening EncryptedSharedPreferences throws when its Keystore key has
     * gone or no longer matches the file (a Keystore reset, some OS updates,
     * data restored onto another phone). That used to crash the app on every
     * launch. The saved login can't be recovered in that state anyway, so
     * start over with a fresh file and key; the user just logs in again.
     */
    private fun openPrefs(context: Context): SharedPreferences? {
        fun open() = EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        return try {
            open()
        } catch (e: Exception) {
            Log.w("CyTubeAuth", "saved login unreadable (${e.javaClass.simpleName}); starting afresh")
            context.deleteSharedPreferences(PREFS_FILE)
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
            try {
                open()
            } catch (e2: Exception) {
                Log.w("CyTubeAuth", "encrypted storage unavailable; logins won't be remembered")
                null
            }
        }
    }

    data class Session(val name: String, val authCookie: String)

    sealed interface LoginOutcome {
        data class Success(val session: Session) : LoginOutcome
        data class Failure(val message: String) : LoginOutcome
        /** The login page couldn't be used (no CSRF token, network error). */
        data class WebFlowUnavailable(val reason: String) : LoginOutcome
    }

    /**
     * Set on every successful [login], regardless of `remember`. Cleared on
     * [logout] and never written anywhere durable, so it does not survive a
     * process restart on its own — that lack of durability is exactly what
     * is supposed to distinguish an unremembered login from a remembered
     * one. Before this field existed, `remember = false` did not just skip
     * disk persistence, it discarded the session for every purpose except
     * the login screen's own local Compose state: [savedSession] read
     * straight from EncryptedSharedPreferences, so the Account screen could
     * say "Signed in as X" while every subsequent [credentialForSession]
     * call (i.e. every channel join, and Compatibility View's cookie share)
     * silently found nothing and fell back to a guest identity — two parts
     * of the app disagreeing about whether the user was logged in.
     * `@Volatile` because a login on one coroutine can be read from another
     * (e.g. ChannelViewModel.connect) shortly after.
     */
    @Volatile
    private var inMemorySession: Session? = null

    /** The session in effect for this run of the app, whether or not it was
     *  persisted — see [inMemorySession]'s doc comment for why an
     *  unremembered login still has to count here. Checked first so it wins
     *  over a stale persisted session for the lifetime of this process (it
     *  cannot itself go stale in a way the persisted copy can't, since both
     *  ultimately come from the same login flow). */
    fun savedSession(): Session? {
        inMemorySession?.let { return it }
        if (!persistedLoaded) {
            val name = prefs?.getString(KEY_NAME, null)
            val cookie = prefs?.getString(KEY_COOKIE, null)
            persistedSession = if (name != null && cookie != null) Session(name, cookie) else null
            persistedLoaded = true
        }
        return persistedSession
    }

    /** Decrypted copy of the remembered session, read from
     *  EncryptedSharedPreferences once rather than decrypted again on every
     *  call (ChannelScreen asks for it during composition in Compatibility
     *  View). Kept in step with prefs by [login] and [logout], the only
     *  writers. */
    @Volatile private var persistedSession: Session? = null
    @Volatile private var persistedLoaded = false

    fun credentialForSession(): CyTubeClient.Credential? =
        savedSession()?.let { CyTubeClient.Credential.Cookie(it.authCookie, it.name) }

    /** Outlives any screen: a logout's clean-up must finish even if the
     *  user leaves the Account screen straight away. */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Forgets the session on this device: the in-memory and stored copies at
     * once, and the copy shared with Compatibility View's WebView in the
     * background. There's nothing to tell the server: CyTube keeps no list
     * of logins to cancel (the cookie carries its own expiry and a hash tied
     * to the account's password, so only a password change or the expiry
     * ends it), and its /logout only clears the cookie in a browser.
     */
    fun logout() {
        // All done here, not in the background job: a login straight after
        // must not have its new session wiped by this one's clean-up.
        inMemorySession = null
        prefs?.edit()?.remove(KEY_NAME)?.remove(KEY_COOKIE)?.apply()
        persistedSession = null
        persistedLoaded = true

        backgroundScope.launch {

            // WebCompatView shares the auth cookie into Android's WebView
            // CookieManager so the user isn't asked to log in twice there.
            // That store is process-wide and disk-persisted, separate from
            // the prefs above — left alone, a logged-out user who had ever
            // opened Compatibility View would still be logged in there. Only
            // the auth cookie is expired: other sites' cookies (embedded
            // players) are none of logout's business. Both the host-only
            // form WebCompatView sets and a domain-wide one the site itself
            // may have set.
            runCatching {
                CookieManager.getInstance().apply {
                    val expired = "auth=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax"
                    setCookie(baseUrl, expired)
                    baseUrl.toHttpUrlOrNull()?.host?.let { host ->
                        setCookie(baseUrl, "$expired; Domain=$host")
                    }
                    flush()
                }
            }
        }
    }

    suspend fun login(username: String, password: String, remember: Boolean): LoginOutcome =
        withContext(Dispatchers.IO) {
            try {
                val (csrfToken, sessionCookies) = fetchCsrf()
                    ?: return@withContext LoginOutcome.WebFlowUnavailable("No CSRF token on /login")

                val form = FormBody.Builder()
                    .add("name", username)
                    .add("password", password)
                    .add("_csrf", csrfToken)
                    .apply { if (remember) add("remember", "on") }
                    .build()

                val req = Request.Builder()
                    .url("$baseUrl/login")
                    .header("Cookie", sessionCookies)
                    .header("Referer", "$baseUrl/login")
                    .post(form)
                    .build()

                http.newCall(req).execute().use { resp ->
                    val auth = resp.headers("Set-Cookie")
                        .firstNotNullOfOrNull { parseCookie(it, "auth") }

                    if (auth == null) {
                        val body = resp.body?.string().orEmpty()
                        val err = Jsoup.parse(body).selectFirst(".alert-danger p")?.text()
                        return@withContext LoginOutcome.Failure(
                            err ?: "Login failed. Check your username and password."
                        )
                    }

                    val session = Session(username, auth)
                    // Always kept for this process's lifetime; only the durable
                    // copy is gated on `remember` — see inMemorySession's doc
                    // comment for why the in-memory one can't also be gated on it.
                    inMemorySession = session
                    if (remember) {
                        prefs?.edit()?.putString(KEY_NAME, username)
                            ?.putString(KEY_COOKIE, auth)?.apply()
                        persistedSession = session
                        persistedLoaded = true
                    }
                    LoginOutcome.Success(session)
                }
            } catch (e: CancellationException) {
                // Not a login failure — the caller (e.g. the login screen's
                // ViewModel scope) was cancelled out from under this request.
                // Let it propagate so structured concurrency isn't broken.
                throw e
            } catch (e: Exception) {
                // Never surface the exception verbatim — it can carry the URL
                // with query params. Keep it generic.
                LoginOutcome.WebFlowUnavailable("Network error contacting the login endpoint")
            }
        }

    private fun fetchCsrf(): Pair<String, String>? {
        val req = Request.Builder().url("$baseUrl/login").get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: return null
            val token = Jsoup.parse(body)
                .selectFirst("input[name=_csrf]")?.attr("value")
                ?.takeIf { it.isNotBlank() } ?: return null
            val cookies = resp.headers("Set-Cookie")
                .mapNotNull { it.substringBefore(';').takeIf { c -> c.contains('=') } }
                .joinToString("; ")
            return token to cookies
        }
    }

    private fun parseCookie(setCookie: String, name: String): String? {
        val first = setCookie.substringBefore(';')
        val idx = first.indexOf('=')
        if (idx <= 0) return null
        if (first.substring(0, idx).trim() != name) return null
        return first.substring(idx + 1).takeIf { it.isNotBlank() }
    }

    private companion object {
        const val PREFS_FILE = "cytube_auth"
        const val KEY_NAME = "name"
        const val KEY_COOKIE = "auth_cookie"
    }
}
