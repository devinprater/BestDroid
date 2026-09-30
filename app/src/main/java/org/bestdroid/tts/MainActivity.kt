package org.bestdroid.tts

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/** Minimal accessible test UI: drives the BestDroid engine end to end. */
class MainActivity : AppCompatActivity() {

    private var tts: TextToSpeech? = null
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        CldrText.init(this)

        statusText = findViewById(R.id.statusText)
        resultText = findViewById(R.id.resultText)
        val input = findViewById<EditText>(R.id.inputText)
        val spinner = findViewById<Spinner>(R.id.voiceSpinner)
        val rateBar = findViewById<SeekBar>(R.id.rateBar)
        val pitchBar = findViewById<SeekBar>(R.id.pitchBar)
        val rateLabel = findViewById<TextView>(R.id.rateLabel)
        val pitchLabel = findViewById<TextView>(R.id.pitchLabel)
        val fallbackCheck = findViewById<CheckBox>(R.id.fallbackCheck)
        val fallbackPackage = findViewById<EditText>(R.id.fallbackPackage)

        val prefs = BestDroidService.prefs(this)

        // Voice list: no engine needed, the catalog is static.
        val builds = VoiceCatalog.all.map { it.build }
        val names = VoiceCatalog.all.map { VoiceCatalog.displayName(it.build) }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val savedVoice = prefs.getString(BestDroidService.KEY_DEFAULT_VOICE, BestDroidService.DEFAULT_VOICE)
        spinner.setSelection(maxOf(builds.indexOf(savedVoice), 0))
        input.setText(VoiceCatalog.sampleFor(builds[spinner.selectedItemPosition]))

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long
            ) {
                input.setText(VoiceCatalog.sampleFor(builds[pos]))
                // Selecting a voice saves it immediately: that is the voice
                // TalkBack uses, no need to press Speak first.
                prefs.edit().putString(BestDroidService.KEY_DEFAULT_VOICE, builds[pos]).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        rateBar.setOnSeekBarChangeListener(simpleListener {
            rateLabel.text = "Rate: ${rateBar.progress + 25}%"
        })
        pitchBar.setOnSeekBarChangeListener(simpleListener {
            pitchLabel.text = "Pitch: ${pitchBar.progress + 25}%"
        })

        fallbackCheck.isChecked = prefs.getBoolean(BestDroidService.KEY_FALLBACK_ENABLED, true)
        val overrideVoiceCheck = findViewById<CheckBox>(R.id.overrideVoiceCheck)
        val shortenPausesCheck = findViewById<CheckBox>(R.id.shortenPausesCheck)
        overrideVoiceCheck.isChecked = prefs.getBoolean(BestDroidService.KEY_OVERRIDE_VOICE, true)
        shortenPausesCheck.isChecked = prefs.getBoolean(BestDroidService.KEY_SHORTEN_PAUSES, true)
        overrideVoiceCheck.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(BestDroidService.KEY_OVERRIDE_VOICE, checked).apply()
        }
        shortenPausesCheck.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(BestDroidService.KEY_SHORTEN_PAUSES, checked).apply()
        }
        fallbackPackage.setText(
            prefs.getString(
                BestDroidService.KEY_FALLBACK_PACKAGE,
                BestDroidService.DEFAULT_FALLBACK_PACKAGE
            )
        )
        fallbackCheck.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(BestDroidService.KEY_FALLBACK_ENABLED, checked).apply()
        }

        // Native engine status, straight from the library.
        try {
            val nativeBuilds = NativeBst.builds()
            val missing = builds.filter { it !in nativeBuilds }
            statusText.text = if (missing.isEmpty()) {
                val rates = nativeBuilds.distinct()
                    .mapNotNull { b ->
                        try {
                            NativeBst.nativeRate(b)
                        } catch (e: Throwable) {
                            null
                        }
                    }.distinct().sorted()
                "Native OK: ${nativeBuilds.size} builds, rates ${rates.joinToString("/")} Hz, tables embedded."
            } else {
                "Native MISSING builds: ${missing.joinToString(",")}"
            }
        } catch (e: Throwable) {
            statusText.text = "Native load FAILED: ${e.message}"
        }

        tts = TextToSpeech(this, { status ->
            resultText.text = if (status == TextToSpeech.SUCCESS) {
                "Engine ready. Pick a voice and press Speak."
            } else {
                "Engine init failed ($status). Is BestDroid enabled as a TTS engine?"
            }
            android.util.Log.i(
                TAG,
                "tts init status=$status defaultEngine=${tts?.defaultEngine} " +
                    "engines=${tts?.engines?.joinToString { it.name }}"
            )
            if (status == TextToSpeech.SUCCESS) tryAutospeak(builds)
        }, ENGINE_PACKAGE)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                runOnUiThread { resultText.text = "Speaking…" }
            }
            override fun onDone(id: String?) {
                runOnUiThread { resultText.text = "Done." }
            }
            override fun onError(id: String?) {
                runOnUiThread { resultText.text = "Error speaking." }
            }
            @Deprecated("deprecated")
            override fun onError(id: String?, errorCode: Int) {
                onError(id)
            }
        })

        findViewById<Button>(R.id.speakButton).setOnClickListener {
            val engine = tts
            if (engine == null) {
                resultText.text = "Engine not ready yet."
                return@setOnClickListener
            }
            val build = builds[spinner.selectedItemPosition]
            prefs.edit().putString(BestDroidService.KEY_DEFAULT_VOICE, build).apply()
            prefs.edit().putString(
                BestDroidService.KEY_FALLBACK_PACKAGE,
                fallbackPackage.text.toString().ifBlank {
                    BestDroidService.DEFAULT_FALLBACK_PACKAGE
                }
            ).apply()
            // Debug headless check: --ez novoice true skips setVoice, so the
            // request carries only a language, exactly like TalkBack sends.
            if (intent.getBooleanExtra(EXTRA_NO_VOICE, false)) {
                engine.language = Locale.forLanguageTag(VoiceCatalog.languageFor(build))
            } else {
                val locale = Locale.forLanguageTag(VoiceCatalog.languageFor(build))
                engine.language = locale
                engine.setVoice(
                    Voice(
                        build, locale,
                        Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, null
                    )
                )
            }
            engine.setSpeechRate((rateBar.progress + 25) / 100f)
            engine.setPitch((pitchBar.progress + 25) / 100f)
            val text = input.text.toString().ifBlank { "Hello." }
            val rc = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bestdroid-test")
            if (rc != TextToSpeech.SUCCESS) resultText.text = "speak() returned $rc"
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            tts?.stop()
            resultText.text = "Stopped."
        }
    }

    /**
     * Headless test hook (debug builds only): launch with
     * `--es autospeak "..."` (+ optional `--es voice <build>`) to speak
     * through the framework TTS path without touching the screen.
     */
    private fun tryAutospeak(builds: List<String>) {
        if (!BuildConfig.DEBUG) return
        val text = intent.getStringExtra(EXTRA_AUTOSPEAK) ?: return
        val wantBuild = intent.getStringExtra(EXTRA_VOICE)
        val spinner = findViewById<Spinner>(R.id.voiceSpinner)
        val input = findViewById<EditText>(R.id.inputText)
        if (wantBuild != null) {
            val idx = builds.indexOf(wantBuild)
            if (idx >= 0) spinner.setSelection(idx)
        }
        input.setText(text)
        intent.getIntExtra(EXTRA_RATE, -1).takeIf { it >= 0 }?.let { rate ->
            val bar = findViewById<SeekBar>(R.id.rateBar)
            bar.progress = (rate - 25).coerceIn(0, bar.max)
        }
        android.util.Log.i(
            TAG,
            "autospeak: build=${builds[spinner.selectedItemPosition]} text=$text"
        )
        findViewById<Button>(R.id.speakButton).performClick()
        android.util.Log.i(TAG, "autospeak submitted voice=${tts?.voice?.name}")
    }

    private fun simpleListener(onChange: () -> Unit): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) = onChange()
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        }
    }

    override fun onDestroy() {
        try {
            tts?.shutdown()
        } catch (t: Throwable) {
        }
        super.onDestroy()
    }

    companion object {
        private const val ENGINE_PACKAGE = "org.bestdroid.tts"
        private const val TAG = "BestDroidUi"

        /** Debug-only launch extras for headless verification. */
        const val EXTRA_AUTOSPEAK = "autospeak"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_NO_VOICE = "novoice"
        const val EXTRA_RATE = "rate"
    }
}
