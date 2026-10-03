package com.cytube.mobile.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Failures as PlayerSurface reports them, sorted into a broken file (try
 *  another quality) or a failing connection (try the same file again). */
class PlaybackFailuresTest {

    @Test fun missingOrRefusedFilesAreBroken() {
        assertTrue(PlaybackFailures.isBrokenSource("ERROR_CODE_IO_BAD_HTTP_STATUS (HTTP 404)"))
        assertTrue(PlaybackFailures.isBrokenSource("ERROR_CODE_IO_BAD_HTTP_STATUS (HTTP 403)"))
        assertTrue(PlaybackFailures.isBrokenSource("ERROR_CODE_IO_BAD_HTTP_STATUS (HTTP 410)"))
        assertTrue(PlaybackFailures.isBrokenSource("ERROR_CODE_IO_FILE_NOT_FOUND"))
    }

    @Test fun unplayableFormatsAreBroken() {
        assertTrue(PlaybackFailures.isBrokenSource("ERROR_CODE_DECODING_FORMAT_UNSUPPORTED"))
        assertTrue(PlaybackFailures.isBrokenSource("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED"))
        assertTrue(PlaybackFailures.isBrokenSource("ERROR_CODE_PARSING_CONTAINER_MALFORMED"))
    }

    @Test fun connectionTroubleIsTransient() {
        for (reason in listOf(
            "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
            "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT",
            "ERROR_CODE_IO_BAD_HTTP_STATUS (HTTP 503)",
            "ERROR_CODE_IO_BAD_HTTP_STATUS (HTTP 429)",
            "ERROR_CODE_IO_BAD_HTTP_STATUS (HTTP 408)",
            "ERROR_CODE_IO_UNSPECIFIED"
        )) {
            assertFalse(reason, PlaybackFailures.isBrokenSource(reason))
            assertTrue(reason, PlaybackFailures.isTransient(reason))
        }
    }

    @Test fun brokenIsNeverTransient() {
        assertFalse(PlaybackFailures.isTransient("ERROR_CODE_IO_BAD_HTTP_STATUS (HTTP 404)"))
        assertFalse(PlaybackFailures.isTransient("ERROR_CODE_DECODER_INIT_FAILED"))
    }

    @Test fun anythingElseIsNeither() {
        assertFalse(PlaybackFailures.isBrokenSource("ERROR_CODE_UNSPECIFIED"))
        assertFalse(PlaybackFailures.isTransient("ERROR_CODE_AUDIO_TRACK_INIT_FAILED"))
        assertFalse(PlaybackFailures.isBrokenSource(""))
    }
}
