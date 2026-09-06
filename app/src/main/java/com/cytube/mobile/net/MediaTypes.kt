package com.cytube.mobile.net

/**
 * Layer 2 of the playback stack: media/provider detection.
 *
 * Type codes are taken verbatim from CyTube's player/update.coffee TYPE_MAP.
 * This file answers only "what is this?" — it never touches a player.
 */
object MediaTypes {

    /** The media id is itself a playable URL. */
    private val PLAYABLE_ID = setOf("fi", "hl", "rt")

    val LABELS = mapOf(
        "yt" to "YouTube", "vi" to "Vimeo", "dm" to "Dailymotion",
        "gd" to "Google Drive", "fi" to "Raw file", "sc" to "SoundCloud",
        "li" to "Livestream", "tw" to "Twitch", "tv" to "Twitch",
        "cu" to "Custom embed", "rt" to "RTMP", "hl" to "HLS",
        "sb" to "Streamable", "tc" to "Twitch clip", "cm" to "Custom manifest",
        "pt" to "PeerTube", "bc" to "Embed", "bn" to "Embed",
        "od" to "Odysee", "nv" to "Niconico"
    )

    /** Layer 3: player selection. One decision, in one place. */
    enum class Player {
        /** Media3 on a URL we already have. */
        NATIVE,
        /** Media3 on a URL NewPipeExtractor resolves first. */
        NEWPIPE,
        /** Media3 on a URL GoogleDriveResolver resolves first. */
        GDRIVE,
        /**
         * The provider's own embeddable iframe URL (meta.embed.src), hosted
         * in a small WebView that's just the video surface — chat, playlist
         * and sync all stay native around it. This is what AUTOMATIC was
         * always documented to prefer over WEB (see resolvePlayer's comment)
         * for cu/bc/bn items, but nothing ever actually routed to it before:
         * playerFor had no branch for it, so every one of these fell all the
         * way through to the whole-page WEB fallback instead.
         */
        EMBED,
        /** The real CyTube page in a WebView. Last resort. */
        WEB,
        /**
         * Nothing native can play this item and the user has not (yet) agreed
         * to fall back to WebView. We never switch on our own — see
         * ChannelViewModel.choosePlayerAndOffer.
         */
        UNAVAILABLE
    }

    /**
     * `hasDirect` means meta.direct carried real source URLs — either because
     * the type never needed anything else (cm, vi), or because someone in the
     * channel is running the old Google Drive userscript and it already
     * populated meta.direct for everyone. If it's there, use it; that's a
     * live channel-provided source and always wins over resolving our own.
     *
     * Otherwise Google Drive gets its own app-side resolution (GoogleDriveResolver)
     * rather than falling back to WebView — see that class for why the
     * userscript's own approach (a legacy Google endpoint) isn't used here.
     *
     * `embedSrc` (meta.embed.src) is what cu/bc/bn carry instead of a direct
     * source: a URL meant to be dropped straight into an iframe. Routing
     * those to EMBED rather than WEB is what lets a custom-embed channel
     * (e.g. one streaming from an 8chan.tv "?embedded=True" view link) play
     * with native chat/playlist/sync intact, instead of needing the whole
     * CyTube page loaded in Compatibility View just to show one iframe.
     */
    fun playerFor(type: String, hasDirect: Boolean, embedSrc: String? = null): Player = when {
        type in PLAYABLE_ID -> Player.NATIVE
        hasDirect -> Player.NATIVE          // cm, vi, gd-with-userscript-metadata
        type == "yt" -> Player.NEWPIPE
        type == "gd" -> Player.GDRIVE
        !embedSrc.isNullOrBlank() -> Player.EMBED
        else -> Player.WEB
    }

    fun label(type: String): String = LABELS[type] ?: type
}
