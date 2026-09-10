package com.cytube.mobile.ui.channel

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.cytube.mobile.net.EmoteSet
import com.cytube.mobile.net.resolveMediaUrl
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
    /** Tags the span of a spoiler (CyTube's own `<span class="spoiler">`)
     *  with its 0-based index among this message's spoilers, in document
     *  order — see [render]'s revealedSpoilers and ChatRow's tap handling. */
    const val SPOILER_TAG = "SPOILER"

    private val GREENTEXT = Color(0xFF789922)
    private val SPOILER = Color(0xFF444444)

    /** A message of just one to this many emotes (no other visible text)
     *  renders them big instead of at inline-text size — see [Rendered.soloEmoteCount].
     *  Beyond this it falls back to normal inline size; a wall of a dozen
     *  giant custom images is a worse read than a wall of small ones. */
    private const val MAX_SOLO_EMOTES = 4

    /** A single message never renders more than this many emotes — beyond it,
     *  additional emote codes are left out entirely (not even as alt text).
     *  Caps the cost of a deliberately emote-spammed line and keeps chat rows
     *  from growing unbounded; ordinary messages never come close to this. */
    private const val MAX_EMOTES_PER_MESSAGE = 3

    /** Threaded through [walk]/[styled] for the lifetime of a single
     *  [render] call so the cap applies per-message, not globally. */
    private class EmoteBudget(var remaining: Int = MAX_EMOTES_PER_MESSAGE) {
        /** True (and consumes one) iff an emote is still allowed here. */
        fun take(): Boolean = if (remaining > 0) { remaining--; true } else false
    }

    /** Everything [walk]/[styled]/[spoiler] need for the lifetime of a single
     *  [render] call, bundled up so adding a new option (like the spoiler
     *  support below) doesn't mean growing every function's parameter list
     *  one more time. */
    private class RenderCtx(
        val linkColor: Color,
        val showImages: Boolean,
        val dropImages: Boolean,
        val images: MutableList<String>,
        val budget: EmoteBudget,
        val revealSpoilers: Boolean,
        val revealedSpoilers: Set<Int>
    ) {
        /** Assigns each spoiler span encountered its index, in document
         *  order; also doubles as the final spoiler count once walking
         *  finishes (see [render]'s Rendered.spoilerCount). */
        var spoilerIndex = 0
    }

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
        val soloEmoteCount: Int = 0,
        /** How many distinct `[spoiler]` spans this message has. 0 for the
         *  overwhelming majority of messages — lets ChatRow skip allocating
         *  any per-message reveal state for those. */
        val spoilerCount: Int = 0
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
        dropImages: Boolean = false,
        /**
         * Real CyTube hides a `[spoiler]` span by matching its text color to
         * the background and reveals it on :hover — there's no hover on a
         * touchscreen, so ChatRow implements tap-to-reveal instead (see
         * SPOILER_TAG below). On TV there's no tap either, and a D-pad has
         * no real equivalent of it, so this bypasses all of that and renders
         * spoilers as plain, already-visible text — same as CyTube's own
         * behavior on a platform with no hover/tap at all. Takes priority
         * over [revealedSpoilers].
         */
        revealSpoilers: Boolean = false,
        /**
         * Indices (0-based, document order) of this message's spoiler spans
         * the user has already tapped open — see ChatRow's per-message
         * reveal state. Ignored when [revealSpoilers] is true.
         */
        revealedSpoilers: Set<Int> = emptySet()
    ): Rendered {
        // Emote substitution happens here, exactly as the official client does
        // it on receipt (util.js:1508). Needed whenever emotes will be shown OR
        // dropped (dropImages still has to recognise them as emotes first);
        // skipped only when neither applies, which is the common chat case.
        val html = if ((showImages || dropImages) && emotes.mightMatch(raw)) emotes.apply(raw) else raw

        // Fast path. The large majority of chat lines are plain text with no
        // markup and no entities; running those through a full HTML parse is
        // pure overhead on the hottest path in the app. A spoiler always
        // arrives as a <span>, so plain text here can never contain one.
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
        val ctx = RenderCtx(
            linkColor, showImages, dropImages, images, EmoteBudget(), revealSpoilers, revealedSpoilers
        )

        val annotated = buildAnnotatedString {
            if (greentext) pushStyle(SpanStyle(color = GREENTEXT))
            walk(body, this, ctx)
            if (greentext) pop()
        }
        return Rendered(annotated, images, soloEmoteCount(annotated, images), ctx.spoilerIndex)
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

    private fun walk(node: Node, builder: AnnotatedString.Builder, ctx: RenderCtx) {
        for (child in node.childNodes()) {
            when (child) {
                is TextNode -> builder.appendLinkified(child.text(), ctx.linkColor)
                is Element -> when (child.tagName().lowercase()) {
                    "br" -> builder.append("\n")

                    // Never rendered, and never worth recursing into: their
                    // text content isn't meant to be read (script) or is
                    // formatting the site page itself has no business showing
                    // us verbatim (style).
                    "script", "style" -> Unit

                    "img" -> {
                        // resolveMediaUrl covers plain chat/MOTD <img> tags
                        // that never went through the Emote model at all
                        // (e.g. admin-authored MOTD HTML) — emote-sourced
                        // ones are already absolute by the time they get
                        // here (see Emote.from), so this is a no-op for those.
                        val src = resolveMediaUrl(child.attr("src"))
                        val alt = child.attr("alt").ifBlank { child.attr("title") }
                        when {
                            ctx.dropImages -> Unit
                            src.isBlank() -> if (alt.isNotBlank()) builder.append(alt)
                            // Cap applies to real emote images (a src is
                            // present) whether or not they're drawn as
                            // pictures — a message with images off but 40
                            // emote codes in it is exactly the spam this
                            // guards against too. Once the budget is spent,
                            // the rest are left out entirely per spec (no
                            // alt-text placeholder either).
                            !ctx.budget.take() -> Unit
                            ctx.showImages -> {
                                ctx.images.add(src)
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
                            SpanStyle(color = ctx.linkColor, textDecoration = TextDecoration.Underline)
                        )
                        walk(child, builder, ctx)
                        builder.pop(); builder.pop()
                    }

                    "strong", "b" -> styled(SpanStyle(fontWeight = FontWeight.Bold), child, builder, ctx)
                    "em", "i" -> styled(SpanStyle(fontStyle = FontStyle.Italic), child, builder, ctx)
                    "s", "strike", "del" -> styled(SpanStyle(textDecoration = TextDecoration.LineThrough), child, builder, ctx)
                    "u" -> styled(SpanStyle(textDecoration = TextDecoration.Underline), child, builder, ctx)
                    "code" -> styled(SpanStyle(fontFamily = FontFamily.Monospace), child, builder, ctx)

                    "span", "div", "p" -> {
                        val cls = child.className()
                        when {
                            cls.contains("greentext") -> styled(SpanStyle(color = GREENTEXT), child, builder, ctx)
                            cls.contains("spoiler") -> spoiler(child, builder, ctx)
                            else -> walk(child, builder, ctx)
                        }
                    }

                    else -> walk(child, builder, ctx)
                }
            }
        }
    }

    /**
     * A `[spoiler]`/`<span class="spoiler">` span. Always gets a SPOILER_TAG
     * annotation carrying its own index, whether hidden or not, so ChatRow
     * can find "which spoiler is under this tap" even for one that's already
     * revealed (tapping it again re-hides it — see ChatRow). Only the style
     * changes: color-matched-to-background (indistinguishable from a solid
     * blank run) while hidden, no special style at all once revealed.
     */
    private fun spoiler(child: Element, builder: AnnotatedString.Builder, ctx: RenderCtx) {
        val index = ctx.spoilerIndex++
        val hidden = !ctx.revealSpoilers && index !in ctx.revealedSpoilers
        builder.pushStringAnnotation(SPOILER_TAG, index.toString())
        builder.pushStyle(if (hidden) SpanStyle(background = SPOILER, color = SPOILER) else SpanStyle())
        walk(child, builder, ctx)
        builder.pop(); builder.pop()
    }

    private fun styled(style: SpanStyle, child: Element, builder: AnnotatedString.Builder, ctx: RenderCtx) {
        builder.pushStyle(style)
        walk(child, builder, ctx)
        builder.pop()
    }

    fun linkAt(text: AnnotatedString, offset: Int): String? =
        text.getStringAnnotations(LINK_TAG, offset, offset).firstOrNull()?.item

    fun emoteAt(text: AnnotatedString, offset: Int): String? =
        text.getStringAnnotations(EMOTE_TAG, offset, offset).firstOrNull()?.item

    /** The tapped spoiler's own index (see SPOILER_TAG), or null if the tap
     *  didn't land on one — ChatRow flips that index in or out of its
     *  per-message revealed set, which re-renders this same message with
     *  that one spoiler shown or hidden again. */
    fun spoilerAt(text: AnnotatedString, offset: Int): Int? =
        text.getStringAnnotations(SPOILER_TAG, offset, offset).firstOrNull()?.item?.toIntOrNull()

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
