package com.cytube.mobile.net

/**
 * Resolves an image URL CyTube handed us — an emote's `image` field, or an
 * arbitrary `<img src>` inside chat/MOTD HTML — into something Coil/OkHttp
 * can actually fetch.
 *
 * Two shapes show up in real channel data that a bare `Coil.load(url)` can't
 * handle at all, both because they only ever worked by relying on a browser
 * to resolve them against the page they were embedded in:
 *  - protocol-relative ("//i.imgur.com/x.gif") — has no scheme, so OkHttp
 *    can't even parse it as a URL. Extremely common: this was the standard
 *    way to link an image without hardcoding http/https for years, and
 *    plenty of channels have emotes saved this way.
 *  - root-relative ("/uploads/emotes/foo.png") — an image hosted on the
 *    CyTube server itself; meaningless without `https://cytu.be` in front
 *    of it.
 * Anything already https:// is returned as-is, and http:// becomes https://.
 *
 * Every one of these values — an emote's saved image URL, or a raw `<img>`
 * tag inside chat/MOTD HTML — is channel/server-controlled, not something
 * this app generated, and it gets handed straight to Coil's image loader.
 * Coil has built-in support for schemes far beyond http/https/data —
 * `file://`, `content://`, `android.resource://` among them — so an
 * unvalidated src here would let a malicious or compromised channel make
 * this app's own chat/emote rendering read local app files or probe other
 * apps' exported content providers, just by posting a message or an emote
 * with a `file://`/`content://` "image". Only http/https/data (the schemes
 * the class doc above always documented as passed through, and none of
 * which can reach a local file or content provider — data: is inline bytes
 * with no fetch at all) ever reach Coil; anything else — `javascript:`,
 * `file:`, `content:`, or a malformed value with no recognisable scheme at
 * all once the relative-URL cases above are handled — is rejected to blank,
 * which every caller already treats as "no image" (falls back to alt text,
 * or is simply not fetched).
 */
fun resolveMediaUrl(url: String): String {
    // Surrounding whitespace, which a browser ignores, would otherwise hide
    // the scheme and get the image rejected.
    val trimmed = url.trim()
    val absolute = when {
        trimmed.startsWith("//") -> "https:$trimmed"
        trimmed.startsWith("/") -> CyTubeClient.DEFAULT_BASE_URL + trimmed
        else -> trimmed
    }
    val scheme = absolute.substringBefore(':', missingDelimiterValue = "").lowercase()
    return when (scheme) {
        // Fetched over https, as a browser does with an http image on an
        // https page like CyTube's: the app doesn't allow plain http at all.
        "http" -> "https" + absolute.substring(scheme.length)
        "https", "data" -> absolute
        else -> ""
    }
}
