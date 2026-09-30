package org.bestdroid.tts

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.os.Bundle
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * BestDroid: the BeSTspeech (Keynote Gold) engine as an Android TTS engine,
 * with a Google-TTS fallback so the service never goes quiet.
 *
 * Twenty voices (one per engine build), text preprocessing ported from
 * iBestSpeech, language-detect voice switching, and per-utterance streaming
 * at each build's native rate (11025 / 10000 / 10800 Hz).
 */
class BestDroidService : TextToSpeechService() {

    companion object {
        const val PREFS = "bestdroid"
        const val KEY_DEFAULT_VOICE = "default_voice"
        const val KEY_FALLBACK_ENABLED = "fallback_enabled"
        const val KEY_FALLBACK_PACKAGE = "fallback_package"
        const val KEY_TIMEOUT_BASE_MS = "timeout_base_ms"
        const val KEY_TIMEOUT_PER_CHAR_MS = "timeout_per_char_ms"

        const val DEFAULT_VOICE = "2006ENG"
        const val DEFAULT_FALLBACK_PACKAGE = "com.google.android.tts"
        const val DEFAULT_TIMEOUT_BASE_MS = 15_000
        const val DEFAULT_TIMEOUT_PER_CHAR_MS = 15

        /** Settings voice wins over every request (Panthera's override_voice). */
        const val KEY_OVERRIDE_VOICE = "override_voice"
        /** Cap silent runs at ~200 ms (Panthera's "fewest pauses"). */
        const val KEY_SHORTEN_PAUSES = "shorten_pauses"

        /** One JNI chunk: keeps native buffers small and stop responsive. */
        const val CHUNK_CHARS = 500

        /** Peak every utterance is normalized to (bounded gain). */
        const val TARGET_PEAK = 26000f

        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val stopped = AtomicBoolean(false)
    private val nativeExecutor = Executors.newCachedThreadPool()
    @Volatile private var defaultBuild: String = DEFAULT_VOICE

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        CldrText.init(this)
        defaultBuild = prefs(this).getString(KEY_DEFAULT_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
        // Warm the native library so the first utterance pays no load cost.
        try {
            NativeBst.builds()
        } catch (e: Throwable) {
            android.util.Log.e("BestDroid", "native load failed", e)
        }
    }

    override fun onDestroy() {
        nativeExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onStop() {
        stopped.set(true)
    }

    // ------------------------------------------------------------------
    // Voices / languages
    // ------------------------------------------------------------------

    private fun androidVoices(): List<Voice> = VoiceCatalog.all.map { info ->
        Voice(
            info.build,
            Locale.forLanguageTag(info.language),
            Voice.QUALITY_HIGH,
            Voice.LATENCY_NORMAL,
            false,
            null
        )
    }

    override fun onGetVoices(): MutableList<Voice> = androidVoices().toMutableList()

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (voiceName != null && VoiceCatalog.infoFor(voiceName) != null) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }

    override fun onLoadVoice(voiceName: String?): Int {
        if (voiceName == null || VoiceCatalog.infoFor(voiceName) == null) {
            return TextToSpeech.ERROR
        }
        defaultBuild = voiceName
        prefs(this).edit().putString(KEY_DEFAULT_VOICE, defaultBuild).apply()
        return TextToSpeech.SUCCESS
    }

    override fun onGetLanguage(): Array<String> {
        val locale = Locale.forLanguageTag(VoiceCatalog.languageFor(defaultBuild))
        return arrayOf(locale.language, locale.country, locale.variant)
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        if (lang.isNullOrEmpty()) return TextToSpeech.LANG_NOT_SUPPORTED
        val candidates = VoiceCatalog.all.filter {
            Locale.forLanguageTag(it.language).language == lang
        }
        if (candidates.isEmpty()) return TextToSpeech.LANG_NOT_SUPPORTED
        if (country.isNullOrEmpty()) return TextToSpeech.LANG_AVAILABLE
        val exact = candidates.any {
            Locale.forLanguageTag(it.language).country.equals(country, ignoreCase = true)
        }
        return if (exact) TextToSpeech.LANG_AVAILABLE
        else TextToSpeech.LANG_COUNTRY_AVAILABLE
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        if (lang.isNullOrEmpty()) return TextToSpeech.LANG_NOT_SUPPORTED
        val candidates = VoiceCatalog.all.filter {
            Locale.forLanguageTag(it.language).language == lang
        }
        if (candidates.isEmpty()) return TextToSpeech.LANG_NOT_SUPPORTED
        // Keep the user's chosen voice when it already speaks this language;
        // otherwise fall through to the closest match and remember it.
        val saved = prefs(this).getString(KEY_DEFAULT_VOICE, DEFAULT_VOICE)
        val savedInfo = saved?.let { VoiceCatalog.infoFor(it) }
        val savedMatches = savedInfo != null &&
            Locale.forLanguageTag(savedInfo.language).language == lang
        if (savedMatches) {
            defaultBuild = savedInfo!!.build
        } else {
            val exact = if (country.isNullOrEmpty()) null
            else candidates.firstOrNull {
                Locale.forLanguageTag(it.language).country.equals(country, ignoreCase = true)
            }
            defaultBuild = exact?.build ?: candidates.first().build
            prefs(this).edit().putString(KEY_DEFAULT_VOICE, defaultBuild).apply()
        }
        val exactNow = candidates.any { it.build == defaultBuild } &&
            (!country.isNullOrEmpty())
        return if (country.isNullOrEmpty() || exactNow) {
            TextToSpeech.LANG_AVAILABLE
        } else {
            TextToSpeech.LANG_COUNTRY_AVAILABLE
        }
    }

    /**
     * The voice TalkBack and the system voice picker use when the client
     * only sets a language: the user's saved voice for that language,
     * else the first catalog voice for it.
     */
    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String {
        val saved = prefs(this).getString(KEY_DEFAULT_VOICE, DEFAULT_VOICE)
        val savedInfo = saved?.let { VoiceCatalog.infoFor(it) }
        if (savedInfo != null && (lang.isNullOrEmpty() ||
                Locale.forLanguageTag(savedInfo.language).language == lang)
        ) {
            return savedInfo.build
        }
        if (!lang.isNullOrEmpty()) {
            VoiceCatalog.all.firstOrNull {
                Locale.forLanguageTag(it.language).language == lang
            }?.let { return it.build }
        }
        return VoiceCatalog.ENGLISH_BUILD
    }

    // ------------------------------------------------------------------
    // Synthesis
    // ------------------------------------------------------------------

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        stopped.set(false)
        try {
            synthesize(request, callback)
        } catch (t: Throwable) {
            // Catch-all: the service must never die silently on an utterance.
            android.util.Log.e("BestDroid", "synthesis failed", t)
            try {
                callback.error()
            } catch (t2: Throwable) {
                android.util.Log.e("BestDroid", "callback.error failed", t2)
            }
        }
    }

