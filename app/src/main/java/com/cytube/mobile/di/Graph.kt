package com.cytube.mobile.di

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.cytube.mobile.data.AuthRepository
import com.cytube.mobile.data.ChannelIndexRepository
import com.cytube.mobile.net.CyTubeClient
import com.cytube.mobile.net.IpSessionStore
import com.cytube.mobile.ui.isTvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Deliberately a plain object rather than a DI framework. The graph is four
 * objects deep; Hilt would be more ceremony than the app has complexity.
 *
 * @OptIn(UnstableApi::class) at the object level: Media3's whole
 * datasource.cache package (SimpleCache, Cache, LeastRecentlyUsedCacheEvictor)
 * and StandaloneDatabaseProvider are marked @UnstableApi upstream — same
 * mechanism NativePlayerHandle already opts into for CacheDataSource.
 */
@OptIn(UnstableApi::class)
object Graph {

    const val BASE_URL = CyTubeClient.DEFAULT_BASE_URL

    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // AuthRepository's login POST needs to read Set-Cookie off /login's own
    // response. That's a 200 as the app calls it (a Referer of /login means
    // "no redirect" to the server), but CyTube redirects when given somewhere
    // to go back to, and OkHttp following that would drop the header. Scoped
    // to its own client — built off `http`, so it shares its connection pool
    // and dispatcher — rather than disabling redirects on `http` itself,
    // which the channel-index scrape and the resolvers share and none of
    // which want redirects suppressed.
    private val authHttp: OkHttpClient by lazy {
        http.newBuilder().followRedirects(false).build()
    }

    // Dedicated to media byte fetches (NativePlayerHandle's data source).
    // `http`'s 30s read timeout is plenty for API calls, but a slow patch of
    // network mid-download on a large video file can go quiet for longer
    // than that on a single read without the stream being dead, and a
    // timeout there is a playback error (and the WebView-fallback prompt)
    // rather than a stutter. Built off `http`, so it shares its dispatcher,
    // but with its own connection pool.
    val mediaHttp: OkHttpClient by lazy {
        http.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(8, 2, TimeUnit.MINUTES))
            .build()
    }

    @Volatile private var authRepo: AuthRepository? = null

    /** First call opens the saved login (AuthStore: a Keystore round-trip,
     *  slow on low-end TV sticks, and once, moving an earlier version's
     *  login), so callers make that first call off the main thread (see
     *  HomeViewModel, ChannelViewModel, LoginScreen).
     *  Synchronized so two first calls on different threads can't build two
     *  instances over the same prefs file. */
    fun auth(context: Context): AuthRepository =
        authRepo ?: synchronized(this) {
            authRepo ?: AuthRepository(context.applicationContext, authHttp, BASE_URL).also { authRepo = it }
        }

    // The client handed over as a function, so making the repository (which
    // the home screen does as it's first drawn) doesn't build the client on
    // the main thread: it's asked for when the list is fetched, off it.
    val channelIndex: ChannelIndexRepository by lazy {
        ChannelIndexRepository({ http }, BASE_URL)
    }

    /** Builds [http] in the background, so it's ready by the time the first
     *  screen's requests (made off the main thread) want it; see CyTubeApp. */
    fun prepareHttp() {
        appScope.launch(Dispatchers.IO) { http.dispatcher }
    }

    fun newClient(context: Context): CyTubeClient = CyTubeClient(http, BASE_URL, savedIpSession(context))

    /** CyTube's ip-session cookie, kept between runs (see IpSessionCookies). */
    @Volatile private var ipSessionStore: IpSessionStore? = null

    private fun savedIpSession(context: Context): IpSessionStore =
        ipSessionStore ?: synchronized(this) {
            ipSessionStore ?: SavedIpSession(context.applicationContext).also { ipSessionStore = it }
        }

    /** For small jobs that must finish even if the screen that started them
     *  goes away (e.g. saving a setting as the user leaves). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** This run's on-disk media cache, once [prepareMediaCache] has it ready. */
    @Volatile private var mediaCacheInstance: Cache? = null
    private val mediaCacheStarted = AtomicBoolean(false)

    /**
     * Sets up the on-disk cache of media bytes (see NativePlayerHandle) in
     * the background, once per run of the app. Called as the app starts, so
     * it's ready long before the first video. The work itself is done on an
     * IO thread, never the main one: it reads and deletes files.
     *
     * For this run only: whatever an earlier run cached is deleted first.
     * What the cache is for all happens within a run: seeking back, playing
     * on after a dropped connection, a quality switch back, video coming
     * back on after the app was in the background, a channel rejoined — each
     * reads again what was just downloaded. Kept across runs, it would only
     * risk a file changed on its server since (the same address, different
     * bytes) being played back mixed in with the old one.
     *
     * Sized for the device (see [mediaCacheBudgetBytes]), least recently
     * used evicted first. In the app's cache folder: no permission needed,
     * and Android may clear it when storage runs low. Media3 allows one
     * SimpleCache per folder for the life of the process, which is why
     * there's only ever this one.
     */
    fun prepareMediaCache(context: Context) {
        if (!mediaCacheStarted.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        appScope.launch(Dispatchers.IO) {
            mediaCacheInstance = try {
                createMediaCache(appContext)
            } catch (e: Exception) {
                // No cache this run: playback reads straight from the network.
                Log.w(TAG, "no media cache this run: ${e.message}", e)
                null
            }
        }
    }

    /**
     * This run's media cache, or null while it's still being set up (or
     * when there's no room for one); the video is then played without it.
     * Never waits, so it's safe on the main thread, where loads ask for it.
     */
    fun mediaCache(context: Context): Cache? {
        prepareMediaCache(context)
        return mediaCacheInstance
    }

    private fun createMediaCache(appContext: Context): Cache? {
        val dir = File(appContext.cacheDir, MEDIA_CACHE_DIR)
        val database = StandaloneDatabaseProvider(appContext)
        // An earlier run's cache (its files and its index in the database).
        SimpleCache.delete(dir, database)
        val budget = mediaCacheBudgetBytes(appContext.cacheDir.usableSpace, isTvDevice(appContext))
        if (budget == 0L) {
            Log.i(TAG, "no media cache this run: not enough free space")
            return null
        }
        return SimpleCache(dir, LeastRecentlyUsedCacheEvictor(budget), database)
    }

    private const val TAG = "CyTubeGraph"
    private const val MEDIA_CACHE_DIR = "media"
}

