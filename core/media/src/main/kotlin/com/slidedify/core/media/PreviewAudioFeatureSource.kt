package com.slidedify.core.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.slidedify.analysis.audio.AudioAnalyzer
import com.slidedify.core.domain.player.PreviewSource
import com.slidedify.core.domain.suggest.FeatureSource
import com.slidedify.core.domain.suggest.TrackFeatures
import com.slidedify.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.ByteOrder


/**
 * Listens to the 30-second preview on the phone (decodes it, nothing is uploaded) and measures
 * energy, brightness and tempo. Expensive: it downloads the clip, so it runs for few songs.
 */
class PreviewAudioFeatureSource(
    private val previews: PreviewSource,
    /** Whether downloading a preview is OK right now (setting + network); if not, it is retried later. */
    private val isAllowed: suspend () -> Boolean = { true },
) : FeatureSource {
    override val id = "audio"
    override val isExpensive = true

    override suspend fun features(track: Track): TrackFeatures? {
        if (!isAllowed()) return null
        val url = previews.previewUrl(track) ?: return TrackFeatures()
        val clip = withTimeoutOrNull(DECODE_TIMEOUT_MS) { withContext(Dispatchers.IO) { decode(url) } } ?: return null
        val audio = AudioAnalyzer.analyze(clip.samples, clip.sampleRate) ?: return TrackFeatures()
        return TrackFeatures(audioEnergy = audio.energy, brightness = audio.brightness, bpm = audio.tempo)
    }

    private class Clip(val samples: FloatArray, val sampleRate: Int)

    /** Mono samples in -1..1 of the first [MAX_SECONDS]; null if the clip can't be read. */
    private suspend fun decode(url: String): Clip? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(url, emptyMap<String, String>())
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var isFloat = false

            val decoder = MediaCodec.createDecoderByType(mime).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start()

            var out = FloatArray(sampleRate * 8)
            var count = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone && count < sampleRate * MAX_SECONDS) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index) ?: return null
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = decoder.outputFormat
                        sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        isFloat = output.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    index >= 0 -> {
                        val buffer = decoder.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val ordered = buffer.order(ByteOrder.nativeOrder())
                            val values: FloatArray = if (isFloat) {
                                val fb = ordered.asFloatBuffer()
                                FloatArray(fb.remaining()).also { fb.get(it) }
                            } else {
                                val sb = ordered.asShortBuffer()
                                FloatArray(sb.remaining()) { sb.get() / 32768f }
                            }
                            val frames = values.size / channels
                            if (count + frames > out.size) out = out.copyOf(maxOf(out.size * 2, count + frames))
                            for (f in 0 until frames) {
                                var sum = 0f
                                for (c in 0 until channels) sum += values[f * channels + c]
                                out[count++] = sum / channels
                            }
                        }
                        decoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            return Clip(out.copyOf(count), sampleRate)
        } catch (e: IOException) {
            return null
        } catch (e: IllegalStateException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private companion object {
        const val MAX_SECONDS = 30
        const val TIMEOUT_US = 10_000L
        const val DECODE_TIMEOUT_MS = 25_000L
    }
}
