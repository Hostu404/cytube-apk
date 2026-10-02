package com.cytube.mobile.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink

/**
 * Logs (tag CyTubeAudio) what happens to a video's sound, for working out
 * why one plays silent: the audio tracks in it with whether this device
 * says it can play each, which one was picked, which decoder took it (the
 * device's own, or FFmpeg's), how it's sent to the speaker (decoded to
 * PCM, or passed through still encoded to a TV or soundbar), and any audio
 * error. Only logs when something changes, not continuously.
 */
@OptIn(UnstableApi::class)
internal class AudioDiagnostics : AnalyticsListener {

    override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audio.isEmpty()) {
            if (tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }) {
                Log.i(TAG, "no audio track found in this video")
            }
            return
        }
        for ((g, group) in audio.withIndex()) {
            for (i in 0 until group.length) {
                Log.i(
                    TAG,
                    "track ${g + 1}.${i + 1}: ${describe(group.getTrackFormat(i))} " +
                        "support=${Util.getFormatSupportString(group.getTrackSupport(i))} " +
                        "selected=${group.isTrackSelected(i)}"
                )
            }
        }
        if (audio.none { it.isSelected }) Log.w(TAG, "no audio track selected")
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
        Log.i(TAG, "playing ${describe(format)}")
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
        val kind = if (decoderName.contains("ffmpeg", ignoreCase = true)) "FFmpeg" else "device"
        Log.i(TAG, "decoder: $decoderName ($kind)")
    }

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig
    ) {
        val encoding = audioTrackConfig.encoding
        val route = if (Util.isEncodingLinearPcm(encoding)) "decoded" else "PASSTHROUGH (still encoded, to TV/soundbar)"
        Log.i(
            TAG,
            "output: ${encodingName(encoding)} $route ${audioTrackConfig.sampleRate}Hz " +
                "offload=${audioTrackConfig.offload} tunneling=${audioTrackConfig.tunneling}"
        )
    }

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        Log.w(TAG, "audio output error: ${audioSinkError.message}", audioSinkError)
    }

    override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
        Log.w(TAG, "audio decoder error: ${audioCodecError.message}", audioCodecError)
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long
    ) {
        Log.w(TAG, "audio underrun (ran out of sound to play) after ${elapsedSinceLastFeedMs}ms")
    }

    private fun describe(f: Format): String = buildString {
        append(f.sampleMimeType ?: "unknown")
        f.codecs?.let { append(" codecs=").append(it) }
        if (f.channelCount != Format.NO_VALUE) append(" ${f.channelCount}ch")
        if (f.sampleRate != Format.NO_VALUE) append(" ${f.sampleRate}Hz")
        f.language?.let { append(" lang=").append(it) }
        f.label?.let { append(" \"").append(it).append('"') }
    }

    private fun encodingName(encoding: Int): String = when (encoding) {
        C.ENCODING_PCM_16BIT -> "PCM 16-bit"
        C.ENCODING_PCM_FLOAT -> "PCM float"
        C.ENCODING_AC3 -> "AC-3"
        C.ENCODING_E_AC3 -> "E-AC-3"
        C.ENCODING_E_AC3_JOC -> "E-AC-3 (Atmos)"
        C.ENCODING_AC4 -> "AC-4"
        C.ENCODING_DTS -> "DTS"
        C.ENCODING_DTS_HD -> "DTS-HD"
        C.ENCODING_DOLBY_TRUEHD -> "TrueHD"
        else -> "encoding $encoding"
    }

    private companion object {
        const val TAG = "CyTubeAudio"
    }
}
