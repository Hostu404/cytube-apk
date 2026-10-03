package com.cytube.mobile.player

/**
 * Sorts a playback failure, as PlayerSurface reports it ("ERROR_CODE_…",
 * with " (HTTP n)" after it for a bad HTTP status), into the file itself
 * being unplayable or the connection failing on the way to it.
 *
 * The difference decides what happens next: a file that's missing, refused
 * or in a format this device can't play will fail the same way every time,
 * so another quality of it is worth trying; a dropped or slow connection
 * says nothing about the file, so the same one is tried again instead —
 * moving down a quality there would leave a film in low quality for the
 * rest of its length over a few seconds of bad Wi-Fi.
 */
object PlaybackFailures {

    private val brokenSourceCodes = setOf(
        "ERROR_CODE_IO_FILE_NOT_FOUND",
        "ERROR_CODE_IO_NO_PERMISSION",
        "ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED",
        "ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE",
        "ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE",
        "ERROR_CODE_PARSING_CONTAINER_MALFORMED",
        "ERROR_CODE_PARSING_MANIFEST_MALFORMED",
        "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED",
        "ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED",
        "ERROR_CODE_DECODER_INIT_FAILED",
        "ERROR_CODE_DECODER_QUERY_FAILED",
        "ERROR_CODE_DECODING_FAILED",
        "ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES",
        "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED"
    )

    private val httpStatus = Regex("""\(HTTP (\d{3})\)""")

    /** True when [reason] says the file itself can't be played, so another
     *  quality of it is worth trying rather than the same one again. */
    fun isBrokenSource(reason: String): Boolean {
        val code = reason.substringBefore(' ')
        if (code in brokenSourceCodes) return true
        if (code != "ERROR_CODE_IO_BAD_HTTP_STATUS") return false
        val status = httpStatus.find(reason)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        // 4xx is the file (gone, refused, wrong address); 408 and 429 are
        // the server asking to come back later, and 5xx is the server
        // having trouble, both of which can clear up.
        return status in 400..499 && status != 408 && status != 429
    }

    /** True when [reason] is the connection or the server failing for now,
     *  worth trying the same file again after a moment. */
    fun isTransient(reason: String): Boolean {
        if (isBrokenSource(reason)) return false
        val code = reason.substringBefore(' ')
        return code == "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED" ||
            code == "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT" ||
            code == "ERROR_CODE_IO_BAD_HTTP_STATUS" ||
            code == "ERROR_CODE_IO_UNSPECIFIED" ||
            code == "ERROR_CODE_TIMEOUT" ||
            code == "ERROR_CODE_BEHIND_LIVE_WINDOW"
    }
}
