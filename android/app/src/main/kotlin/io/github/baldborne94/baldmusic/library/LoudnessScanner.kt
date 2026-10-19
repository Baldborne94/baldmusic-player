package io.github.baldborne94.baldmusic.library

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import io.github.baldborne94.baldmusic.core.library.FileStamp
import io.github.baldborne94.baldmusic.core.library.LoudnessIndex
import io.github.baldborne94.baldmusic.core.library.LoudnessMeter
import io.github.baldborne94.baldmusic.core.library.Measure
import java.io.File
import java.nio.ByteOrder

/**
 * Misura il volume dei file senza ReplayGain nei tag: decodifica il brano con i codec
 * del telefono (gli stessi che lo suonano) e passa il PCM a [LoudnessMeter]. Una volta
 * per file, poi solo se cambia. È lavoro pesante: lo fa [LoudnessWorker], quando il
 * telefono è in carica (o subito, se lo chiedi dalle Impostazioni).
 */
class LoudnessScanner(private val context: Context) {

    private val file = File(context.filesDir, "volume.json")

    val index: LoudnessIndex by lazy { LoudnessIndex.fromJson(runCatching { file.readText() }.getOrNull()) }

    /**
     * Misura i file di [files] che mancano, uno alla volta, finché [stop] non dice di
     * fermarsi. Restituisce quanti ne ha misurati.
     */
    fun measure(files: List<FileStamp>, uriOf: (Long) -> Uri, stop: () -> Boolean): Int {
        val pending = index.pending(files)
        var done = 0
        for (stamp in pending) {
            if (stop()) break
            index.put(stamp, runCatching { decode(uriOf(stamp.id), stop) }.onFailure { Log.w(TAG, "Volume non misurato: ${stamp.id}", it) }.getOrNull())
            done++
            // Si salva ogni tanto: se il lavoro si ferma a metà non si riparte da zero.
            if (done % 10 == 0) save()
        }
        if (done > 0) save()
        return done
    }

    /** Toglie dall'indice i file che non ci sono più. */
    fun retain(ids: Set<Long>) {
        if (index.retain(ids)) save()
    }

    private fun decode(uri: Uri, stop: () -> Boolean): Measure? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return pump(extractor, codec, stop)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun pump(extractor: MediaExtractor, codec: MediaCodec, stop: () -> Boolean): Measure? {
        val info = MediaCodec.BufferInfo()
        var meter: LoudnessMeter? = null
        var shorts = ShortArray(0)
        var floats = FloatArray(0)
        var inputDone = false
        while (true) {
            if (stop()) return null
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
                val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val encoding = if (Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                val m = meter ?: LoudnessMeter(rate, channels).also { meter = it }
                // Un formato che cambia a metà brano è rarissimo: si misura il resto come prima.
                if (m.sampleRate == rate && m.channels == channels) {
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    when (encoding) {
                        AudioFormat.ENCODING_PCM_16BIT -> {
                            val sb = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val n = sb.remaining()
                            if (shorts.size < n) shorts = ShortArray(n)
                            sb.get(shorts, 0, n)
                            m.addShorts(shorts, n / channels)
                        }
                        AudioFormat.ENCODING_PCM_FLOAT -> {
                            val fb = out.order(ByteOrder.nativeOrder()).asFloatBuffer()
                            val n = fb.remaining()
                            if (floats.size < n) floats = FloatArray(n)
                            fb.get(floats, 0, n)
                            m.addFloats(floats, n / channels)
                        }
                        // Altri formati (24/32 bit interi): non si misura, il brano suona com'è.
                        else -> {
                            codec.releaseOutputBuffer(outIndex, false)
                            return null
                        }
                    }
                }
            }
            codec.releaseOutputBuffer(outIndex, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return meter?.result()
        }
    }

    private fun save() {
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(index.toJson())
            tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "Indice del volume non salvato", it) }
    }

    private companion object {
        const val TAG = "LoudnessScanner"
        const val TIMEOUT_US = 10_000L
    }
}