/**
 * CyTube's ip-session cookie (see IpSessionCookies) in a preferences file of
 * its own, so a channel's wait for new visitors counts from the first visit,
 * not from each run of the app, as a browser keeps it. Read and written only
 * off the main thread (IpSessionCookies does both on an IO thread).
 */
private class SavedIpSession(private val context: Context) : IpSessionStore {
    private val prefs by lazy { context.getSharedPreferences("cytube_ip_session", Context.MODE_PRIVATE) }
    override fun load(): String? = prefs.getString(KEY, null)
    override fun save(value: String) { prefs.edit { putString(KEY, value) } }
    private companion object { const val KEY = "cookie" }
}

/** The most the media cache keeps on a phone or tablet. */
internal const val PHONE_MEDIA_CACHE_MAX_BYTES = 1_024L * 1024L * 1024L // 1 GB
/** The most it keeps on a TV, whose storage is small (a Fire TV Stick has
 *  8 GB in all): still minutes of video at the bitrates it plays. */
internal const val TV_MEDIA_CACHE_MAX_BYTES = 256L * 1024L * 1024L
/** Below this there's no point to one: no cache at all. */
internal const val MIN_MEDIA_CACHE_BYTES = 32L * 1024L * 1024L

/**
 * How big this run's media cache may grow, given [freeBytes] free in the
 * app's cache folder: the device's ceiling, but never more than a quarter
 * of the free space, and 0 (no cache) when that leaves too little to help.
 */
internal fun mediaCacheBudgetBytes(freeBytes: Long, isTv: Boolean): Long {
    val ceiling = if (isTv) TV_MEDIA_CACHE_MAX_BYTES else PHONE_MEDIA_CACHE_MAX_BYTES
    val budget = minOf(ceiling, freeBytes / 4)
    return if (budget < MIN_MEDIA_CACHE_BYTES) 0L else budget
}

/**
 * A client built off this one with request slots and connections of its own,
 * for bursts of side requests (emote images, YouTube lookups) that mustn't hold
 * up the video's own fetching. `newBuilder()` alone would share this client's
 * Dispatcher, whose limits (64 requests at once, 5 per host by default) apply
 * across every client sharing it.
 */
fun OkHttpClient.separate(maxRequestsPerHost: Int = 5, idleConnections: Int = 5): OkHttpClient.Builder =
    newBuilder()
        .dispatcher(Dispatcher().apply { this.maxRequestsPerHost = maxRequestsPerHost })
        .connectionPool(ConnectionPool(idleConnections, 5, TimeUnit.MINUTES))
