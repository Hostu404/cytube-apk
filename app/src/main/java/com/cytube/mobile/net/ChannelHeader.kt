package com.cytube.mobile.net

import org.jsoup.Jsoup

/**
 * The picture drawn behind the top of the channel screen (behind the channel
 * name and buttons, the video, its title and the channel notice), so a
 * channel looks like its own page there:
 *
 *  1. The channel's background: from its own CSS ([ChannelStyle.pageBackground]),
 *     else from the stylesheets it @imports or links as its external CSS,
 *     else one its script sets ([scriptBackground]).
 *  2. Failing that, the channel notice's background: one the CSS puts on
 *     the notice ([ChannelStyle.motdBackground]), else one set on the
 *     notice's own elements ([motdBackground]). Never a picture placed in
 *     the notice: those are its content (a poster, a logo), not a background.
 *
 * Like the rest of net/, all of it is untrusted: this only ever returns https
 * addresses, or null for none.
 */
object ChannelHeader {

    /** The notice's HTML and the channel's script are cut off past this. */
    private const val MAX_CHARS = 200_000

    /** How far before an address in the script "background" may be. */
    private const val SCRIPT_CONTEXT_CHARS = 40

    /** A web address in the script. */
    private val SCRIPT_ADDRESS = Regex("""https?://[^'"`)\\\s]+""", RegexOption.IGNORE_CASE)

    /** "background", or "bg" as a name or part of one (bgImage, channelBg,
     *  BG), but not inside a word (debug). */
    private val BACKGROUND_WORD = Regex("""(?i:background)|(?<![A-Za-z])(?:bg|BG|Bg)|[a-z0-9]Bg""")

    /** Addresses that are no picture, whatever comes before them (a
     *  background song, a background video). */
    private val NOT_PICTURES = Regex("""\.(mp3|ogg|oga|wav|flac|m4a|aac|mp4|webm|mkv|mov|m3u8|mpd|js|css|html?|json)$""", RegexOption.IGNORE_CASE)

    /** A stylesheet the script adds to the page itself (a <link> to a
     *  .css, or the like). */
    private val SCRIPT_STYLESHEET = Regex("""https?://[^'"`)\\\s]+?\.css(?:\?[^'"`)\\\s]*)?(?=['"`)\\\s])""", RegexOption.IGNORE_CASE)

    /** At most this many stylesheets the script adds are read. */
    private const val MAX_SCRIPT_STYLESHEETS = 4

    /**
     * The picture to show: [styles] are the channel's stylesheets, its own
     * CSS first, then the ones it imports or links; [script] is its script;
     * [page] the channel page's address, for the notice's relative ones.
     */
    fun image(styles: List<ChannelStyle>, script: String, motdHtml: String, page: String? = null): ChannelBackground? =
        candidates(styles, script, motdHtml, page).firstOrNull()

    /** Every picture there is to show, best first (as [image] picks), so
     *  that when one won't load the next can be tried. */
    fun candidates(styles: List<ChannelStyle>, script: String, motdHtml: String, page: String? = null): List<ChannelBackground> =
        (styles.mapNotNull { it.pageBackground } +
            listOfNotNull(scriptBackground(script)?.let { ChannelBackground(it) }) +
            styles.mapNotNull { it.motdBackground } +
            listOfNotNull(motdBackground(motdHtml, page)))
            .distinctBy { it.url }

    /** Stylesheets the channel's script adds to the page (a <link> to a
     *  .css, or the like), which its background can be in. */
    fun scriptStylesheets(js: String): List<String> {
        if (js.isBlank()) return emptyList()
        return SCRIPT_STYLESHEET.findAll(js.take(MAX_CHARS))
            .mapNotNull { webAddress(it.value) }
            .filter(::worthReading)
            .distinct()
            .take(MAX_SCRIPT_STYLESHEETS)
            .toList()
    }

    /**
     * A background the channel's script sets: the first web address in it
     * with "background" or "bg" just before it. That's a url(…) it writes in
     * a background style ($("body").css("background-image", "url(…)")) as
     * much as an address it gives a background setting
     * (window.Background_Image = "…", as theme scripts such as Nexus are
     * configured). Not a song or a video. The script is never run, only read.
     */
    fun scriptBackground(js: String): String? {
        if (js.isBlank()) return null
        val text = js.take(MAX_CHARS)
        return SCRIPT_ADDRESS.findAll(text)
            .filter { m ->
                val before = text.substring((m.range.first - SCRIPT_CONTEXT_CHARS).coerceAtLeast(0), m.range.first)
                BACKGROUND_WORD.containsMatchIn(before)
            }
            .mapNotNull { m -> webAddress(m.value)?.takeIf { !NOT_PICTURES.containsMatchIn(it.substringBefore('?')) } }
            .firstOrNull()
    }

    /** A background set on one of the notice's own elements
     *  (style="background: url(…) right"), with its position; [page] is
     *  the channel page's address, for a relative one. */
    fun motdBackground(html: String, page: String? = null): ChannelBackground? {
        if (html.isBlank()) return null
        val body = runCatching { Jsoup.parseBodyFragment(html.take(MAX_CHARS)).body() }
            .getOrNull() ?: return null
        for (el in body.select("[style]")) {
            val decls = declarations(el.attr("style"))
            backgroundUrl(decls, null, page)?.let { return ChannelBackground.from(it, decls) }
        }
        return null
    }
}
