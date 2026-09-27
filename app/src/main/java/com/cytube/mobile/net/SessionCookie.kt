package com.cytube.mobile.net

/**
 * CyTube's login cookie is `name:expiry:salt:hash:rank` (the server's
 * session.js), expiry in epoch milliseconds, sometimes URL-encoded as it
 * comes off Set-Cookie.
 */
object SessionCookie {
    /** When [cookie] stops being accepted, or null if it can't be read. */
    fun expiresAtMs(cookie: String): Long? {
        val parts = cookie.replace("%3A", ":", ignoreCase = true).split(':')
        if (parts.size < 4) return null
        return parts[1].toLongOrNull()
    }

    fun isExpired(cookie: String, nowMs: Long): Boolean =
        expiresAtMs(cookie)?.let { nowMs > it } ?: false
}
