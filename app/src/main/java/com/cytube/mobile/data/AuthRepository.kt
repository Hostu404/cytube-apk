package com.cytube.mobile.data

import android.content.Context
import android.webkit.CookieManager
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
 * restart, kept encrypted by [AuthStore] — a successful login always
 * counts for the rest of THIS process's lifetime regardless (see
 * inMemorySession below); only the durable copy is conditional.
 */
class AuthRepository(context: Context, private val http: OkHttpClient, private val baseUrl: String) {

    /** The remembered login, encrypted with a key kept in the Android
     *  Keystore. One that can't be read (a Keystore reset, say) reads as
     *  none and the user logs in again, rather than crashing every launch.
     *  Opening it can take a moment the first time: see Graph.auth. */
    private val store: AuthStore = AuthStore.open(context)

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
     * distinguishes an unremembered login from a remembered one. Without it,
     * an unremembered login would count only on the Account screen, while
     * [credentialForSession] (every channel join, and Compatibility View's
     * cookie share) found nothing and joined as a guest.
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
            persistedSession = store.load()
            persistedLoaded = true
        }
        return persistedSession
    }

    /** Decrypted copy of the remembered session, read from [store] once
     *  rather than decrypted again on every call (ChannelScreen asks for it
     *  each time Compatibility View opens). Kept in step with the
     *  store by [login] and [logout], the only writers. */
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
        store.clear()
        persistedSession = null
        persistedLoaded = true

        backgroundScope.launch {

            // WebCompatView shares the auth cookie into Android's WebView
            // CookieManager so the user isn't asked to log in twice there.
            // That store is process-wide and disk-persisted, separate from
            // the app's own above — left alone, a logged-out user who had ever
            // opened Compatibility View would still be logged in there. Only
            // the auth cookie is expired: other sites' cookies (embedded
            // players) are none of logout's business. Both the domain-wide
            // form WebCompatView sets (as the site itself does; see
            // CompatCookies) and the host-only one older versions set.
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
                        store.save(session)
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
}
