package com.cytube.mobile.ui.channel

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.cytube.mobile.net.EmoteSet
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * CyTube chat messages arrive as sanitized HTML, not plain text — chat filters
 * and emote substitution rewrite the body server-side (chat.js filterMessage).
 * So we walk the DOM and map the handful of tags CyTube's sanitizer permits.
 *
 * Emotes come through as <img>. Rather than fall back to the alt text, each one
 * becomes an inline content placeholder that ChatRow fills with the real image,
 * so emotes render in the flow of the sentence exactly as they do on the site.
 */
object ChatHtml {

    const val LINK_TAG = "URL"
    /** Tags the span of an inline emote with the shortcode to insert on tap —
     *  e.g. ":smile:", not the image URL appendInlineContent keys on. */
    const val EMOTE_TAG = "EMOTE"

    private val GREENTEXT = Color(0xFF789922)
    private val SPOILER = Color(0xFF444444)

    /** A message of just one to this many emotes (no other visible text)
     *  renders them big instead of at inline-text size — see [Rendered.soloEmoteCount].
     *  Beyond this it falls back to normal inline size; a wall of a dozen
     *  giant custom images is a worse read than a wall of small ones. */
    private const val MAX_SOLO_EMOTES = 4

    data class Rendered(
        val text: AnnotatedString,
        val imageUrls: List<String>,
        /**
         * Nonzero when the message is ENTIRELY emotes — no other visible
         * text — and there are few enough of them (see [MAX_SOLO_EMOTES]) to
         * render big without the row taking over the chat. A ":smile:" typed
         * mid-sentence never sets this; only a message that is just emotes,
         * the case where making them bigger doesn't cost anything (there's
         * no surrounding text for a taller row to crowd).
         */
        val soloEmoteCount: Int = 0
    )

    fun render(
        raw: String,
        greentext: Boolean,
        linkColor: Color,
        showImages: Boolean,
        emotes: EmoteSet = EmoteSet.EMPTY,
        /**
         * For content like the channel MOTD, which channels sometimes fill
         * with repeated emote codes as decoration: recognise emotes (so codes
         * get substituted the same way chat does) but discard every image
         * entirely instead of showing them or falling back to alt text. True
         * junk removal, not just "don't load images".
         */
        dropImages: Boolean = false
    ): Rendered {
        // Emote substitution happens here, exactly as the official client does
        // it on receipt (util.js:1508). Needed whenever emotes will be shown OR
        // dropped (dropImages still has to recognise them as emotes first);
        // skipped only when neither applies, which is the common chat case.
        val html = if ((showImages || dropImages) && emotes.mightMatch(raw)) emotes.apply(raw) else raw

        // Fast path. The large majority of chat lines are plain text with no
        // markup and no entities; running those through a full HTML parse is
        // pure overhead on the hottest path in the app.
        if (html.indexOf('<') < 0 && html.indexOf('&') < 0) {
            val plain = buildAnnotatedString {
                if (greentext) pushStyle(SpanStyle(color = GREENTEXT))
                appendLinkified(html, linkColor)
                if (greentext) pop()
            }
            return Rendered(plain, emptyList())
        }

        val images = mutableListOf<String>()
        val body = Jsoup.parseBodyFragment(html).body()

        val annotated = buildAnnotatedString {
            if (greentext) pushStyle(SpanStyle(color = GREENTEXT))
            walk(body, this, images, linkColor, showImages, dropImages)
            if (greentext) pop()
        }
        return Rendered(annotated, images, soloEmoteCount(annotated, images))
    }

    /** See [Rendered.soloEmoteCount]: nonzero only when every bit of visible
     *  text in the message is covered by an EMOTE_TAG span (i.e. nothing but
     *  emotes, no sentence around them) and there aren't too many of them. */
    private fun soloEmoteCount(annotated: AnnotatedString, images: List<String>): Int {
        if (images.isEmpty()) return 0
        val spans = annotated.getStringAnnotations(EMOTE_TAG, 0, annotated.text.length)
        if (spans.isEmpty() || spans.size > MAX_SOLO_EMOTES) return 0
        val covered = BooleanArray(annotated.text.length)
        for (span in spans) for (i in span.start until span.end) covered[i] = true
        val hasOtherText = annotated.text.withIndex().any { (i, ch) -> !covered[i] && !ch.isWhitespace() }
        return if (hasOtherText) 0 else spans.size
    }