    private fun synthesize(request: SynthesisRequest, callback: SynthesisCallback) {
        val text = request.charSequenceText?.toString() ?: ""
        if (text.isBlank()) {
            callback.done()
            return
        }

        // Which voice: explicit voice name wins, then request language, then default.
        // Re-read the saved voice so changes in the app UI apply to this
        // already-running service without a restart.
        prefs(this).getString(KEY_DEFAULT_VOICE, null)?.let { saved ->
            if (VoiceCatalog.infoFor(saved) != null) defaultBuild = saved
        }
        // Panthera's override_voice: the settings voice wins over every
        // request, so a TalkBack user gets the voice they picked. The saved
        // voice still has to speak the requested language; otherwise fall
        // through to the language match below.
        val voiceOverride = prefs(this).getBoolean(KEY_OVERRIDE_VOICE, true)
        val requestedVoice = if (voiceOverride) null else request.voiceName
        var build = defaultBuild
        if (requestedVoice != null && VoiceCatalog.infoFor(requestedVoice) != null) {
            build = requestedVoice
        } else {
            // Switch voices only when the current one cannot speak the
            // requested language; otherwise the saved/settings voice stands.
            val reqLang = request.language
            val buildLang = VoiceCatalog.infoFor(build)?.let {
                Locale.forLanguageTag(it.language).language
            }
            if (!reqLang.isNullOrEmpty() && buildLang != reqLang) {
                VoiceCatalog.all.firstOrNull {
                    Locale.forLanguageTag(it.language).language == reqLang
                }?.let { build = it.build }
            }
        }

        val rate = request.speechRate.takeIf { it > 0 } ?: 100
        val pitch = request.pitch.takeIf { it > 0 } ?: 100
        val engineRate = EngineParameters.engineRate(rate)
        val enginePitch = EngineParameters.enginePitch(pitch)

        val language = VoiceCatalog.languageFor(build)

        // 1) Preprocess (SSMLText port): entities, CLDR descriptions, fold,
        //    clock times, pronunciations, number separation, comma softening.
        val prepared = TextPipeline.prepare(text, language)
        if (prepared.isBlank()) {
            callback.done()
            return
        }

        // 2) Language-detect voice switch: a voice follows the text's language.
        LanguageDetector.buildToSpeak(prepared, current = language)?.let { switched ->
            android.util.Log.i("BestDroid", "switching $build -> $switched for text language")
            build = switched
        }
        val activeLanguage = VoiceCatalog.languageFor(build)
        val finalText = if (activeLanguage != language) {
            TextPipeline.prepare(text, activeLanguage)
        } else {
            prepared
        }
        if (finalText.isBlank()) {
            callback.done()
            return
        }

        // 3) Encode for the build; an unmappable script falls back to English
        //    rather than emitting silence.
        var info = VoiceCatalog.infoFor(build) ?: VoiceCatalog.infoFor(DEFAULT_VOICE)!!
        var encoded = VoiceCatalog.encodeForBuild(finalText, info)
        if (encoded == null) {
            android.util.Log.i("BestDroid", "$build cannot read script; English fallback")
            info = VoiceCatalog.infoFor(VoiceCatalog.ENGLISH_BUILD)!!
            val english = TextPipeline.prepare(text, "en-US")
            encoded = VoiceCatalog.encodeForBuild(english, info)
            if (encoded == null) {
                fallbackOrError(text, rate, pitch, callback, "unencodable text")
                return
            }
            build = info.build
        }

        val p = prefs(this)
        val fallbackEnabled = p.getBoolean(KEY_FALLBACK_ENABLED, true)
        val timeoutMs = p.getInt(KEY_TIMEOUT_BASE_MS, DEFAULT_TIMEOUT_BASE_MS) +
            finalText.length * p.getInt(KEY_TIMEOUT_PER_CHAR_MS, DEFAULT_TIMEOUT_PER_CHAR_MS)

        // 4) Native synth on an executor under a proportional timeout.
        // SSML-aware: split prosody/break sections first; plain text takes
        // the identical single-segment path it always has.
        val synthBuild = build
        val segments = try {
            TextPipeline.splitSsml(text)
        } catch (t: Throwable) {
            listOf(TextPipeline.SsmlSegment(text, 50, 50, 0))
        }
        val multiSegment = segments.size != 1 || segments[0].rate != 50 ||
            segments[0].pitch != 50 || segments[0].gapAfterMs != 0
        val synthBytes = checkNotNull(encoded)
        val synthPitch = enginePitch
        val synthRate = engineRate
        data class SegPlan(
            val chunks: List<ByteArray>,
            val pitch: Int,
            val rate: Int,
            val gapAfterMs: Int
        )
        val segPlans: List<SegPlan>
        val chunkList: List<ByteArray>
        if (!multiSegment) {
            segPlans = listOf(SegPlan(chunkBytes(synthBytes), synthPitch, synthRate, 0))
            chunkList = segPlans[0].chunks
        } else {
            segPlans = segments.mapNotNull { seg ->
                val segPrepared = try {
                    TextPipeline.prepare(seg.text, activeLanguage)
                } catch (t: Throwable) {
                    ""
                }
                if (segPrepared.isBlank()) {
                    if (seg.gapAfterMs > 0) SegPlan(emptyList(), synthPitch, synthRate, seg.gapAfterMs)
                    else null
                } else {
                    val segEncoded = try {
                        VoiceCatalog.encodeForBuild(segPrepared, info)
                    } catch (t: Throwable) {
                        null
                    } ?: return@mapNotNull null
                    val segRate = EngineParameters.engineRate(
                        (rate * seg.rate / 50.0).roundToInt()
                    )
                    val segPitch = EngineParameters.enginePitch(
                        (pitch * seg.pitch / 50.0).roundToInt()
                    )
                    SegPlan(chunkBytes(segEncoded), segPitch, segRate, seg.gapAfterMs)
                }
            }
            chunkList = segPlans.flatMap { it.chunks }
        }
        android.util.Log.i(
            "BestDroid",
            "synth start build=$synthBuild chars=${finalText.length} " +
                "segments=${segPlans.size} chunks=${chunkList.size} " +
                "timeout=${timeoutMs}ms rate=$rate"
        )
        val future = nativeExecutor.submit<SegSynth> {
            val out = mutableListOf<ShortArray>()
            val verbatim = mutableSetOf<Int>()
            val gaps = mutableMapOf<Int, Int>()
            for (plan in segPlans) {
                for (chunk in plan.chunks) {
                    if (stopped.get()) break
                    val pcm = try {
                        NativeBst.nativeSay(synthBuild, chunk, plan.pitch, plan.rate)
                    } catch (e: Throwable) {
                        android.util.Log.e("BestDroid", "nativeSay failed", e)
                        null
                    }
                    if (pcm != null && pcm.isNotEmpty()) out.add(pcm)
                }
                if (plan.gapAfterMs > 0 && !stopped.get()) {
                    // Gap silence is materialized below once the sample rate
                    // is known. Empty arrays are never emitted by nativeSay
                    // (isNotEmpty guard above), so they are safe markers;
                    // the duration map rides alongside.
                    out.add(ShortArray(0))
                    verbatim.add(out.lastIndex)
                    gaps[out.lastIndex] = plan.gapAfterMs
                }
            }
            SegSynth(out, verbatim, gaps)
        }

        val synth: SegSynth? = try {
            future.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            android.util.Log.w("BestDroid", "native synth timed out after ${timeoutMs}ms")
            future.cancel(true)
            null
        } catch (e: Exception) {
            android.util.Log.e("BestDroid", "native synth failed", e)
            null
        }

        val nativeRate = try {
            NativeBst.nativeRate(synthBuild)
        } catch (e: Throwable) {
            0
        }.takeIf { it > 0 } ?: 11025

        // Materialize <break> gaps as exact silence at the build's rate.
        val chunks: List<ShortArray>? = synth?.chunks?.mapIndexed { index, pcm ->
            val gapMs = synth.gaps[index]
            if (pcm.isEmpty() && gapMs != null && gapMs > 0) {
                ShortArray((gapMs * nativeRate / 1000).coerceIn(1, nativeRate * 10))
            } else {
                pcm
            }
        }
        val verbatim = synth?.verbatim ?: emptySet()

        val hasSpeech = chunks != null && chunks.any { pcm -> pcm.any { it != 0.toShort() } }
        if (hasSpeech && !stopped.get()) {
            val frames = streamPcm(callback, nativeRate, chunks!!, verbatim)
            android.util.Log.i(
                "BestDroid",
                "request done build=$synthBuild rate=$rate pitch=$pitch frames=$frames"
            )
            return
        }

        // 5) Nothing speakable came out: Google fallback, or an honest error.
        if (!fallbackEnabled || stopped.get()) {
            if (!stopped.get()) {
                try {
                    callback.error()
                } catch (t: Throwable) {
                    android.util.Log.e("BestDroid", "callback.error failed", t)
                }
            } else {
                try {
                    callback.done()
                } catch (t: Throwable) {
                    android.util.Log.e("BestDroid", "callback.done failed", t)
                }
            }
            return
        }
        fallbackOrError(text, rate, pitch, callback, "native produced no speech")
    }

