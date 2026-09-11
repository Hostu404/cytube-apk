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
         * A single-video WebView — just the video surface, with chat,
         * playlist and sync all staying native around it. This is what
         * AUTOMATIC was always documented to prefer over WEB (see
         * resolvePlayer's comment) for cu/bc/bn items, but nothing ever
         * actually routed to it before: playerFor had no branch for it, so
         * every one of these fell all the way through to the whole-page WEB
         * fallback instead.
         *
         * The URL it loads (see MediaFrame.embedPlayableSrc) is not always a
         * dedicated embed link — meta.embed.src when the channel supplied
         * one, scuri (the item's original source URL) next, and for a
         * handful of providers CyTube's own resolution gives neither of
         * those, knownEmbedUrl's hardcoded pattern for that provider — all
         * of it skipped for Google Drive. That's what lets this cover any
         * provider whose own page is mostly-just-a-video-player, not only
         * cu/bc/bn.
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
     * the type never needed anything else (cm), or because someone in the
     * channel is running the old Google Drive userscript and it already
     * populated meta.direct for everyone. If it's there, use it; that's a
     * live channel-provided source and always wins over resolving our own.
     * (Vimeo does NOT belong on this list despite once being documented here —
     * mediaquery's vimeo.js only sets meta.direct via lookupAndExtract, but
     * CyTube's own get-info.js vi handler calls plain lookup/lookupAnonymous
     * instead, which never touches meta.direct at all. See knownEmbedUrl.)
     *
     * Otherwise Google Drive gets its own app-side resolution (GoogleDriveResolver)
     * rather than falling back to WebView — see that class for why the
     * userscript's own approach (a legacy Google endpoint) isn't used here.
     *
     * `embedPlayableSrc` (see MediaFrame) is meta.embed.src when the channel
     * carries one — cu/bc/bn's dedicated "drop straight into an iframe" URL —
     * or otherwise scuri, the item's original source URL, for anything else
     * that isn't Google Drive. Routing those to EMBED rather than WEB is what
     * lets a custom-embed channel (e.g. one streaming from an 8chan.tv
     * "?embedded=True" view link) — or any other item whose provider page is
     * mostly-just-a-video-player — play with native chat/playlist/sync
     * intact, instead of needing the whole CyTube page loaded in
     * Compatibility View just to show one video.
     */
    fun playerFor(
        type: String,
        hasDirect: Boolean,
        embedPlayableSrc: String? = null,
        /** MediaFrame.isLivestream — true when the server's own seconds
         *  field says this item has no fixed duration. Confirmed live: a yt
         *  item that fails through NEWPIPE with this set is a genuine
         *  NewPipeExtractor/Media3 limitation, not a fluke — the exact same
         *  stream played fine both through YouTube's own iframe (what EMBED
         *  loads for yt — see knownEmbedUrl) and through the whole real
         *  CyTube page (WEB), both of which hand live playback to YouTube's
         *  own player instead of resolving a raw manifest themselves. Going
         *  straight to EMBED for a live yt item skips the doomed NEWPIPE
         *  attempt (and whatever error it surfaces first) rather than
         *  reacting to its failure after the fact. */
        isLive: Boolean = false
    ): Player = when {
        type in PLAYABLE_ID -> Player.NATIVE
        hasDirect -> Player.NATIVE          // cm, gd-with-userscript-metadata
        type == "yt" && isLive && !embedPlayableSrc.isNullOrBlank() -> Player.EMBED
        type == "yt" -> Player.NEWPIPE
        type == "gd" -> Player.GDRIVE
        !embedPlayableSrc.isNullOrBlank() -> Player.EMBED
        else -> Player.WEB
    }

    fun label(type: String): String = LABELS[type] ?: type

    /**
     * A handful of providers CyTube resolves with no embeddable link
     * anywhere in meta — no meta.embed, no meta.direct, no scuri — even
     * though the provider itself has a stable, public, no-API-key embed
     * page. Checked directly against CyTube's own resolution source
     * (get-info.js and @cytube/mediaquery's provider modules), not assumed:
     *
     *  - yt (YouTube): only ever reaches here after NEWPIPE has already
     *    started and then failed (playerFor always prefers NEWPIPE outright
     *    for yt, before this is ever consulted — see reportPlaybackFailure
     *    for where this branch actually matters). youtube.com/embed/ is
     *    guaranteed to work for anything that made it into a CyTube
     *    playlist in the first place: mediaquery's youtube.js rejects
     *    non-embeddable videos at add-time (video.status.embeddable), so a
     *    yt item existing at all already proves this URL will play.
     *  - vi (Vimeo): mediaquery's vimeo.js only fills meta.direct via
     *    lookupAndExtract, but CyTube's get-info.js vi handler calls plain
     *    lookup instead, which never touches meta.direct — so despite an
     *    older comment on hasDirect above claiming otherwise, Vimeo items
     *    normally arrive with no playable source at all. Same embeddability
     *    guarantee as yt above: lookup rejects videos with
     *    embed_privacy !== 'anywhere' before the item can even be added.
     *    player.vimeo.com/video/ is Vimeo's own stable embed path.
     *  - dm (Dailymotion): the server's dm handler builds its Media with no
     *    meta at all. dailymotion.com/embed/video/ is stable and documented.
     *  - nv (Niconico): mediaquery's nicovideo.js sets only meta.thumbnail.
     *    embed.nicovideo.jp is Niconico's own dedicated embed subdomain.
     *  - sb (Streamable): mediaquery's streamable.js sets only
     *    meta.thumbnail. streamable.com/e/ is Streamable's documented
     *    embed path.
     *  - pt (PeerTube): mediaquery's peertube.js DOES set meta.embed, but as
     *    {tag, domain, uuid, short, onlyLong} — there is no "src" key, so
     *    MediaFrame.embedPlayableSrc's meta.embed.src check never catches
     *    it. The item id is itself "domain;shortUUID" (see peertube.js),
     *    which is exactly what building the URL here needs.
     *    /videos/embed/ is PeerTube's own standard route on every instance.
     *
     * Deliberately NOT attempted for Twitch (tw/tv/tc): Twitch's player
     * embed requires a "parent" query param matching the actual embedding
     * page's own domain, which a WebView navigated straight to
     * player.twitch.tv has no good answer for — getting it wrong fails
     * outright rather than degrading gracefully, so those stay on the
     * whole-page WEB offer, where the real CyTube page's own origin is what
     * makes that embed work correctly in the first place. Also not
     * attempted for li (Livestream.com, a largely defunct service with no
     * confirmed embed pattern) or sc (SoundCloud — CyTube's own server has
     * refused to add new sc items at all since 2022, so there is nothing to
     * fall back for).
     */
    /** A bare DNS hostname — letters/digits/hyphens per label, labels joined
     *  by dots, no scheme/userinfo/port/path/query. This is deliberately
     *  strict: peertube.js's "domain" half of a pt id is meant to be exactly
     *  this, and knownEmbedUrl below splices it directly into a URL string
     *  that gets loaded in a WebView, so anything that isn't unambiguously a
     *  hostname (an "@" that would smuggle in userinfo, a "/" that would
     *  smuggle in a path, a scheme, etc.) must be rejected outright rather
     *  than passed through. */
    private val HOSTNAME_REGEX =
        Regex("^(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")

    /** PeerTube's own short-UUID charset (base58-ish, no separators) — see
     *  peertube.js. Deliberately narrow for the same reason as HOSTNAME_REGEX. */
    private val PEERTUBE_SHORT_ID_REGEX = Regex("^[A-Za-z0-9]{1,64}$")

    /**
     * All server-supplied — a channel's own media id, straight off a
     * changeMedia/playlist frame — so every branch below is untrusted input
     * being spliced into a URL a WebView will load, not a trusted constant.
     * Only the "pt" branch actually needs validating: every other id here is
     * used as an opaque path segment on a fixed, hardcoded host that the id
     * itself has no control over, so there is nothing for a doctored id to
     * redirect — at worst it 404s on the real provider. "pt" is different:
     * peertube.js packs the instance's own hostname INTO the id
     * ("domain;shortUUID"), and that hostname becomes part of the URL's
     * authority itself — an unvalidated id there would let a malicious or
     * compromised channel point this WebView at an arbitrary attacker
     * domain merely by shaping the id string (e.g. embedding "@" to smuggle
     * in a different host, or "/" to smuggle in a path), rather than an
     * actual PeerTube instance.
     */
    fun knownEmbedUrl(type: String, id: String): String? = when (type) {
        "yt" -> "https://www.youtube.com/embed/$id"
        "vi" -> "https://player.vimeo.com/video/$id"
        "dm" -> "https://www.dailymotion.com/embed/video/$id"
        "nv" -> "https://embed.nicovideo.jp/watch/$id"
        "sb" -> "https://streamable.com/e/$id"
        "pt" -> {
            val parts = id.split(";", limit = 2)
            val domain = parts.getOrNull(0)
            val shortId = parts.getOrNull(1)
            if (parts.size == 2 && domain != null && shortId != null &&
                HOSTNAME_REGEX.matches(domain) && PEERTUBE_SHORT_ID_REGEX.matches(shortId)
            ) {
                "https://$domain/videos/embed/$shortId"
            } else null
        }
        else -> null
    }

    /**
     * True when [type] alone (plus whatever id a PlaylistItem carries) is
     * enough to resolve playback — no server-pushed meta (direct sources,
     * embed.src) required. A PlaylistItem (see Frames.kt) only ever gives us
     * uid/title/duration/type/mediaId — never meta — so this is exactly what
     * decides which playlist rows can be picked for personal/unsynced
     * browsing (see ChannelViewModel.pickPersonal / PlaylistPanel) versus
     * which ones only ever get real playable data when they're the
     * channel's actual current item (cm needs meta.direct; cu/bc/bn need
     * meta.embed.src and have no knownEmbedUrl fallback; tw/tv/tc/li/sc are
     * excluded above for the reasons documented on knownEmbedUrl).
     *
     * id is irrelevant to every branch here (PLAYABLE_ID/yt/gd resolve from
     * type alone; knownEmbedUrl's only id-shaped branch, pt, only inspects
     * id's structure, never rejects a well-formed one) so a placeholder is
     * fine to probe with.
     */
    fun canResolveIndependently(type: String): Boolean =
        type in PLAYABLE_ID || type == "yt" || type == "gd" || knownEmbedUrl(type, "x") != null
}
