package com.cytube.mobile.data

import android.content.Context
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.cytube.mobile.net.CyTubeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

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
 *
 * Socket login remains as a fallback for the session only; it is never stored.
 */
class AuthRepository(context: Context, private val http: OkHttpClient, private val baseUrl: String) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "cytube_auth",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    data class Session(val name: String, val authCookie: String)

    sealed interface LoginOutcome {
        data class Success(val session: Session) : LoginOutcome
        data class Failure(val message: String) : LoginOutcome
        /** CSRF/cookie flow unavailable; caller may fall back to socket login. */
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
            val name = prefs.getString(KEY_NAME, null)
            val cookie = prefs.getString(KEY_COOKIE, null)
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

    /**
     * Clears the local session and, best-effort, asks the server to invalidate
     * the cookie too (CyTube's own `/logout`). The local clear always happens
     * even if that request fails or the server is unreachable — a logout
     * should never get "stuck" behind a network call — but a real logout
     * should revoke the credential, not just forget it on this device.
     */
    suspend fun logout() = withContext(Dispatchers.IO) {
        // An unremembered session never made it into prefs at all (see
        // inMemorySession's doc comment), so it has to be checked here too
        // or logging out of one would skip the server-side /logout call
        // entirely and just silently drop the in-memory copy below.
        val cookie = inMemorySession?.authCookie ?: prefs.getString(KEY_COOKIE, null)
        if (cookie != null) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/logout")
                    .header("Cookie", "auth=$cookie")
                    .get()
                    .build()
                http.newCall(req).execute().close()
            }
        }
        inMemorySession = null
        prefs.edit().remove(KEY_NAME).remove(KEY_COOKIE).apply()
        persistedSession = null
        persistedLoaded = true

        // WebCompatView shares the auth cookie into Android's WebView
        // CookieManager so the user isn't asked to log in twice there. That
        // store is process-wide and disk-persisted, entirely separate from
        // the EncryptedSharedPreferences cleared above — left alone, a
        // logged-out user who had ever opened Compatibility Mode would still
        // be shown as logged in when they opened it again.
        runCatching {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
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
                        prefs.edit().putString(KEY_NAME, username)
                            .putString(KEY_COOKIE, auth).apply()
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
        const val KEY_NAME = "name"
        const val KEY_COOKIE = "auth_cookie"
    }
}