    /** Native synth result: PCM per chunk, <break> gap markers, and chunk
     * indexes whose pauses must stream verbatim (the shortener skips them,
     * so an explicit break keeps its full duration). */
    private data class SegSynth(
        val chunks: List<ShortArray>,
        val verbatim: Set<Int>,
        val gaps: Map<Int, Int>
    )

    /** Splits encoded bytes into chunks at spaces (never mid-word). */
    private fun chunkBytes(bytes: ByteArray): List<ByteArray> {
        if (bytes.size <= CHUNK_CHARS) return listOf(bytes)
        val out = mutableListOf<ByteArray>()
        var start = 0
        while (start < bytes.size) {
            var end = minOf(start + CHUNK_CHARS, bytes.size)
            if (end < bytes.size) {
                // Break at a space inside the window — never mid-word, and
                // never past the window (lastIndexOf scans the whole array).
                var space = -1
                var s = end - 1
                while (s > start) {
                    if (bytes[s] == ' '.code.toByte()) {
                        space = s
                        break
                    }
                    s--
                }
                if (space > start) end = space
            }
            // Never hand the engine NUL bytes.
            val slice = bytes.copyOfRange(start, end).filter { it != 0.toByte() }.toByteArray()
            if (slice.isNotEmpty()) out.add(slice)
            start = if (end == start) end + 1 else end
            while (start < bytes.size && bytes[start] == ' '.code.toByte()) start++
        }
        return out
    }

