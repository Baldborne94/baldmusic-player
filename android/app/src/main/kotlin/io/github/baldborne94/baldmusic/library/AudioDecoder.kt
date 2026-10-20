package io.github.baldborne94.baldmusic.library

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import java.nio.ByteOrder

/** Chi riceve il PCM decodificato: campioni interleaved, [frames] per canale. */
interface PcmSink {
    /** false = basta così (es. abbastanza secondi per l'impronta). */
    fun shorts(data: ShortArray, frames: Int): Boolean

    fun floats(data: FloatArray, frames: Int): Boolean
}

/**
 * Decodifica un brano del telefono con i codec del telefono (gli stessi che lo suonano):
 * MediaExtractor + MediaCodec, PCM 16 bit o float. Lo usano la misura del volume
 * ([LoudnessScanner]) e l'impronta per il riconoscimento ([RecognitionScanner]).
 */
object AudioDecoder {

    enum class Outcome {
        /** Fino alla fine, o finché il ricevente ha detto basta. */
        DONE,
        /** [decode] fermato da fuori. */
        STOPPED,
        /** Niente audio, o PCM in un formato che non si sa leggere (24/32 bit interi). */
        UNSUPPORTED,
    }

    private const val TIMEOUT_US = 10_000L

    /**
     * Decodifica [uri]; [newSink] riceve frequenza e canali appena si conoscono e dà chi
     * riceverà i campioni. Un formato che cambia a metà brano (rarissimo) si ignora: il
     * ricevente ha quello che è arrivato prima.
     */
    fun decode(context: Context, uri: Uri, stop: () -> Boolean, newSink: (sampleRate: Int, channels: Int) -> PcmSink): Outcome {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return Outcome.UNSUPPORTED
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return pump(extractor, codec, stop, newSink)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun pump(extractor: MediaExtractor, codec: MediaCodec, stop: () -> Boolean, newSink: (Int, Int) -> PcmSink): Outcome {
        val info = MediaCodec.BufferInfo()
        var sink: PcmSink? = null
        var rate = 0
        var channels = 0
        var shorts = ShortArray(0)
        var floats = FloatArray(0)
        var inputDone = false
        while (true) {
            if (stop()) return Outcome.STOPPED
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outIndex < 0) continue
            val out = codec.getOutputBuffer(outIndex)
            if (out != null && info.size > 0) {
                val f = codec.getOutputFormat(outIndex)
                val r = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val c = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val encoding = if (Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                val s = sink ?: newSink(r, c).also { sink = it; rate = r; channels = c }
                if (rate == r && channels == c) {
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    val more = when (encoding) {
                        AudioFormat.ENCODING_PCM_16BIT -> {
                            val sb = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val n = sb.remaining()
                            if (shorts.size < n) shorts = ShortArray(n)
                            sb.get(shorts, 0, n)
                            s.shorts(shorts, n / c)
                        }
                        AudioFormat.ENCODING_PCM_FLOAT -> {
                            val fb = out.order(ByteOrder.nativeOrder()).asFloatBuffer()
                            val n = fb.remaining()
                            if (floats.size < n) floats = FloatArray(n)
                            fb.get(floats, 0, n)
                            s.floats(floats, n / c)
                        }
                        else -> {
                            codec.releaseOutputBuffer(outIndex, false)
                            return Outcome.UNSUPPORTED
                        }
                    }
                    if (!more) {
                        codec.releaseOutputBuffer(outIndex, false)
                        return Outcome.DONE
                    }
                }
            }
            codec.releaseOutputBuffer(outIndex, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return if (sink == null) Outcome.UNSUPPORTED else Outcome.DONE
        }
    }
}
