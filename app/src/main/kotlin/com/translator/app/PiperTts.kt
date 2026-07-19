package com.translator.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
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
            Voice("it", "Paola", "vits-piper-it_IT-paola-medium", "it_IT-paola-medium.onnx", 67),
            Voice("en", "Amy", "vits-piper-en_US-amy-medium", "en_US-amy-medium.onnx", 67),
            Voice("de", "Thorsten", "vits-piper-de_DE-thorsten-medium", "de_DE-thorsten-medium.onnx", 67),
        )
    }

    private val engines = mutableMapOf<String, OfflineTts>()
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
    fun downloadVoice(voice: Voice, onProgress: (Int) -> Unit) {
        val root = voicesRoot().apply { mkdirs() }
        val archive = File(root, "${voice.dirName}.tar.bz2")

        val conn = URL("$BASE_URL/${voice.dirName}.tar.bz2").openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
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
        Log.i(TAG, "Voice ready: ${voice.dirName}")
        onProgress(100)
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

    /**
     * Synthesizes and plays [text]; blocking until playback finishes.
     * Returns false when the voice is missing or synthesis fails.
     */
    fun speak(text: String, langCode: String): Boolean {
        val voice = voiceFor(langCode) ?: return false
        if (!isVoiceReady(langCode)) return false
        return try {
            stopped = false
            val tts = engineFor(voice)
            val audio = tts.generate(text = text, sid = 0, speed = 1.0f)
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
    }
}
