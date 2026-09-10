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
 * Anything already absolute (http://, https://, data:) is returned as-is.
 */
fun resolveMediaUrl(url: String): String = when {
    url.startsWith("//") -> "https:$url"
    url.startsWith("/") -> CyTubeClient.DEFAULT_BASE_URL + url
    else -> url
}