    private fun streamPcm(
        callback: SynthesisCallback,
        sampleRate: Int,
        chunks: List<ShortArray>,
        verbatimIndices: Set<Int> = emptySet()
    ): Int {
        try {
            callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
        } catch (t: Throwable) {
            android.util.Log.e("BestDroid", "callback.start failed", t)
            return 0
        }
        // Panthera's "fewest pauses": cap silent runs, keep rhythm otherwise.
        val shorten = try {
            prefs(this).getBoolean(KEY_SHORTEN_PAUSES, true)
        } catch (t: Throwable) {
            true
        }
        val shortener = if (shorten) PauseShortener(sampleRate) else null
        var droppedTotal = 0
        var total = 0
        val frame = ByteArray(2048)
        // Pass 1: shorten + measure peak, so every build lands at the same
        // loudness (2006ENG whisper-quiet, 1998ENG clipping-hot otherwise).
        data class Ready(val pcm: ShortArray, val len: Int)
        val ready = ArrayList<Ready>(chunks.size)
        var peak = 0
        for ((index, pcm) in chunks.withIndex()) {
            if (stopped.get()) break
            val use: ShortArray
            val useLen: Int
            // Explicit <break> silence streams verbatim: the shortener would
            // otherwise eat the very pause the client asked for.
            if (shortener != null && index !in verbatimIndices) {
                val buf = ShortArray(pcm.size)
                val n = try {
                    shortener.process(pcm, pcm.size, buf)
                } catch (t: Throwable) {
                    pcm.size.also { buf.indices.forEach { i -> buf[i] = pcm[i] } }
                }
                droppedTotal += pcm.size - n
                use = buf
                useLen = n
            } else {
                use = pcm
                useLen = pcm.size
            }
            if (useLen == 0) continue
            for (i in 0 until useLen) {
                val a = kotlin.math.abs(use[i].toInt())
                if (a > peak) peak = a
            }
            ready.add(Ready(use, useLen))
        }
        // One gain for the whole utterance: boost quiet builds, tame hot
        // ones, never touch silence. Bounded so hiss never gets 40 dB.
        val gain = if (peak <= 0) 1.0f
        else (TARGET_PEAK / peak.toFloat()).coerceIn(0.25f, 4.0f)
        try {
            for ((use, useLen) in ready) {
                if (stopped.get()) break
                // short[] -> little-endian bytes, streamed in small frames so
                // stop stays responsive and no huge buffer is needed.
                val bytes = ByteArray(useLen * 2)
                var b = 0
                for (i in 0 until useLen) {
                    val v = (use[i] * gain).roundToInt().coerceIn(-32768, 32767)
                    bytes[b++] = (v and 0xFF).toByte()
                    bytes[b++] = ((v shr 8) and 0xFF).toByte()
                }
                var off = 0
                while (off < bytes.size) {
                    if (stopped.get()) break
                    val n = minOf(frame.size, bytes.size - off)
                    System.arraycopy(bytes, off, frame, 0, n)
                    callback.audioAvailable(frame, 0, n)
                    off += n
                }
                total += useLen
            }
            if (shorten && droppedTotal > 0) {
                android.util.Log.i(
                    "BestDroid",
                    "shortened pauses: dropped $droppedTotal samples"
                )
            }
            android.util.Log.i(
                "BestDroid",
                "stream peak=$peak gain=$gain frames=$total"
            )
            if (stopped.get()) {
                try {
                    callback.done()
                } catch (t: Throwable) {
                    android.util.Log.e("BestDroid", "callback.done failed", t)
                }
            } else {
                callback.done()
            }
            return total
        } catch (t: Throwable) {
            android.util.Log.e("BestDroid", "streaming failed", t)
            try {
                callback.error()
            } catch (t2: Throwable) {
                android.util.Log.e("BestDroid", "callback.error failed", t2)
            }
            return total
        }
    }

