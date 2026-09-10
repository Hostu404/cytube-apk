package com.cytube.mobile.di

import android.content.Context
import com.cytube.mobile.data.AuthRepository
import com.cytube.mobile.data.ChannelIndexRepository
import com.cytube.mobile.net.CyTubeClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Deliberately a plain object rather than a DI framework. The graph is four
 * objects deep; Hilt would be more ceremony than the app has complexity.
 */
object Graph {

    const val BASE_URL = CyTubeClient.DEFAULT_BASE_URL

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // AuthRepository's login POST needs to read Set-Cookie off the 302 that
    // CyTube's /login returns, which OkHttp would otherwise follow and discard
    // before that header is ever seen. Scoped to its own client — built off
    // `http` so it still shares its connection pool and dispatcher — rather
    // than disabling redirects on `http` itself, which every other consumer
    // (media byte fetches, the channel-index scrape, Drive/YouTube resolving)
    // shares and none of which want redirects suppressed.
    private val authHttp: OkHttpClient by lazy {
        http.newBuilder().followRedirects(false).build()
    }

    private var authRepo: AuthRepository? = null

    fun auth(context: Context): AuthRepository =
        authRepo ?: AuthRepository(context.applicationContext, authHttp, BASE_URL).also { authRepo = it }

    val channelIndex: ChannelIndexRepository by lazy {
        ChannelIndexRepository(http, BASE_URL)
    }

    fun newClient(context: Context): CyTubeClient = CyTubeClient(http, BASE_URL)
}
