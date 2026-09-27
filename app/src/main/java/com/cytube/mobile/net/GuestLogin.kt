package com.cytube.mobile.net

/**
 * Making sense of a refused guest login (the server's user.js guestLogin).
 * The server allows one guest login per IP address per minute, so a quick
 * reconnect is refused even with a perfectly good name. That needs a wait
 * and the same name again, not a different name, which would be refused
 * for the same reason.
 */
object GuestLogin {
    private val COOLDOWN = Regex("one per IP address per (\\d+) seconds", RegexOption.IGNORE_CASE)

    /** How long to wait before trying the same name again when [error] is
     *  the per-IP limit; null for any other refusal. */
    fun cooldownSeconds(error: String?): Int? =
        error?.let { COOLDOWN.find(it) }?.groupValues?.get(1)?.toIntOrNull()
}
