package com.cytube.mobile.net

/**
 * CyTube's login cookie is `name:expiry:salt:hash:rank` (the server's
 * session.js genSession; verifySession also takes the older form without
 * `rank`), expiry in epoch milliseconds.
 *
 * The server sets it as a signed cookie (webserver.js setAuthCookie,
 * `signed: true`), so what comes off Set-Cookie is Express's signed form,
 * URL-encoded: `s:` + that value + `.` + a signature, as in
 * `s%3Ajon%3A1800000000000%3A<salt>%3A<hash>%3A1.<signature>`. Reading the
 * second field of that took the username for the expiry: never readable for
 * an ordinary name, and for an all-digit one (CyTube allows those) a time in
 * 1970, so the login always looked expired. The unsigned form is read too.
 */
object SessionCookie {
    /** When [cookie] stops being accepted, or null if it can't be read. */
    fun expiresAtMs(cookie: String): Long? {
        // Only the separators need decoding: the expiry itself is digits.
        val parts = cookie.trim().replace("%3A", ":", ignoreCase = true).split(':')
        // Signed: "s", then the value's fields, the last with ".<signature>"
        // on it (nothing else in the value has a '.': the salt and hash are
        // Base64, the rank a whole number). Told apart that way rather than
        // by the "s" alone, which is also a valid username.
        val signed = parts.size >= 5 && parts[0] == "s" && '.' in parts.last()
        val fields = if (signed) parts.drop(1) else parts
        if (fields.size !in 4..5) return null
        return fields[1].toLongOrNull()?.takeIf { it > 0 }
    }

    fun isExpired(cookie: String, nowMs: Long): Boolean =
        expiresAtMs(cookie)?.let { nowMs > it } ?: false
}
