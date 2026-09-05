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
            .followRedirects(false)   // we need to read Set-Cookie on the 302
            .build()
    }

    private var authRepo: AuthRepository? = null

    fun auth(context: Context): AuthRepository =
        authRepo ?: AuthRepository(context.applicationContext, http, BASE_URL).also { authRepo = it }

    val channelIndex: ChannelIndexRepository by lazy {
        ChannelIndexRepository(http, BASE_URL)
    }

    fun newClient(context: Context): CyTubeClient = CyTubeClient(http, BASE_URL)
}
