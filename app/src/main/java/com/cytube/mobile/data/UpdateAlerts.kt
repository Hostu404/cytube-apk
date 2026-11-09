package com.cytube.mobile.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.cytube.mobile.BuildConfig
import com.cytube.mobile.di.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

/** Something to tell everyone on the home page (phone app only): a newer build to get ([update]), or
 *  a message from alerts.json, shown up to and including [until]. [id] is what dismissing it keeps. */
@Immutable
data class AppAlert(
    val id: String,
    val text: String,
    val link: String?,
    val update: Boolean = false,
    val until: LocalDate? = null
)

/**
 * The phone app's update and news alerts, kept light: no permission beyond the internet access the
 * app has anyway, nothing in the background, never on TV. As the home page opens, at most every
 * [CHECK_EVERY_MS], two small reads from GitHub:
 *  - the release this build follows (a nightly build the nightly release, any other the latest
 *    proper release): published after this copy of the app was installed or last updated, it's an
 *    update alert, linking to the release's page;
 *  - alerts.json in the repo, edited on GitHub to tell everyone something (see [AppAlerts.newsFrom]).
 * What's found is kept, so it still shows offline, until it's dismissed or has had its day. A read
 * that fails keeps what that one found before; a disk that can't be read or written just means no
 * alerts this time.
 */
class UpdateAlerts(private val context: Context) {

    /** What to show now: found, not dismissed, and not past its last day (by the phone's own date).
     *  Only when that changes, not on every other write to the settings store. */
    val shown: Flow<List<AppAlert>> = context.dataStore.data
        .map { p ->
            val dismissed = p[DISMISSED].orEmpty()
            val today = LocalDate.now()
            AppAlerts.decode(p[FOUND]).filter { it.id !in dismissed && (it.until == null || !today.isAfter(it.until)) }
        }
        .distinctUntilChanged()
        .catch { if (it is IOException) emit(emptyList()) else throw it }

    /** [refreshIfDue], in the app's own scope rather than the page's: leaving the home page straight
     *  away doesn't cut it short. */
    fun refreshInBackground() {
        Graph.appScope.launch { refreshIfDue() }
    }

    suspend fun refreshIfDue(now: Long = System.currentTimeMillis()) {
        if (!refreshing.tryLock()) return                                     // one at a time
        try {
            val p = context.dataStore.data.first()
            if (now - (p[CHECKED_AT] ?: 0L) in 0 until CHECK_EVERY_MS) return
            // Let the home page's own loading (the channel list) go first.
            delay(START_DELAY_MS)
            val nightly = AppAlerts.isNightly(BuildConfig.VERSION_NAME)
            val release = fetch(if (nightly) NIGHTLY_API else LATEST_API)
            val news = fetch(ALERTS_FILE)
            // Offline: keep what's there, and don't count this as a check — it
            // used to be marked done before fetching, so opening the app
            // offline put off the next look for 12 hours. (Failing fast as
            // they do offline, a few tries more cost nothing.)
            if (release is Fetched.Failed && news is Fetched.Failed) return
            val before = AppAlerts.decode(p[FOUND])
            val update = when (release) {
                is Fetched.Body -> listOfNotNull(release.json?.let { AppAlerts.updateFrom(it, installedAt(), nightly) })
                Fetched.Missing -> emptyList()
                Fetched.Failed -> before.filter { it.update }
            }
            val messages = when (news) {
                is Fetched.Body -> news.json?.let { AppAlerts.newsFrom(it, LocalDate.now(), BuildConfig.VERSION_CODE) }.orEmpty()
                Fetched.Missing -> emptyList()
                Fetched.Failed -> before.filterNot { it.update }
            }
            val found = update + messages
            context.dataStore.edit {
                it[CHECKED_AT] = now
                it[FOUND] = AppAlerts.encode(found)
                // Only what's still about stays dismissed, so the list never grows.
                it[DISMISSED] = it[DISMISSED].orEmpty().filterTo(HashSet()) { id -> found.any { a -> a.id == id } }
            }
        } catch (_: IOException) {
            // Not this time.
        } finally {
            refreshing.unlock()
        }
    }

    suspend fun dismiss(id: String) {
        try {
            context.dataStore.edit { it[DISMISSED] = it[DISMISSED].orEmpty() + id }
        } catch (_: IOException) {
        }
    }

    private sealed interface Fetched {
        class Body(val json: JSONObject?) : Fetched
        data object Missing : Fetched
        data object Failed : Fetched
    }