    private fun walk(
        node: Node,
        builder: AnnotatedString.Builder,
        images: MutableList<String>,
        linkColor: Color,
        showImages: Boolean,
        dropImages: Boolean = false
    ) {
        for (child in node.childNodes()) {
            when (child) {
                is TextNode -> builder.appendLinkified(child.text(), linkColor)
                is Element -> when (child.tagName().lowercase()) {
                    "br" -> builder.append("\n")

                    // Never rendered, and never worth recursing into: their
                    // text content isn't meant to be read (script) or is
                    // formatting the site page itself has no business showing
                    // us verbatim (style).
                    "script", "style" -> Unit

                    "img" -> {
                        val src = child.attr("src")
                        val alt = child.attr("alt").ifBlank { child.attr("title") }
                        when {
                            dropImages -> Unit
                            src.isBlank() -> if (alt.isNotBlank()) builder.append(alt)
                            showImages -> {
                                images.add(src)
                                val code = alt.ifBlank { "[emote]" }
                                // The id IS the url, so ChatRow can build the
                                // content map straight from imageUrls. EMOTE_TAG
                                // separately carries the shortcode so tapping the
                                // emote can insert the same text the emote picker
                                // would, not the image URL.
                                builder.pushStringAnnotation(EMOTE_TAG, code)
                                builder.appendInlineContent(src, code)
                                builder.pop()
                            }
                            else -> builder.append(alt.ifBlank { "[emote]" })
                        }
                    }

                    "a" -> {
                        builder.pushStringAnnotation(LINK_TAG, child.attr("href"))
                        builder.pushStyle(
                            SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                        )
                        walk(child, builder, images, linkColor, showImages, dropImages)
                        builder.pop(); builder.pop()
                    }

                    "strong", "b" -> styled(SpanStyle(fontWeight = FontWeight.Bold), child, builder, images, linkColor, showImages, dropImages)
                    "em", "i" -> styled(SpanStyle(fontStyle = FontStyle.Italic), child, builder, images, linkColor, showImages, dropImages)
                    "s", "strike", "del" -> styled(SpanStyle(textDecoration = TextDecoration.LineThrough), child, builder, images, linkColor, showImages, dropImages)
                    "u" -> styled(SpanStyle(textDecoration = TextDecoration.Underline), child, builder, images, linkColor, showImages, dropImages)
                    "code" -> styled(SpanStyle(fontFamily = FontFamily.Monospace), child, builder, images, linkColor, showImages, dropImages)

                    "span", "div", "p" -> {
                        val cls = child.className()
                        val style = when {
                            cls.contains("greentext") -> SpanStyle(color = GREENTEXT)
                            cls.contains("spoiler") -> SpanStyle(background = SPOILER, color = SPOILER)
                            else -> null
                        }
                        if (style != null) {
                            styled(style, child, builder, images, linkColor, showImages, dropImages)
                        } else {
                            walk(child, builder, images, linkColor, showImages, dropImages)
                        }
                    }

                    else -> walk(child, builder, images, linkColor, showImages, dropImages)
                }
            }
        }
    }

    private fun styled(
        style: SpanStyle,
        child: Element,
        builder: AnnotatedString.Builder,
        images: MutableList<String>,
        linkColor: Color,
        showImages: Boolean,
        dropImages: Boolean = false
    ) {
        builder.pushStyle(style)
        walk(child, builder, images, linkColor, showImages, dropImages)
        builder.pop()
    }

    fun linkAt(text: AnnotatedString, offset: Int): String? =
        text.getStringAnnotations(LINK_TAG, offset, offset).firstOrNull()?.item

    fun emoteAt(text: AnnotatedString, offset: Int): String? =
        text.getStringAnnotations(EMOTE_TAG, offset, offset).firstOrNull()?.item

    /**
     * Only bare http/https runs become links. Text CyTube already wrapped in an
     * <a> is handled by the "a" branch above; this covers everything the server
     * left as plain text, which is most of it.
     */
    private val URL_PATTERN = Regex("https?://[^\\s<>\"']+")

    private fun AnnotatedString.Builder.appendLinkified(text: String, linkColor: Color) {
        if (!text.contains("http", ignoreCase = true)) {
            append(text)
            return
        }
        var last = 0
        for (m in URL_PATTERN.findAll(text)) {
            if (m.range.first > last) append(text.substring(last, m.range.first))
            // Trailing punctuation is almost never part of the URL.
            val url = m.value.trimEnd('.', ',', ')', ']', '!', '?', ';', ':')
            pushStringAnnotation(LINK_TAG, url)
            pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
            append(url)
            pop(); pop()
            append(m.value.substring(url.length))
            last = m.range.last + 1
        }
        if (last < text.length) append(text.substring(last))
    }

}
