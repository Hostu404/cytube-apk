package com.cytube.mobile.data

/**
 * CyTube's own channel-name charset — letters, digits, underscore, hyphen.
 * (The server only creates names of up to 30 characters; the length allowed
 * here is looser on purpose, since what matters is that nothing else can
 * get into a URL built from it.) Shared by every place a user-supplied string is treated as a channel
 * name (the home screen's direct-entry box, a `cytu.be/r/<channel>` deep link, and the
 * channel screen's own join validation) so the three copies of this regex can't
 * quietly drift apart from one another.
 */
val CHANNEL_NAME_REGEX = Regex("^[A-Za-z0-9_-]{1,100}$")

/**
 * [name] as CyTube itself has it: the server lowercases every channel name
 * it's given (user.js handleJoinChannel, server.js getChannel), so "/r/Foo"
 * and "/r/foo" are one channel. Everything the app keys on a channel
 * (favourites, recents, its compatibility mode, picture and recent emotes)
 * goes through this, so one channel isn't kept as two.
 */
fun canonicalChannelName(name: String): String = name.lowercase()