    private suspend fun fetch(url: String): Fetched = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
            Graph.http.newCall(request).execute().use { r ->
                when {
                    r.code == 404 -> Fetched.Missing
                    !r.isSuccessful -> Fetched.Failed
                    else -> Fetched.Body(runCatching { JSONObject(r.peekBody(MAX_BYTES).string()) }.getOrNull())
                }
            }
        } catch (_: IOException) {
            Fetched.Failed
        }
    }

    /** When this copy of the app was installed or last updated; unknown, never older than a release. */
    private fun installedAt(): Long = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            else @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, 0)
        info.lastUpdateTime
    }.getOrDefault(Long.MAX_VALUE)

    private companion object {
        /** Twice a day at most, a few seconds after the home page opens. */
        const val CHECK_EVERY_MS = 12 * 60 * 60_000L
        const val START_DELAY_MS = 3_000L
        /** One check at a time, in the whole app. */
        val refreshing = Mutex()
        const val MAX_BYTES = 512L * 1024
        const val REPO = "Hostu404/cytube-apk"
        const val NIGHTLY_API = "https://api.github.com/repos/$REPO/releases/tags/nightly"
        const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"
        const val ALERTS_FILE = "https://raw.githubusercontent.com/$REPO/main/alerts.json"
        val CHECKED_AT = longPreferencesKey("alerts_checked_at")
        val FOUND = stringPreferencesKey("alerts_found")
        val DISMISSED = stringSetPreferencesKey("alerts_dismissed")
    }
}

/** The rules for what an alert is, apart from fetching and keeping them, for testing. */
internal object AppAlerts {
    const val MAX_NEWS = 3
    const val MAX_TEXT = 200
    const val NIGHTLY_PAGE = "https://github.com/Hostu404/cytube-apk/releases/tag/nightly"
    const val LATEST_PAGE = "https://github.com/Hostu404/cytube-apk/releases/latest"

    /** A nightly build: "classless nightly 6 Nov". */
    fun isNightly(versionName: String): Boolean = versionName.contains(" nightly ")

    /** An update alert from GitHub's [release], if it came out after [installedAtMs]: "New nightly:
     *  7 Nov 2026" (the nightly's title, "Nightly 7 Nov 2026") or "New version: v1.9.0". */
    fun updateFrom(release: JSONObject, installedAtMs: Long, nightly: Boolean): AppAlert? {
        if (release.optBoolean("draft")) return null
        val published = runCatching { Instant.parse(release.text("published_at")) }.getOrNull() ?: return null
        if (published.toEpochMilli() <= installedAtMs) return null
        val name = release.text("name").ifBlank { release.text("tag_name") }.trim().take(60)
        val text = if (nightly) "New nightly: " + name.removePrefix("Nightly ").ifBlank { "out now" }
            else "New version: " + name.ifBlank { "out now" }
        return AppAlert("update:$published", text, if (nightly) NIGHTLY_PAGE else LATEST_PAGE, update = true)
    }

    /**
     * The messages in alerts.json for this version on [today] (the phone's date), at most [MAX_NEWS]:
     *
     *     { "alerts": [ { "id": "maint-11-08", "text": "Server maintenance tonight",
     *                     "link": "https://…", "until": "2026-11-09",
     *                     "minVersionCode": 20, "maxVersionCode": 23 } ] }
     *
     * [id] and [text] are needed (a new id shows again to those who dismissed the old one); the rest
     * are optional: a link to open (http or https only), the last day it's shown, and the versions
     * (versionCode) it's for. Text is plain, at most [MAX_TEXT] characters.
     *
     * Each id once (the first entry with it wins): the home page keys its list on the id, and a list
     * with one id twice can't be drawn at all — it would crash the home page on every launch, with
     * the alerts kept on the phone and not looked for again for hours.
     */
    fun newsFrom(json: JSONObject, today: LocalDate, versionCode: Int): List<AppAlert> {
        val list = json.optJSONArray("alerts") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val a = list.optJSONObject(i) ?: return@mapNotNull null
            val id = a.text("id").trim()
            val text = a.text("text").trim().take(MAX_TEXT)
            if (id.isEmpty() || text.isEmpty()) return@mapNotNull null
            if (a.has("minVersionCode") && versionCode < a.optInt("minVersionCode")) return@mapNotNull null
            if (a.has("maxVersionCode") && versionCode > a.optInt("maxVersionCode")) return@mapNotNull null
            val until = a.text("until").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return@mapNotNull null }
            if (until != null && today.isAfter(until)) return@mapNotNull null
            val link = a.text("link").trim().takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
            AppAlert("news:$id", text, link, until = until)
        }.distinctBy { it.id }.take(MAX_NEWS)
    }

    /** [key]'s text; "" for one that's missing or null. (Android's optString gives a null as the
     *  word "null"; the desktop library the unit tests run on doesn't, so this keeps them alike.) */
    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)

    fun encode(alerts: List<AppAlert>): String = JSONArray().apply {
        for (a in alerts) put(JSONObject().apply {
            put("id", a.id); put("text", a.text); put("update", a.update)
            a.link?.let { put("link", it) }
            a.until?.let { put("until", it.toString()) }
        })
    }.toString()

    /** What [encode] kept, each id once (see [newsFrom]): a list saved by an earlier version could
     *  still have one twice. */
    fun decode(s: String?): List<AppAlert> {
        if (s.isNullOrBlank()) return emptyList()
        val list = runCatching { JSONArray(s) }.getOrNull() ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val a = list.optJSONObject(i) ?: return@mapNotNull null
            AppAlert(
                id = a.text("id"), text = a.text("text"),
                link = a.text("link").takeIf { it.isNotBlank() }, update = a.optBoolean("update"),
                until = a.text("until").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            )
        }.distinctBy { it.id }
    }
}
