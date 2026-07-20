package com.translator.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app neural TTS: Piper voices (it/en/de) running on sherpa-onnx.
 *
 * Voices are downloaded once from the sherpa-onnx release assets and cached
 * in filesDir; afterwards synthesis is fully offline.
 */
class PiperTts(private val context: Context) {

    data class Voice(
        val langCode: String,
        val displayName: String,
        val dirName: String,
        val modelFile: String,
        val sizeMb: Int,
    )

    companion object {
        private const val TAG = "PiperTts"
        private const val BASE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

        val VOICES = listOf(
            Voice("it", "Miro", "vits-piper-it_IT-miro-high", "it_IT-miro-high.onnx", 64),
            Voice("en", "Amy", "vits-piper-en_US-amy-medium", "en_US-amy-medium.onnx", 67),
            Voice("de", "Thorsten", "vits-piper-de_DE-thorsten-medium", "de_DE-thorsten-medium.onnx", 67),
        )

        // Supertonic 3: modern flow-matching multilingual model (it/en/de and 28
        // more), int8, notably more natural than Piper. Language is passed at
        // synthesis time via GenerationConfig.extra["lang"].
        const val SUPERTONIC_DIR = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
        const val SUPERTONIC_SIZE_MB = 123
    }

    private val engines = mutableMapOf<String, OfflineTts>()
    private var supertonicEngine: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var stopped = false

    private fun voicesRoot(): File = File(context.filesDir, "piper-voices")

    fun voiceFor(langCode: String): Voice? =
        VOICES.firstOrNull { it.langCode == langCode }

    fun isVoiceReady(langCode: String): Boolean {
        val voice = voiceFor(langCode) ?: return false
        val dir = File(voicesRoot(), voice.dirName)
        return File(dir, voice.modelFile).exists() &&
            File(dir, "tokens.txt").exists() &&
            File(dir, "espeak-ng-data").isDirectory
    }

    /** Blocking download + extraction; call from a background thread. */
    fun downloadVoice(voice: Voice, onProgress: (Int) -> Unit) =
        fetchAndExtract(voice.dirName, onProgress)

    /** Blocking download + extraction of the Supertonic package. */
    fun downloadSupertonic(onProgress: (Int) -> Unit) =
        fetchAndExtract(SUPERTONIC_DIR, onProgress)

    private fun fetchAndExtract(dirName: String, onProgress: (Int) -> Unit) {
        val root = voicesRoot().apply { mkdirs() }
        val archive = File(root, "$dirName.tar.bz2")

        val conn = URL("$BASE_URL/$dirName.tar.bz2").openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 180_000
        conn.instanceFollowRedirects = true
        try {
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(archive).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done * 85 / total).toInt())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        onProgress(90)
        TarArchiveInputStream(
            BZip2CompressorInputStream(BufferedInputStream(archive.inputStream())),
        ).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val out = File(root, entry.name)
                if (!out.canonicalPath.startsWith(root.canonicalPath)) continue
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { tar.copyTo(it) }
                }
            }
        }
        archive.delete()
        Log.i(TAG, "Package ready: $dirName")
        onProgress(100)
    }

    fun isSupertonicReady(): Boolean {
        val dir = File(voicesRoot(), SUPERTONIC_DIR)
        return File(dir, "tts.json").exists() &&
            File(dir, "vocoder.int8.onnx").exists() &&
            File(dir, "text_encoder.int8.onnx").exists()
    }

    /** Loads (or returns the cached) engine; blocking, call off the main thread. */
    private fun engineFor(voice: Voice): OfflineTts {
        engines[voice.langCode]?.let { return it }
        val dir = File(voicesRoot(), voice.dirName)
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = File(dir, voice.modelFile).absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    dataDir = File(dir, "espeak-ng-data").absolutePath,
                ),
                numThreads = 2,
            ),
        )
        val tts = OfflineTts(config = config)
        engines[voice.langCode] = tts
        return tts
    }

    private fun supertonic(): OfflineTts {
        supertonicEngine?.let { return it }
        val dir = File(voicesRoot(), SUPERTONIC_DIR)
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = File(dir, "duration_predictor.int8.onnx").absolutePath,
                    textEncoder = File(dir, "text_encoder.int8.onnx").absolutePath,
                    vectorEstimator = File(dir, "vector_estimator.int8.onnx").absolutePath,
                    vocoder = File(dir, "vocoder.int8.onnx").absolutePath,
                    ttsJson = File(dir, "tts.json").absolutePath,
                    unicodeIndexer = File(dir, "unicode_indexer.bin").absolutePath,
                    voiceStyle = File(dir, "voice.bin").absolutePath,
                ),
                numThreads = 2,
            ),
        )
        val tts = OfflineTts(config = config)
        supertonicEngine = tts
        return tts
    }

    /**
     * Supertonic synthesis. The language MUST be passed via extra["lang"],
     * otherwise the model defaults to English. Blocking; call off the main thread.
     */
    fun speakSupertonic(text: String, langCode: String, speed: Float = 1.0f): Boolean {
        if (!isSupertonicReady()) return false
        return try {
            stopped = false
            val tts = supertonic()
            val audio = tts.generateWithConfig(
                text,
                GenerationConfig(
                    speed = speed,
                    sid = 0,
                    numSteps = 5,
                    extra = mapOf("lang" to langCode),
                ),
            )
            if (audio.samples.isEmpty() || stopped) return false
            play(audio.samples, audio.sampleRate)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Supertonic synthesis failed", e)
            false
        }
    }

    /**
     * Synthesizes and plays [text]; blocking until playback finishes.
     * Returns false when the voice is missing or synthesis fails.
     */
    fun speak(text: String, langCode: String, speed: Float = 1.0f): Boolean {
        val voice = voiceFor(langCode) ?: return false
        if (!isVoiceReady(langCode)) return false
        return try {
            stopped = false
            val tts = engineFor(voice)
            val audio = tts.generate(text = text, sid = 0, speed = speed)
            if (audio.samples.isEmpty() || stopped) return false
            play(audio.samples, audio.sampleRate)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Piper synthesis failed", e)
            false
        }
    }

    private fun play(samples: FloatArray, sampleRate: Int) {
        stopInternal()
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate * Float.SIZE_BYTES))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        track.play()
        var offset = 0
        while (offset < samples.size && !stopped) {
            val written = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written < 0) break
            offset += written
        }
        if (!stopped) {
            val frames = samples.size
            val deadline = System.currentTimeMillis() + frames * 1000L / sampleRate + 2000L
            while (!stopped && track.playbackHeadPosition < frames &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(20)
            }
        }
        runCatching { track.stop() }
        track.release()
        if (audioTrack === track) audioTrack = null
    }

    fun stop() {
        stopped = true
        stopInternal()
    }

    private fun stopInternal() {
        audioTrack?.let {
            audioTrack = null
            runCatching { it.stop() }
            runCatching { it.release() }
        }
    }

    fun release() {
        stop()
        engines.values.forEach { runCatching { it.release() } }
        engines.clear()
        supertonicEngine?.let { runCatching { it.release() } }
        supertonicEngine = null
    }
}