    // ------------------------------------------------------------------
    // Google fallback: same text via the fallback package's engine to a
    // temp wav, then its PCM is streamed to OUR callback — the client
    // hears speech even when the native engine has nothing.
    // ------------------------------------------------------------------

    private fun fallbackOrError(
        text: String, rate: Int, pitch: Int,
        callback: SynthesisCallback, reason: String
    ) {
        android.util.Log.w("BestDroid", "fallback engaged: $reason")
        val packageName = prefs(this).getString(KEY_FALLBACK_PACKAGE, DEFAULT_FALLBACK_PACKAGE)
            ?: DEFAULT_FALLBACK_PACKAGE
        // Google would read SSML tags aloud; hand it the tag-stripped text.
        val fallbackText = try {
            if ('<' in text && '>' in text) TextPipeline.stripTags(TextPipeline.decodeEntities(text)) else text
        } catch (t: Throwable) {
            text
        }
        try {
            val pcm = synthesizeWithFallback(fallbackText, rate, pitch, packageName)
            if (pcm != null && pcm.samples.any { it != 0.toShort() } && !stopped.get()) {
                streamPcm(callback, pcm.rate, listOf(pcm.samples))
                return
            }
            android.util.Log.w("BestDroid", "fallback produced no speech either")
        } catch (t: Throwable) {
            android.util.Log.e("BestDroid", "fallback failed", t)
        }
        try {
            if (stopped.get()) callback.done() else callback.error()
        } catch (t: Throwable) {
            android.util.Log.e("BestDroid", "callback failed", t)
        }
    }

