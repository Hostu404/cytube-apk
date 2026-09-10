package com.cytube.mobile.data

/**
 * CyTube's own channel-name charset — letters, digits, underscore, hyphen, 1-100
 * characters. Shared by every place a user-supplied string is treated as a channel
 * name (the home screen's direct-entry box, a `cytu.be/r/<channel>` deep link, and the
 * channel screen's own join validation) so the three copies of this regex can't
 * quietly drift apart from one another.
 */
val CHANNEL_NAME_REGEX = Regex("^[A-Za-z0-9_-]{1,100}$")