    private data class FallbackPcm(val rate: Int, val samples: ShortArray)

    private fun synthesizeWithFallback(
        text: String, rate: Int, pitch: Int, packageName: String
    ): FallbackPcm? {
        val ready = CountDownLatch(1)
        var tts: TextToSpeech? = null
        val engineTts = TextToSpeech(this, { _ ->
            ready.countDown()
        }, packageName)
        tts = engineTts
        if (!ready.await(10, TimeUnit.SECONDS)) {
            try {
                tts.shutdown()
            } catch (t: Throwable) {
            }
            return null
        }
        try {
            val done = CountDownLatch(1)
            val ok = AtomicBoolean(false)
            val utteranceId = UUID.randomUUID().toString()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    ok.set(true); done.countDown()
                }
                override fun onError(id: String?) {
                    done.countDown()
                }
                @Deprecated("deprecated")
                override fun onError(id: String?, errorCode: Int) {
                    done.countDown()
                }
            })
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            val wav = File.createTempFile("bestdroid-fallback", ".wav", cacheDir)
            // setSpeechRate takes a float multiplier; our rate is percent.
            tts.setSpeechRate(rate / 100f)
            tts.setPitch(pitch / 100f)
            val rc = tts.synthesizeToFile(text, params, wav, utteranceId)
            if (rc != TextToSpeech.SUCCESS) return null
            if (!done.await(60, TimeUnit.SECONDS)) return null
            if (!ok.get() || !wav.exists() || wav.length() <= 44) return null
            return readWav(wav).also {
                try {
                    wav.delete()
                } catch (t: Throwable) {
                }
            }
        } finally {
            try {
                tts.shutdown()
            } catch (t: Throwable) {
            }
        }
    }

    /** Minimal WAV reader: 8/16-bit PCM, mono or stereo (stereo averaged). */
    private fun readWav(wav: File): FallbackPcm? {
        val bytes = wav.readBytes()
        if (bytes.size <= 44) return null
        fun u16(off: Int) = (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)
        fun u32(off: Int) = (u16(off)) or (u16(off + 2) shl 16)
        if (bytes[0] != 'R'.code.toByte() || bytes[8] != 'W'.code.toByte()) return null
        val channels = u16(22)
        val rate = u32(24)
        val bits = u16(34)
        // Find the data chunk (synthesizeToFile writes a plain 44-byte header).
        var dataOff = 44
        if (bytes[36] == 'd'.code.toByte()) {
            dataOff = 44
        }
        val samples: ShortArray = when (bits) {
            16 -> {
                val frames = (bytes.size - dataOff) / 2 / maxOf(channels, 1)
                ShortArray(frames) { f ->
                    if (channels >= 2) {
                        val a = u16(dataOff + f * 4).toShort().toInt()
                        val b = u16(dataOff + f * 4 + 2).toShort().toInt()
                        ((a + b) / 2).toShort()
                    } else {
                        u16(dataOff + f * 2).toShort()
                    }
                }
            }
            8 -> {
                val frames = (bytes.size - dataOff) / maxOf(channels, 1)
                ShortArray(frames) { f ->
                    val v = if (channels >= 2) {
                        val a = bytes[dataOff + f * 2].toInt() and 0xFF
                        val b = bytes[dataOff + f * 2 + 1].toInt() and 0xFF
                        (a + b) / 2
                    } else {
                        bytes[dataOff + f].toInt() and 0xFF
                    }
                    (((v - 128) shl 8)).toShort()
                }
            }
            else -> return null
        }
        return FallbackPcm(rate, samples)
    }
}
