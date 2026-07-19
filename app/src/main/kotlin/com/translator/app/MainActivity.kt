package com.translator.app

import android.Manifest
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import audio.soniqo.speech.ModelDownloadWorker
import audio.soniqo.speech.ModelPrecision
import audio.soniqo.speech.PipelineMode
import audio.soniqo.speech.SpeechConfig
import audio.soniqo.speech.SpeechEvent
import audio.soniqo.speech.SpeechPipeline
import audio.soniqo.speech.SttModel
import audio.soniqo.speech.TtsModel
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Offline translator MVP.
 *
 * Scope intentionally stays pragmatic: Italian, English and German are the
 * stable set; extra ML Kit languages can be enabled as a beta layer on top of
 * Parakeet TDT, without changing speech-core.
 */
class MainActivity : ComponentActivity() {

    private data class LanguageOption(
        val name: String,
        val promptName: String,
        val mlKitCode: String,
        val ttsCode: String,
        val flag: String,
    )

    private data class TranslationKey(
        val source: String,
        val target: String,
    )

    private data class Exchange(
        val source: LanguageOption,
        val target: LanguageOption,
        val original: String,
        val translated: String,
    )

    private var pipeline: SpeechPipeline? = null
    private var audioRecord: AudioRecord? = null
    private var systemTts: TextToSpeech? = null

    @Volatile private var recording = false
    @Volatile private var micMuted = false
    private var pipelineStarted = false
    private var observingDownload = false
    private var conversationActive = false
    private var processing = false
    private var imeWasVisible = false
    private var speechModelsReady = false
    private var translationModelsReady = false
    private var systemTtsReady = false
    private var ttsEnabled = true
    private var extraLanguagesEnabled = false
    private var textScale = 1.0f
    private var selectedSourceIndex = 1
    private var selectedTargetIndex = 0

    private val translators = mutableMapOf<TranslationKey, Translator>()
    private val readyTranslationKeys = mutableSetOf<TranslationKey>()
    private var languageIdentifier: LanguageIdentifier? = null
    private var textRecognizer: TextRecognizer? = null
    private var photoUri: Uri? = null
    private var piperTts: PiperTts? = null

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            if (saved) photoUri?.let { processPhoto(it) }
        }
    private var continuousMode = true
    private var usePiper = true
    private val history = mutableListOf<Exchange>()
    private val downloadingVoices = mutableSetOf<String>()

    private lateinit var statusView: TextView
    private lateinit var statusDot: View
    private lateinit var downloadProgress: ProgressBar
    private lateinit var sourceValue: TextView
    private lateinit var targetValue: TextView
    private lateinit var sourceFlag: FlagView
    private lateinit var targetFlag: FlagView
    private lateinit var sourcePill: LinearLayout
    private lateinit var targetPill: LinearLayout
    private lateinit var swapButton: TextView
    private lateinit var micButton: MicButtonView
    private lateinit var keyboardButton: FrameLayout
    private lateinit var cameraButton: FrameLayout
    private lateinit var clearButton: FrameLayout
    private lateinit var sourceText: EditText
    private lateinit var targetText: TextView
    private lateinit var waveform: LiveWaveformView
    private lateinit var settingsPanel: ScrollView
    private lateinit var settingsContent: LinearLayout

    companion object {
        private const val TAG = "Translator"
        private const val PREFS = "translator-settings"
        private const val PREF_TTS_ENABLED = "tts-enabled"
        private const val PREF_CONTINUOUS = "continuous-mode"
        private const val PREF_USE_PIPER = "use-piper"
        private const val PREF_EXTRA_LANGUAGES = "extra-languages"
        private const val PREF_TEXT_SCALE = "text-scale"
        private const val MIC_PERMISSION = 1

        private val STT_MODEL = SttModel.PARAKEET
        private val PIPELINE_TTS_MODEL = TtsModel.NONE
        private val CORE_LANGUAGES = listOf(
            LanguageOption("Italiano", "italiano", TranslateLanguage.ITALIAN, "it", "🇮🇹"),
            LanguageOption("Inglese", "inglese", TranslateLanguage.ENGLISH, "en", "🇬🇧"),
            LanguageOption("Tedesco", "tedesco", TranslateLanguage.GERMAN, "de", "🇩🇪"),
        )
        private val EXTRA_LANGUAGES = listOf(
            LanguageOption("Bulgaro", "bulgaro", TranslateLanguage.BULGARIAN, "bg", "🇧🇬"),
            LanguageOption("Ceco", "ceco", TranslateLanguage.CZECH, "cs", "🇨🇿"),
            LanguageOption("Danese", "danese", TranslateLanguage.DANISH, "da", "🇩🇰"),
            LanguageOption("Greco", "greco", TranslateLanguage.GREEK, "el", "🇬🇷"),
            LanguageOption("Spagnolo", "spagnolo", TranslateLanguage.SPANISH, "es", "🇪🇸"),
            LanguageOption("Estone", "estone", TranslateLanguage.ESTONIAN, "et", "🇪🇪"),
            LanguageOption("Finlandese", "finlandese", TranslateLanguage.FINNISH, "fi", "🇫🇮"),
            LanguageOption("Francese", "francese", TranslateLanguage.FRENCH, "fr", "🇫🇷"),
            LanguageOption("Croato", "croato", TranslateLanguage.CROATIAN, "hr", "🇭🇷"),
            LanguageOption("Ungherese", "ungherese", TranslateLanguage.HUNGARIAN, "hu", "🇭🇺"),
            LanguageOption("Lituano", "lituano", TranslateLanguage.LITHUANIAN, "lt", "🇱🇹"),
            LanguageOption("Lettone", "lettone", TranslateLanguage.LATVIAN, "lv", "🇱🇻"),
            LanguageOption("Maltese", "maltese", TranslateLanguage.MALTESE, "mt", "🇲🇹"),
            LanguageOption("Olandese", "olandese", TranslateLanguage.DUTCH, "nl", "🇳🇱"),
            LanguageOption("Polacco", "polacco", TranslateLanguage.POLISH, "pl", "🇵🇱"),
            LanguageOption("Portoghese", "portoghese", TranslateLanguage.PORTUGUESE, "pt", "🇵🇹"),
            LanguageOption("Rumeno", "rumeno", TranslateLanguage.ROMANIAN, "ro", "🇷🇴"),
            LanguageOption("Russo", "russo", TranslateLanguage.RUSSIAN, "ru", "🇷🇺"),
            LanguageOption("Slovacco", "slovacco", TranslateLanguage.SLOVAK, "sk", "🇸🇰"),
            LanguageOption("Sloveno", "sloveno", TranslateLanguage.SLOVENIAN, "sl", "🇸🇮"),
            LanguageOption("Svedese", "svedese", TranslateLanguage.SWEDISH, "sv", "🇸🇪"),
            LanguageOption("Ucraino", "ucraino", TranslateLanguage.UKRAINIAN, "uk", "🇺🇦"),
        )

        // Light palette, reference-style
        private const val BG = "#E8EAEE"
        private const val CARD = "#FBFCFD"
        private const val PILL = "#EDEFF3"
        private const val TEXT_PRIMARY = "#17191C"
        private const val TEXT_SECONDARY = "#8A93A1"
        private const val ICON = "#3C434D"
        private const val BLACK_BTN = "#15181C"
        private const val ACCENT = "#FF5A4E"
        private const val GREEN = "#12A667"
        private const val AMBER = "#C98A0B"
        private const val RED = "#DC4B41"

        private const val SRC_PLACEHOLDER = "Parla al microfono\no tocca qui per scrivere."
        private const val TGT_PLACEHOLDER = "La traduzione apparirà qui."
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun dpF(value: Float): Float =
        value * resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        ttsEnabled = prefs.getBoolean(PREF_TTS_ENABLED, true)
        continuousMode = prefs.getBoolean(PREF_CONTINUOUS, true)
        usePiper = prefs.getBoolean(PREF_USE_PIPER, true)
        extraLanguagesEnabled = prefs.getBoolean(PREF_EXTRA_LANGUAGES, false)
        textScale = prefs.getFloat(PREF_TEXT_SCALE, 1.0f).coerceIn(0.9f, 1.25f)
        languageIdentifier = LanguageIdentification.getClient()
        piperTts = PiperTts(applicationContext)
        buildUI()
        initSystemTts()
        ensureTranslationModelsForSelection()
        loadSpeechPipeline()
        ensureVoicesForSelection()

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (settingsPanel.visibility == View.VISIBLE) {
                    hideSettings()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    override fun onStop() {
        super.onStop()
        stopSpeech()
        if (conversationActive) {
            stopConversation()
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                if (recording) stopMicrophone()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        systemTts?.stop()
        systemTts?.shutdown()
        systemTts = null
        pipeline?.close()
        pipeline = null
        translators.values.forEach { it.close() }
        translators.clear()
        languageIdentifier?.close()
        languageIdentifier = null
        textRecognizer?.close()
        textRecognizer = null
        piperTts?.release()
        piperTts = null
    }

    // ------------------------------------------------------------ photo OCR

    private fun capturePhoto() {
        if (conversationActive) return
        val photosDir = File(cacheDir, "photos").apply { mkdirs() }
        val file = File(photosDir, "capture.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        photoUri = uri
        runCatching { takePicture.launch(uri) }.onFailure {
            Toast.makeText(this, "Fotocamera non disponibile.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processPhoto(uri: Uri) {
        setStatus("Leggo il testo dalla foto...")
        val image = try {
            InputImage.fromFilePath(this, uri)
        } catch (e: Throwable) {
            Log.e(TAG, "Photo load failed", e)
            setStatus("Errore lettura foto")
            return
        }
        val recognizer = textRecognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .also { textRecognizer = it }
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = visionText.text.replace('\n', ' ').trim()
                if (text.isBlank()) {
                    setStatus("Nessun testo trovato nella foto")
                    return@addOnSuccessListener
                }
                sourceText.setText(text)
                detectDirection(text) { source, target ->
                    runTypedTranslation(text, source, target)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed", e)
                setStatus("Errore riconoscimento testo: ${e.message}")
            }
    }

    private fun initSystemTts() {
        systemTts = TextToSpeech(this) { status ->
            systemTtsReady = status == TextToSpeech.SUCCESS
        }
        systemTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                if (utteranceId?.startsWith("conv-") == true) finishExchange()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId?.startsWith("conv-") == true) finishExchange()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId?.startsWith("conv-") == true) finishExchange()
            }
        })
    }

    // ------------------------------------------------------------------ UI

    private fun buildUI() {
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(BG))
            // Give focus somewhere to land when the EditText releases it,
            // otherwise clearFocus() bounces focus straight back to the field.
            isFocusable = true
            isFocusableInTouchMode = true
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, sb.top, v.paddingRight, sb.bottom)
            // When the keyboard goes away (gesture/back), drop focus so the
            // caret stops blinking. Only on the visible -> hidden transition:
            // clearing focus on any "not visible" dispatch would steal focus
            // while the IME is still opening.
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeWasVisible && !imeVisible &&
                ::sourceText.isInitialized && sourceText.hasFocus()
            ) {
                sourceText.clearFocus()
            }
            imeWasVisible = imeVisible
            insets
        }

        root.addView(header())

        val cards = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(4))
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        val source = translationCard(isSource = true)
        cards.addView(source)

        swapButton = TextView(this).apply {
            text = "⇅"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = roundedColor(BLACK_BTN, dpF(28f))
            elevation = dpF(8f)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, dp(-24), 0, dp(-24))
            }
            setOnClickListener { swapLanguages() }
        }
        cards.addView(swapButton)

        val target = translationCard(isSource = false)
        cards.addView(target)

        root.addView(cards)
        root.addView(bottomPanel())

        val rootFrame = FrameLayout(this)
        rootFrame.addView(root)

        settingsPanel = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(BG))
            isVerticalScrollBarEnabled = false
            isClickable = true
            visibility = View.GONE
            isFillViewport = true
        }
        settingsContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(28))
        }
        settingsPanel.addView(settingsContent)
        rootFrame.addView(settingsPanel)

        ViewCompat.setOnApplyWindowInsetsListener(settingsPanel) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, sb.top, v.paddingRight, sb.bottom)
            insets
        }

        setContentView(rootFrame)
        updateLanguageUi()
        updateButtonStates()
    }

    // -------------------------------------------------------- settings page

    private fun showSettings() {
        rebuildSettings()
        settingsPanel.visibility = View.VISIBLE
    }

    private fun hideSettings() {
        settingsPanel.visibility = View.GONE
    }

    private fun rebuildSettings() {
        settingsContent.removeAllViews()

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(14))
        }
        headerRow.addView(TextView(this).apply {
            text = "‹"
            textSize = 26f
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            gravity = Gravity.CENTER
            background = roundedColor(CARD, dpF(21f))
            elevation = dpF(2f)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                setMargins(0, 0, dp(14), 0)
            }
            setPadding(0, 0, 0, dp(4))
            setOnClickListener { hideSettings() }
        })
        headerRow.addView(TextView(this).apply {
            text = "Impostazioni"
            textSize = 22f
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        settingsContent.addView(headerRow)

        // ------------------------------------------------------- language card
        val languageCard = settingsCard("Lingue")
        languageCard.addView(switchRow(
            "Lingue extra beta",
            "Mostra altre lingue compatibili con ML Kit e con la copertura ampia di Parakeet TDT. I modelli di traduzione si scaricano solo per la coppia scelta.",
            extraLanguagesEnabled,
        ) { checked ->
            extraLanguagesEnabled = checked
            savePref(PREF_EXTRA_LANGUAGES, checked)
            normalizeSelectedLanguageIndices()
            updateLanguageUi()
            ensureTranslationModelsForSelection()
            ensureVoicesForSelection()
            rebuildSettings()
        })
        languageCard.addView(TextView(this).apply {
            text = if (extraLanguagesEnabled) {
                "${availableLanguages().size} lingue visibili. Voce neurale locale disponibile per italiano, inglese e tedesco; per le altre lingue si usa la voce Android se installata."
            } else {
                "Modalità stabile: italiano, inglese e tedesco."
            }
            textSize = 12f
            setTextColor(Color.parseColor(TEXT_SECONDARY))
            setPadding(0, dp(10), 0, 0)
            setLineSpacing(dpF(3f), 1f)
        })
        settingsContent.addView(languageCard)

        // ------------------------------------------------ conversation card
        val convCard = settingsCard("Conversazione")
        convCard.addView(switchRow(
            "Conversazione continua",
            "Dopo ogni frase l'app torna in ascolto da sola. Se disattivata, premi il microfono per ogni frase.",
            continuousMode,
        ) { checked ->
            continuousMode = checked
            savePref(PREF_CONTINUOUS, checked)
        })
        settingsContent.addView(convCard)

        // ---------------------------------------------------------- text card
        val textCard = settingsCard("Testo")
        textCard.addView(TextView(this).apply {
            text = "Dimensione testo traduzioni"
            textSize = 15f
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        textCard.addView(TextView(this).apply {
            text = "Regola solo i testi principali: originale e traduzione."
            textSize = 12f
            setTextColor(Color.parseColor(TEXT_SECONDARY))
            setPadding(0, dp(4), 0, dp(12))
        })
        val scaleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(
            "Piccolo" to 0.9f,
            "Normale" to 1.0f,
            "Grande" to 1.18f,
        ).forEach { (label, scale) ->
            scaleRow.addView(textScaleButton(label, scale))
        }
        textCard.addView(scaleRow)
        settingsContent.addView(textCard)

        // ------------------------------------------------------- voice card
        val voiceCard = settingsCard("Voce")
        voiceCard.addView(switchRow(
            "Leggi traduzione ad alta voce",
            "L'interprete pronuncia ogni traduzione appena pronta.",
            ttsEnabled,
        ) { checked ->
            setTtsEnabled(checked)
        })

        voiceCard.addView(settingsDivider())
        voiceCard.addView(engineRow(
            "Voce neurale in-app (consigliata)",
            "Qualità alta, sempre offline dopo il download delle voci.",
            selected = usePiper,
        ) {
            usePiper = true
            savePref(PREF_USE_PIPER, true)
            ensureVoicesForSelection()
            rebuildSettings()
        })
        voiceCard.addView(engineRow(
            "Voce di sistema Android",
            "Usa le voci del telefono (dipende da quelle installate).",
            selected = !usePiper,
        ) {
            usePiper = false
            savePref(PREF_USE_PIPER, false)
            rebuildSettings()
        })
        settingsContent.addView(voiceCard)

        if (usePiper) {
            val voicesCard = settingsCard("Voci neurali (Piper)")
            PiperTts.VOICES.forEach { voice ->
                val language = CORE_LANGUAGES.firstOrNull { it.ttsCode == voice.langCode } ?: return@forEach
                voicesCard.addView(piperVoiceRow(language, voice))
            }
            voicesCard.addView(TextView(this).apply {
                text = "Le voci si scaricano una sola volta (Wi-Fi consigliato) e poi funzionano completamente offline."
                textSize = 12f
                setTextColor(Color.parseColor(TEXT_SECONDARY))
                setPadding(0, dp(10), 0, 0)
            })
            settingsContent.addView(voicesCard)
        } else {
            val androidCard = settingsCard("Voci di sistema")
            val voicesStatus = availableLanguages().joinToString("\n") { lang ->
                val availability = systemTts?.isLanguageAvailable(localeFor(lang))
                val ok = systemTtsReady && availability != null && availability >= TextToSpeech.LANG_AVAILABLE
                "${lang.flag} ${lang.name}: ${if (ok) "voce pronta" else "voce mancante"}"
            }
            androidCard.addView(TextView(this).apply {
                text = voicesStatus
                textSize = 14f
                setTextColor(Color.parseColor(TEXT_PRIMARY))
                setLineSpacing(dpF(5f), 1f)
            })
            androidCard.addView(TextView(this).apply {
                text = "Scarica voci Android"
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = roundedColor(BLACK_BTN, dpF(19f))
                setPadding(dp(16), dp(9), dp(16), dp(9))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(0, dp(12), 0, 0) }
                setOnClickListener {
                    runCatching {
                        startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                    }.onFailure {
                        Toast.makeText(this@MainActivity, "Impossibile aprire il download voci.", Toast.LENGTH_SHORT).show()
                    }
                }
            })
            settingsContent.addView(androidCard)
        }
    }

    private fun settingsCard(title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedColor(CARD, dpF(22f))
            elevation = dpF(3f)
            setPadding(dp(18), dp(14), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, dp(14)) }
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 13f
                setTextColor(Color.parseColor(TEXT_SECONDARY))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                isAllCaps = true
                setPadding(0, 0, 0, dp(10))
            })
        }

    private fun settingsDivider(): View =
        View(this).apply {
            setBackgroundColor(Color.parseColor("#E3E6EB"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1),
            ).apply { setMargins(0, dp(12), 0, dp(12)) }
        }

    private fun textScaleButton(label: String, scale: Float): TextView =
        TextView(this).apply {
            val selected = kotlin.math.abs(textScale - scale) < 0.03f
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor(if (selected) "#FFFFFF" else TEXT_PRIMARY))
            background = roundedColor(if (selected) BLACK_BTN else PILL, dpF(18f))
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(4), 0, dp(4), 0) }
            setOnClickListener {
                setTextScale(scale)
                rebuildSettings()
            }
        }

    private fun switchRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        texts.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(Color.parseColor(TEXT_SECONDARY))
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(texts)
        row.addView(Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        })
        return row
    }

    private fun engineRow(
        title: String,
        subtitle: String,
        selected: Boolean,
        onSelect: () -> Unit,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { if (!selected) onSelect() }
        }
        row.addView(TextView(this).apply {
            text = if (selected) "●" else "○"
            textSize = 17f
            setTextColor(Color.parseColor(if (selected) ACCENT else TEXT_SECONDARY))
            setPadding(0, 0, dp(12), 0)
        })
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        texts.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(Color.parseColor(TEXT_SECONDARY))
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(texts)
        return row
    }

    private fun piperVoiceRow(language: LanguageOption, voice: PiperTts.Voice): LinearLayout {
        val piper = piperTts
        val ready = piper?.isVoiceReady(voice.langCode) == true
        val downloading = downloadingVoices.contains(voice.langCode)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }
        row.addView(TextView(this).apply {
            text = "${language.flag} ${language.name} — ${voice.displayName}"
            textSize = 15f
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        val action = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(14), dp(7), dp(14), dp(7))
        }
        when {
            ready -> {
                action.text = "✓ Pronta"
                action.setTextColor(Color.parseColor(GREEN))
                action.background = roundedColor(PILL, dpF(17f))
                action.setOnClickListener {
                    lifecycleScope.launch(Dispatchers.IO) {
                        piperTts?.speak(sampleTextFor(language), voice.langCode)
                    }
                }
            }
            downloading -> {
                action.text = "..."
                action.setTextColor(Color.parseColor(TEXT_SECONDARY))
                action.background = roundedColor(PILL, dpF(17f))
            }
            else -> {
                action.text = "Scarica (${voice.sizeMb} MB)"
                action.setTextColor(Color.WHITE)
                action.background = roundedColor(BLACK_BTN, dpF(17f))
                action.setOnClickListener { startVoiceDownload(language, voice, action) }
            }
        }
        row.addView(action)
        return row
    }

    private fun startVoiceDownload(
        language: LanguageOption,
        voice: PiperTts.Voice,
        action: TextView,
    ) {
        val piper = piperTts ?: return
        if (!downloadingVoices.add(voice.langCode)) return
        action.text = "0%"
        action.setTextColor(Color.parseColor(TEXT_SECONDARY))
        action.background = roundedColor(PILL, dpF(17f))
        action.setOnClickListener(null)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                piper.downloadVoice(voice) { percent ->
                    lifecycleScope.launch(Dispatchers.Main) { action.text = "$percent%" }
                }
                withContext(Dispatchers.Main) {
                    downloadingVoices.remove(voice.langCode)
                    Toast.makeText(
                        this@MainActivity,
                        "Voce ${language.name} pronta.",
                        Toast.LENGTH_SHORT,
                    ).show()
                    if (settingsPanel.visibility == View.VISIBLE) rebuildSettings()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Voice download failed", e)
                withContext(Dispatchers.Main) {
                    downloadingVoices.remove(voice.langCode)
                    Toast.makeText(
                        this@MainActivity,
                        "Download voce non riuscito: ${e.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                    if (settingsPanel.visibility == View.VISIBLE) rebuildSettings()
                }
            }
        }
    }

    private fun ensureVoicesForSelection() {
        if (!ttsEnabled || !usePiper) return
        listOf(selectedSource(), selectedTarget())
            .distinctBy { it.ttsCode }
            .forEach { language ->
                val voice = piperTts?.voiceFor(language.ttsCode) ?: return@forEach
                ensureVoiceReady(language, voice)
            }
    }

    private fun ensureVoiceReady(language: LanguageOption, voice: PiperTts.Voice) {
        val piper = piperTts ?: return
        if (piper.isVoiceReady(voice.langCode)) return
        if (!downloadingVoices.add(voice.langCode)) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                piper.downloadVoice(voice) { percent ->
                    setStatus("Scarico voce ${language.name}: $percent%")
                }
                withContext(Dispatchers.Main) {
                    downloadingVoices.remove(voice.langCode)
                    if (settingsPanel.visibility == View.VISIBLE) rebuildSettings()
                    if (pipeline != null && areSelectedTranslationModelsReady()) setStatus("Pronto")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Auto voice download failed", e)
                withContext(Dispatchers.Main) {
                    downloadingVoices.remove(voice.langCode)
                    setStatus("Voce ${language.name} non scaricata")
                    if (settingsPanel.visibility == View.VISIBLE) rebuildSettings()
                }
            }
        }
    }

    private fun sampleTextFor(language: LanguageOption): String = when (language.ttsCode) {
        "it" -> "Ciao, questa è la mia voce."
        "de" -> "Hallo, das ist meine Stimme."
        else -> "Hi, this is my voice."
    }

    private fun savePref(key: String, value: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(key, value).apply()
    }

    private fun header(): FrameLayout {
        val bar = FrameLayout(this).apply {
            setPadding(dp(18), dp(12), dp(18), dp(10))
        }

        bar.addView(TextView(this).apply {
            text = "Interprete"
            textSize = 20f
            setTextColor(Color.parseColor(TEXT_PRIMARY))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        })

        val offlinePill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedColor(CARD, dpF(18f))
            elevation = dpF(2f)
            setPadding(dp(10), dp(7), dp(12), dp(7))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL,
            )
        }
        offlinePill.addView(View(this).apply {
            background = roundedColor(GREEN, dpF(4f))
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                setMargins(0, 0, dp(6), 0)
            }
        })
        offlinePill.addView(TextView(this).apply {
            text = "Offline"
            textSize = 12f
            setTextColor(Color.parseColor(TEXT_SECONDARY))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        bar.addView(offlinePill)

        bar.addView(HistoryIconView(this).apply {
            background = roundedColor(CARD, dpF(21f))
            elevation = dpF(2f)
            layoutParams = FrameLayout.LayoutParams(
                dp(42),
                dp(42),
                Gravity.END or Gravity.CENTER_VERTICAL,
            ).apply { rightMargin = dp(50) }
            setOnClickListener { showHistory() }
        })

        bar.addView(TuneButtonView(this).apply {
            background = roundedColor(CARD, dpF(21f))
            elevation = dpF(2f)
            layoutParams = FrameLayout.LayoutParams(
                dp(42),
                dp(42),
                Gravity.END or Gravity.CENTER_VERTICAL,
            )
            setOnClickListener { showSettings() }
        })

        return bar
    }

    private fun translationCard(isSource: Boolean): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedColor(CARD, dpF(30f))
            elevation = dpF(4f)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        if (isSource) {
            val pill = languagePill { showLanguagePicker(isSource = true) }
            sourcePill = pill.first
            sourceFlag = pill.second
            sourceValue = pill.third
            controls.addView(sourcePill)
            controls.addView(TextView(this).apply {
                text = "Rileva"
                textSize = 13f
                setTextColor(Color.parseColor(TEXT_SECONDARY))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                background = roundedColor(PILL, dpF(21f))
                setPadding(dp(12), dp(9), dp(12), dp(9))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(dp(8), 0, 0, 0) }
                setOnClickListener {
                    if (conversationActive) return@setOnClickListener
                    val text = sourceText.text.toString().trim()
                    if (text.isBlank()) {
                        Toast.makeText(
                            this@MainActivity,
                            "Scrivi o detta una frase, poi tocca Rileva.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        hideKeyboard()
                        detectDirection(text) { source, target ->
                            runTypedTranslation(text, source, target)
                        }
                    }
                }
            })
            controls.addView(spacer())
            clearButton = iconCircle(ClearIconView(this)) {
                if (!conversationActive) {
                    hideKeyboard()
                    sourceText.setText("")
                    targetText.text = TGT_PLACEHOLDER
                    targetText.setTextColor(Color.parseColor(TEXT_SECONDARY))
                    if (pipeline != null && areSelectedTranslationModelsReady()) setStatus("Pronto")
                }
            }
            controls.addView(clearButton)
            controls.addView(iconCircle(SpeakerIconView(this)) {
                speakIfReal(sourceText.text.toString(), SRC_PLACEHOLDER, selectedSource())
            })
        } else {
            val pill = languagePill { showLanguagePicker(isSource = false) }
            targetPill = pill.first
            targetFlag = pill.second
            targetValue = pill.third
            controls.addView(targetPill)
            controls.addView(spacer())
            controls.addView(iconCircle(CopyIconView(this)) {
                val text = targetText.text.toString()
                if (text != TGT_PLACEHOLDER) copyText(text)
            })
            controls.addView(iconCircle(SpeakerIconView(this)) {
                speakIfReal(targetText.text.toString(), TGT_PLACEHOLDER, selectedTarget())
            })
            controls.addView(iconCircle(StopIconView(this)) {
                stopSpeech(interruptedByUser = true)
            })
        }
        card.addView(controls)

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        val textView: TextView = if (isSource) {
            EditText(this).apply {
                hint = SRC_PLACEHOLDER
                setHintTextColor(Color.parseColor(TEXT_SECONDARY))
                setTextColor(Color.parseColor(TEXT_PRIMARY))
                background = null
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_ACTION_DONE
                setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
                setHorizontallyScrolling(false)
                maxLines = 8
                setOnEditorActionListener { _, actionId, event ->
                    when {
                        actionId == EditorInfo.IME_ACTION_DONE -> {
                            translateTyped()
                            true
                        }
                        // Some keyboards deliver the action key as a raw Enter KeyEvent.
                        actionId == EditorInfo.IME_NULL &&
                            event?.action == android.view.KeyEvent.ACTION_DOWN -> {
                            translateTyped()
                            true
                        }
                        actionId == EditorInfo.IME_NULL -> true
                        else -> false
                    }
                }
            }
        } else {
            TextView(this).apply {
                text = TGT_PLACEHOLDER
                setTextColor(Color.parseColor(TEXT_SECONDARY))
            }
        }
        textView.apply {
            textSize = 23f * textScale
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setLineSpacing(dpF(3f), 1.02f)
            setPadding(dp(6), dp(10), dp(6), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        scroll.addView(textView)
        card.addView(scroll)

        if (isSource) sourceText = textView as EditText else targetText = textView
        return card
    }

    private fun spacer(): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

    private fun languagePill(onClick: () -> Unit): Triple<LinearLayout, FlagView, TextView> {
        val flag = FlagView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                setMargins(0, 0, dp(8), 0)
            }
        }
        val value = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor(TEXT_SECONDARY))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val chevron = TextView(this).apply {
            text = "⌄"
            textSize = 13f
            setTextColor(Color.parseColor(TEXT_SECONDARY))
            setPadding(dp(5), 0, 0, dp(3))
        }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedColor(PILL, dpF(21f))
            setPadding(dp(10), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
            addView(flag)
            addView(value)
            addView(chevron)
        }
        return Triple(pill, flag, value)
    }

    private fun iconCircle(icon: View, onClick: () -> Unit): FrameLayout =
        FrameLayout(this).apply {
            background = roundedColor(PILL, dpF(21f))
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                setMargins(dp(8), 0, 0, 0)
            }
            addView(icon.apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            })
            setOnClickListener { onClick() }
        }

    private fun bottomPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(10), dp(20), dp(14))
        }

        waveform = LiveWaveformView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34),
            ).apply { setMargins(dp(8), 0, dp(8), dp(2)) }
        }
        panel.addView(waveform)

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        }
        statusDot = View(this).apply {
            background = roundedColor(GREEN, dpF(4f))
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                setMargins(0, 0, dp(8), 0)
            }
        }
        statusRow.addView(statusDot)
        statusView = TextView(this).apply {
            text = "Preparazione..."
            textSize = 14f
            setTextColor(Color.parseColor(GREEN))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        statusRow.addView(statusView)
        panel.addView(statusRow)

        downloadProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                dp(220),
                dp(4),
            ).apply { setMargins(0, 0, 0, dp(12)) }
        }
        panel.addView(downloadProgress)

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        keyboardButton = iconCircle(KeyboardIconView(this)) {
            if (!conversationActive) {
                sourceText.requestFocus()
                sourceText.setSelection(sourceText.text.length)
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(sourceText, InputMethodManager.SHOW_IMPLICIT)
            }
        }.apply {
            background = roundedColor(CARD, dpF(24f))
            elevation = dpF(2f)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                setMargins(0, 0, dp(22), 0)
            }
        }
        actionsRow.addView(keyboardButton)

        micButton = MicButtonView(this).apply {
            elevation = dpF(6f)
            layoutParams = LinearLayout.LayoutParams(dp(74), dp(74))
            setOnClickListener { onMicTap() }
        }
        actionsRow.addView(micButton)

        cameraButton = iconCircle(CameraIconView(this)) {
            capturePhoto()
        }.apply {
            background = roundedColor(CARD, dpF(24f))
            elevation = dpF(2f)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                setMargins(dp(22), 0, 0, 0)
            }
        }
        actionsRow.addView(cameraButton)

        panel.addView(actionsRow)

        return panel
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(sourceText.windowToken, 0)
        sourceText.clearFocus()
    }

    private fun translateTyped() {
        if (conversationActive) return
        val text = sourceText.text.toString().trim()
        if (text.isEmpty()) return
        hideKeyboard()
        detectDirection(text) { source, target -> runTypedTranslation(text, source, target) }
    }

    private fun runTypedTranslation(text: String, source: LanguageOption, target: LanguageOption) {
        if (source != selectedSource()) {
            selectedSourceIndex = indexOfLanguage(source).takeIf { it >= 0 } ?: selectedSourceIndex
            selectedTargetIndex = indexOfLanguage(target).takeIf { it >= 0 } ?: selectedTargetIndex
            updateLanguageUi()
        }
        translateDirect(text, source, target, addToHistory = true)
    }

    private fun translateDirect(
        text: String,
        source: LanguageOption,
        target: LanguageOption,
        addToHistory: Boolean,
    ) {
        val translator = translatorFor(source, target)
        if (!isTranslationReady(source, target)) {
            setStatus("Preparo traduzione ${source.name} -> ${target.name}...")
            ensureTranslationModelsForSelection {
                translateDirect(text, source, target, addToHistory)
            }
            return
        }

        setStatus("Traduco (${source.name} -> ${target.name})...")
        translator.translate(text)
            .addOnSuccessListener { translated ->
                lifecycleScope.launch(Dispatchers.Main) {
                    setCardText(targetText, translated)
                    if (addToHistory) history.add(Exchange(source, target, text, translated))
                    setStatus("Pronto")
                    playTranslationIfEnabled(translated, target)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Translation failed", e)
                setStatus("Errore traduzione: ${e.message}")
            }
    }

    private fun showHistory() {
        if (history.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Cronologia")
                .setMessage("Nessuno scambio ancora: avvia una conversazione col microfono.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val items = history.map {
            "${it.source.flag} ${it.original}\n${it.target.flag} ${it.translated}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Cronologia (tocca per copiare)")
            .setItems(items) { _, which -> copyText(history[which].translated) }
            .setPositiveButton("Chiudi", null)
            .setNegativeButton("Svuota") { _, _ -> history.clear() }
            .show()
    }

    // ------------------------------------------------------- language state

    private fun showLanguagePicker(isSource: Boolean) {
        if (conversationActive) return
        val languages = availableLanguages()
        val labels = languages.map { "${it.flag}  ${it.name}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (isSource) "Lingua di chi parla" else "Lingua della traduzione")
            .setItems(labels) { _, which ->
                if (isSource) {
                    selectedSourceIndex = which
                    if (selectedTargetIndex == which) {
                        selectedTargetIndex = nextLanguageIndex(which)
                    }
                } else {
                    selectedTargetIndex = which
                    if (selectedSourceIndex == which) {
                        selectedSourceIndex = nextLanguageIndex(which)
                    }
                }
                updateLanguageUi()
                ensureTranslationModelsForSelection {
                    retranslateCurrentText()
                }
                ensureVoicesForSelection()
            }
            .show()
    }

    /** Re-runs the translation of the text on screen after a language change. */
    private fun retranslateCurrentText() {
        if (conversationActive) return
        val text = sourceText.text.toString().trim()
        if (text.isBlank()) return
        translateDirect(text, selectedSource(), selectedTarget(), addToHistory = false)
    }

    private fun nextLanguageIndex(index: Int): Int =
        (index + 1).mod(availableLanguages().size)

    private fun swapLanguages() {
        if (conversationActive) return
        val source = selectedSourceIndex
        selectedSourceIndex = selectedTargetIndex
        selectedTargetIndex = source

        val src = sourceText.text.toString()
        val tgt = targetText.text.toString()
        if (src.isNotBlank() && tgt != TGT_PLACEHOLDER) {
            sourceText.setText(tgt)
            setCardText(targetText, src)
        }

        updateLanguageUi()
        ensureTranslationModelsForSelection()
        ensureVoicesForSelection()
        swapButton.animate().rotationBy(180f).setDuration(220).start()
    }

    private fun updateLanguageUi() {
        val source = selectedSource()
        val target = selectedTarget()
        sourceValue.text = source.name
        targetValue.text = target.name
        sourceFlag.setLanguage(source.ttsCode)
        targetFlag.setLanguage(target.ttsCode)
    }

    // ------------------------------------------------------------ drawables

    private fun roundedColor(color: String, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = radius
        }

    // -------------------------------------------------------------- models

    private fun ensureTranslationModelsForSelection(onReady: (() -> Unit)? = null) {
        val source = selectedSource()
        val target = selectedTarget()
        val required = listOf(
            TranslationKey(source.mlKitCode, target.mlKitCode),
            TranslationKey(target.mlKitCode, source.mlKitCode),
        ).filter { it.source != it.target }.distinct()

        if (required.all { it in readyTranslationKeys }) {
            translationModelsReady = true
            checkAllReady()
            onReady?.invoke()
            return
        }

        translationModelsReady = false
        updateButtonStates()
        setStatus("Scarico traduzione ${source.name} ↔ ${target.name}...")

        val conditions = DownloadConditions.Builder().build()
        var completed = 0
        var failed = false

        required.forEach { key ->
            val translator = translatorFor(key.source, key.target)
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    if (failed) return@addOnSuccessListener
                    readyTranslationKeys.add(key)
                    completed++
                    Log.i(TAG, "Translation model ready: ${key.source}->${key.target}")
                    if (completed >= required.size) {
                        translationModelsReady = areSelectedTranslationModelsReady()
                        checkAllReady()
                        onReady?.invoke()
                    }
                }
                .addOnFailureListener { e ->
                    failed = true
                    Log.e(TAG, "Translation model download failed", e)
                    setStatus("Errore download traduzione: ${e.message}")
                    updateButtonStates()
                }
        }
    }

    private fun loadSpeechPipeline() {
        setStatus("Scarico modelli vocali...")
        downloadProgress.visibility = View.VISIBLE
        downloadProgress.progress = 0

        WorkManager.getInstance(applicationContext)
            .cancelUniqueWork(ModelDownloadWorker.uniqueName(sttModel = STT_MODEL, ttsModel = TtsModel.SUPERTONIC))

        ModelDownloadWorker.enqueue(
            applicationContext,
            ModelPrecision.INT8,
            sttModel = STT_MODEL,
            ttsModel = PIPELINE_TTS_MODEL,
        )
        if (observingDownload) return
        observingDownload = true

        WorkManager.getInstance(applicationContext)
            .getWorkInfosForUniqueWorkLiveData(ModelDownloadWorker.uniqueName(sttModel = STT_MODEL, ttsModel = PIPELINE_TTS_MODEL))
            .observe(this) { infos ->
                val info = infos.firstOrNull { !it.state.isFinished }
                    ?: infos.lastOrNull() ?: return@observe
                when (info.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.BLOCKED,
                    WorkInfo.State.RUNNING -> {
                        val total = info.progress.getInt(ModelDownloadWorker.KEY_TOTAL, 0)
                        if (total > 0) {
                            val file = info.progress.getString(ModelDownloadWorker.KEY_FILE) ?: ""
                            val bytes = info.progress.getLong(ModelDownloadWorker.KEY_BYTES_DOWNLOADED, 0L)
                            val fileTotal = info.progress.getLong(ModelDownloadWorker.KEY_FILE_TOTAL_BYTES, 0L)
                            val done = info.progress.getInt(ModelDownloadWorker.KEY_COMPLETED, 0)
                            val mb = if (fileTotal > 0) {
                                "${bytes / 1_000_000}/${fileTotal / 1_000_000} MB"
                            } else {
                                "${bytes / 1_000_000} MB"
                            }
                            setStatus("${friendlyModelName(file)}  $mb  -  $done/$total")
                            downloadProgress.progress =
                                info.progress.getInt(ModelDownloadWorker.KEY_PERCENT, 0)
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val modelDir = info.outputData.getString(ModelDownloadWorker.KEY_MODEL_DIR)
                        if (modelDir == null) {
                            setStatus("Errore: nessuna directory modelli")
                            return@observe
                        }
                        speechModelsReady = true
                        downloadProgress.progress = 100
                        downloadProgress.visibility = View.GONE
                        if (!pipelineStarted) {
                            pipelineStarted = true
                            initPipeline(modelDir)
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        val err = info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "sconosciuto"
                        setStatus("Errore download: $err")
                    }
                    WorkInfo.State.CANCELLED -> {
                        setStatus("Download annullato")
                    }
                }
            }
    }

    private fun initPipeline(modelDir: String) {
        lifecycleScope.launch {
            try {
                setStatus("Preparo modello ascolto...")
                val config = SpeechConfig(
                    modelDir = modelDir,
                    pipelineMode = PipelineMode.TRANSCRIBE_ONLY,
                    useNnapi = false,
                    sttModel = STT_MODEL,
                    ttsModel = PIPELINE_TTS_MODEL,
                    precision = ModelPrecision.INT8,
                    emitPartialTranscriptions = false,
                    endOfSpeechSilenceSec = 0.9f,
                )

                val p = withContext(Dispatchers.IO) {
                    SpeechPipeline(config)
                }
                pipeline = p

                launch {
                    p.events.collect { event ->
                        when (event) {
                            is SpeechEvent.SpeechStarted -> setStatus("Sto ascoltando...")
                            is SpeechEvent.SpeechEnded -> setStatus("Sto trascrivendo...")
                            is SpeechEvent.TranscriptionCompleted -> handleTranscription(event.text)
                            is SpeechEvent.Error -> {
                                Log.e(TAG, "Pipeline error: ${event.message}")
                                setStatus("Errore: ${event.message}")
                                stopConversation()
                            }
                            is SpeechEvent.ResponseDone -> {
                                if (conversationActive && recording && !processing) p.resumeListening()
                            }
                            else -> Unit
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    checkAllReady()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Pipeline init failed", e)
                withContext(Dispatchers.Main) {
                    setStatus("Errore inizializzazione: ${e.message}")
                }
            }
        }
    }

    private fun checkAllReady() {
        translationModelsReady = areSelectedTranslationModelsReady()
        if (pipeline != null && translationModelsReady) {
            setStatus("Pronto")
            downloadProgress.visibility = View.GONE
        }
        updateButtonStates()
    }

    // ------------------------------------------------------------- session

    private fun onMicTap() {
        if (conversationActive) {
            stopConversation()
        } else {
            startConversation()
        }
    }

    private fun startConversation() {
        if (!ensureMicPermission()) return
        if (pipeline == null || !areSelectedTranslationModelsReady()) return

        conversationActive = true
        processing = false
        micMuted = false
        hideKeyboard()
        showPlaceholders()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        micButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        updateButtonStates()
        setStatus(listeningStatus())
        startSession()
    }

    private fun listeningStatus(): String =
        "In ascolto: parla in ${selectedSource().promptName} o in ${selectedTarget().promptName}"

    private fun stopConversation() {
        conversationActive = false
        processing = false
        micMuted = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        micButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        updateButtonStates()
        systemTts?.stop()
        piperTts?.stop()
        if (pipeline != null && areSelectedTranslationModelsReady()) setStatus("Pronto")
        lifecycleScope.launch(Dispatchers.IO) {
            stopMicrophone()
            runCatching { pipeline?.stop() }
        }
    }

    private fun startSession() {
        val p = pipeline ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                startMicrophone(p)
                p.start()
            } catch (e: Throwable) {
                Log.e(TAG, "Session start failed", e)
                stopMicrophone()
                withContext(Dispatchers.Main) {
                    conversationActive = false
                    processing = false
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    updateButtonStates()
                    setStatus("Errore avvio ascolto: ${e.message}")
                }
            }
        }
    }

    private fun startMicrophone(p: SpeechPipeline) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return

        val sampleRate = 16_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBuffer <= 0) {
            setStatus("Microfono non disponibile")
            return
        }

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            minBuffer,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            setStatus("Errore microfono")
            return
        }

        audioRecord = record
        recording = true
        record.startRecording()

        lifecycleScope.launch(Dispatchers.IO) {
            val buffer = FloatArray(512)
            while (recording) {
                val read = try {
                    audioRecord?.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) ?: 0
                } catch (_: IllegalStateException) {
                    break
                }
                if (read > 0) {
                    if (micMuted) {
                        // Keep draining the mic but ignore audio while the app speaks.
                        waveform.pushLevel(0f)
                    } else {
                        p.pushAudio(if (read == buffer.size) buffer else buffer.copyOf(read))
                        var sum = 0f
                        for (i in 0 until read) sum += buffer[i] * buffer[i]
                        val rms = sqrt(sum / read)
                        waveform.pushLevel(min(1f, rms * 9f))
                    }
                }
            }
        }
    }

    private fun stopMicrophone() {
        recording = false
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        waveform.post { waveform.setActive(false) }
    }

    private fun handleTranscription(rawText: String) {
        if (!conversationActive || processing) return
        val text = tidyTranscription(rawText)
        if (text.isBlank()) return
        processing = true
        micMuted = true

        detectDirection(text) { source, target -> runExchange(text, source, target) }
    }

    /**
     * Picks the translation direction by scoring the text against the two
     * selected languages only — robust on short sentences, where the generic
     * single-language detector often answers "und" and the direction would
     * otherwise stick to the previous speaker.
     */
    private fun detectDirection(
        text: String,
        onResult: (LanguageOption, LanguageOption) -> Unit,
    ) {
        val langA = selectedSource()
        val langB = selectedTarget()
        val identifier = languageIdentifier
        if (identifier == null) {
            onResult(langA, langB)
            return
        }
        identifier.identifyPossibleLanguages(text)
            .addOnSuccessListener { candidates ->
                var scoreA = 0f
                var scoreB = 0f
                candidates.forEach {
                    if (it.languageTag == langA.mlKitCode) scoreA = it.confidence
                    if (it.languageTag == langB.mlKitCode) scoreB = it.confidence
                }
                Log.i(TAG, "LangID ${langA.mlKitCode}=$scoreA ${langB.mlKitCode}=$scoreB for: $text")
                if (scoreB > scoreA) onResult(langB, langA) else onResult(langA, langB)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Language detection failed", e)
                onResult(langA, langB)
            }
    }

    /** One conversation turn: show original, translate, speak, then resume listening. */
    private fun runExchange(text: String, source: LanguageOption, target: LanguageOption) {
        lifecycleScope.launch(Dispatchers.Main) {
            if (!conversationActive) {
                processing = false
                return@launch
            }
            if (source != selectedSource()) {
                // Keep the speaker's language on the top card.
                selectedSourceIndex = indexOfLanguage(source).takeIf { it >= 0 } ?: selectedSourceIndex
                selectedTargetIndex = indexOfLanguage(target).takeIf { it >= 0 } ?: selectedTargetIndex
                updateLanguageUi()
            }
            sourceText.setText(text)
            setStatus("Traduco (${source.name} -> ${target.name})...")

            val translator = translatorFor(source, target)
            if (!isTranslationReady(source, target)) {
                setStatus("Preparo traduzione ${source.name} -> ${target.name}...")
                ensureTranslationModelsForSelection {
                    runExchange(text, source, target)
                }
                return@launch
            }
            translator.translate(text)
                .addOnSuccessListener { translated ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        setCardText(targetText, translated)
                        history.add(Exchange(source, target, text, translated))
                        micButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        if (!speakInConversation(translated, target)) finishExchange()
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Translation failed", e)
                    lifecycleScope.launch(Dispatchers.Main) {
                        setStatus("Errore traduzione: ${e.message}")
                        finishExchange()
                    }
                }
        }
    }

    /** Speaks the translation; returns false when playback could not start. */
    private fun speakInConversation(text: String, language: LanguageOption): Boolean {
        if (!ttsEnabled) return false
        stopSpeech()

        val piper = piperTts
        if (usePiper && piper != null && piper.isVoiceReady(language.ttsCode)) {
            setStatus("Leggo la traduzione...")
            lifecycleScope.launch(Dispatchers.IO) {
                piper.speak(text, language.ttsCode)
                finishExchange()
            }
            return true
        }

        val tts = systemTts ?: return false
        if (!systemTtsReady) return false
        val availability = tts.setLanguage(localeFor(language))
        if (availability == TextToSpeech.LANG_MISSING_DATA ||
            availability == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Toast.makeText(
                this,
                "Voce ${language.name} non disponibile: traduzione solo a schermo.",
                Toast.LENGTH_SHORT,
            ).show()
            return false
        }
        setStatus("Leggo la traduzione...")
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "conv-${System.currentTimeMillis()}")
        return result == TextToSpeech.SUCCESS
    }

    /** Ends the current turn and re-arms listening. Safe to call from any thread. */
    private fun finishExchange() {
        lifecycleScope.launch(Dispatchers.Main) {
            if (!conversationActive) {
                processing = false
                return@launch
            }
            if (!continuousMode) {
                // Push-to-talk: one exchange per mic press.
                stopConversation()
                return@launch
            }
            // Let the speaker's echo die out before re-opening the mic.
            delay(300)
            processing = false
            micMuted = false
            runCatching { pipeline?.resumeListening() }
            if (conversationActive) {
                setStatus(listeningStatus())
            }
        }
    }

    private fun tidyTranscription(text: String): String =
        text.trim().replaceFirstChar { it.uppercase() }

    private fun setCardText(view: TextView, text: String) {
        view.text = text
        view.setTextColor(Color.parseColor(TEXT_PRIMARY))
    }

    private fun showPlaceholders() {
        sourceText.setText("")
        targetText.text = TGT_PLACEHOLDER
        targetText.setTextColor(Color.parseColor(TEXT_SECONDARY))
    }

    // ------------------------------------------------------------------ TTS

    private fun speakIfReal(text: String, placeholder: String, language: LanguageOption) {
        if (text.isBlank() || text == placeholder) return
        speak(text, language)
    }

    private fun stopSpeech(interruptedByUser: Boolean = false) {
        piperTts?.stop()
        systemTts?.stop()
        if (interruptedByUser) {
            micButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (conversationActive && processing) {
                finishExchange()
            } else if (!conversationActive) {
                setStatus("Audio interrotto")
            }
        }
    }

    private fun speak(text: String, language: LanguageOption) {
        stopSpeech()
        val piper = piperTts
        if (usePiper && piper != null && piper.isVoiceReady(language.ttsCode)) {
            lifecycleScope.launch(Dispatchers.IO) { piper.speak(text, language.ttsCode) }
            return
        }

        val tts = systemTts
        if (tts == null || !systemTtsReady) {
            Toast.makeText(this, "Motore voce Android non pronto.", Toast.LENGTH_SHORT).show()
            return
        }

        val locale = localeFor(language)
        val availability = tts.setLanguage(locale)
        if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) {
            Toast.makeText(
                this,
                "Voce ${language.name} non installata. Scaricala nelle impostazioni TTS Android.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "translation-${System.currentTimeMillis()}")
        if (result != TextToSpeech.SUCCESS) {
            setStatus("Voce non disponibile")
        }
    }

    private fun playTranslationIfEnabled(text: String, language: LanguageOption) {
        if (!ttsEnabled) return
        speak(text, language)
    }

    private fun localeFor(language: LanguageOption): Locale = Locale.forLanguageTag(language.ttsCode)

    // -------------------------------------------------------------- helpers

    private fun translatorFor(source: LanguageOption, target: LanguageOption): Translator =
        translatorFor(source.mlKitCode, target.mlKitCode)

    private fun translatorFor(sourceCode: String, targetCode: String): Translator {
        val key = TranslationKey(sourceCode, targetCode)
        translators[key]?.let { return it }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceCode)
            .setTargetLanguage(targetCode)
            .build()
        return Translation.getClient(options).also { translators[key] = it }
    }

    private fun isTranslationReady(source: LanguageOption, target: LanguageOption): Boolean =
        TranslationKey(source.mlKitCode, target.mlKitCode) in readyTranslationKeys

    private fun areSelectedTranslationModelsReady(): Boolean {
        val source = selectedSource()
        val target = selectedTarget()
        return isTranslationReady(source, target) && isTranslationReady(target, source)
    }

    private fun selectedSource(): LanguageOption {
        val languages = availableLanguages()
        return languages[selectedSourceIndex.coerceIn(languages.indices)]
    }

    private fun selectedTarget(): LanguageOption {
        val languages = availableLanguages()
        return languages[selectedTargetIndex.coerceIn(languages.indices)]
    }

    private fun availableLanguages(): List<LanguageOption> =
        if (extraLanguagesEnabled) CORE_LANGUAGES + EXTRA_LANGUAGES else CORE_LANGUAGES

    private fun indexOfLanguage(language: LanguageOption): Int =
        availableLanguages().indexOfFirst { it.mlKitCode == language.mlKitCode }

    private fun normalizeSelectedLanguageIndices() {
        val languages = availableLanguages()
        selectedSourceIndex = selectedSourceIndex.coerceIn(languages.indices)
        selectedTargetIndex = selectedTargetIndex.coerceIn(languages.indices)
        if (selectedSourceIndex == selectedTargetIndex) {
            selectedTargetIndex = nextLanguageIndex(selectedSourceIndex)
        }
    }

    private fun friendlyModelName(file: String): String = when {
        file.contains("parakeet", ignoreCase = true) -> "Scarico modello ascolto"
        file.contains("kokoro", ignoreCase = true) ||
            file.contains("voice", ignoreCase = true) ||
            file.contains("dict_", ignoreCase = true) -> "Scarico modello voce"
        file.contains("silero", ignoreCase = true) -> "Scarico rilevamento voce"
        file.contains("deepfilter", ignoreCase = true) -> "Scarico filtro audio"
        else -> "Scarico modelli offline"
    }

    private fun copyText(text: String) {
        if (text.isBlank()) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Traduzione", text))
        Toast.makeText(this, "Traduzione copiata", Toast.LENGTH_SHORT).show()
    }

    private fun setTtsEnabled(enabled: Boolean) {
        ttsEnabled = enabled
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_TTS_ENABLED, enabled)
            .apply()
        if (!enabled) stopSpeech(interruptedByUser = true)
        if (enabled) ensureVoicesForSelection()
    }

    private fun setTextScale(scale: Float) {
        textScale = scale.coerceIn(0.9f, 1.25f)
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putFloat(PREF_TEXT_SCALE, textScale)
            .apply()
        applyTextScale()
    }

    private fun applyTextScale() {
        if (::sourceText.isInitialized) sourceText.textSize = 23f * textScale
        if (::targetText.isInitialized) targetText.textSize = 23f * textScale
    }

    private fun updateButtonStates() {
        val ready = pipeline != null && areSelectedTranslationModelsReady()
        val active = conversationActive
        micButton.isEnabled = ready
        micButton.setReady(ready)
        micButton.setRecording(active)
        waveform.setActive(active)
        listOf(sourcePill, targetPill, swapButton, keyboardButton, cameraButton, clearButton).forEach {
            it.isEnabled = !active
            it.alpha = if (active) 0.45f else 1.0f
        }
    }

    private fun setStatus(text: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            statusView.text = text
            val color = when {
                text.contains("errore", ignoreCase = true) -> RED
                text.contains("scaric", ignoreCase = true) ||
                    text.contains("preparo", ignoreCase = true) ||
                    text.contains("preparazione", ignoreCase = true) ||
                    text.contains("traduc", ignoreCase = true) ||
                    text.contains("trascrivendo", ignoreCase = true) ||
                    text.contains("leggo", ignoreCase = true) -> AMBER
                text.contains("ascolt", ignoreCase = true) ||
                    text.contains("parla", ignoreCase = true) -> ACCENT
                else -> GREEN
            }
            statusView.setTextColor(Color.parseColor(color))
            statusDot.background = roundedColor(color, dpF(4f))
        }
    }

    private fun ensureMicPermission(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) return true
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            MIC_PERMISSION,
        )
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        results: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == MIC_PERMISSION && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            setStatus("Pronto")
        }
    }

    // --------------------------------------------------------- custom views

    /** Big round record button: mic glyph when idle, stop square + pulse when recording. */
    private class MicButtonView(context: android.content.Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private var recordingState = false
        private var readyState = false
        private var pulse = 0f
        private var pulseAnimator: ValueAnimator? = null

        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#FF5A4E")
        }
        private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeCap = Paint.Cap.ROUND
        }

        fun setRecording(value: Boolean) {
            if (recordingState == value) return
            recordingState = value
            if (value) startPulse() else stopPulse()
            invalidate()
        }

        fun setReady(value: Boolean) {
            readyState = value
            alpha = if (value) 1f else 0.5f
            invalidate()
        }

        private fun startPulse() {
            pulseAnimator?.cancel()
            pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1400
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    pulse = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun stopPulse() {
            pulseAnimator?.cancel()
            pulseAnimator = null
            pulse = 0f
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            stopPulse()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) / 2f - 7f * density

            if (recordingState && pulse > 0f) {
                val pulseRadius = radius + pulse * 7f * density
                pulsePaint.strokeWidth = 2.5f * density
                pulsePaint.alpha = ((1f - pulse) * 170).toInt()
                canvas.drawCircle(cx, cy, pulseRadius, pulsePaint)
            }

            if (recordingState) {
                circlePaint.shader = LinearGradient(
                    cx - radius, cy - radius, cx + radius, cy + radius,
                    Color.parseColor("#FF6A5B"),
                    Color.parseColor("#E8443A"),
                    Shader.TileMode.CLAMP,
                )
            } else {
                circlePaint.shader = null
                circlePaint.color = Color.parseColor(if (readyState) "#15181C" else "#B9BfC9")
            }
            canvas.drawCircle(cx, cy, radius, circlePaint)

            if (recordingState) {
                val side = radius * 0.62f
                glyphPaint.style = Paint.Style.FILL
                canvas.drawRoundRect(
                    RectF(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f),
                    4f * density, 4f * density, glyphPaint,
                )
            } else {
                drawMicGlyph(canvas, cx, cy, radius * 0.9f, glyphPaint)
            }
        }
    }

    /** Waveform driven by real mic RMS while recording; subtle idle bars otherwise. */
    private class LiveWaveformView(context: android.content.Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val barCount = 36
        private val levels = FloatArray(barCount) { 0.08f }
        private var active = false

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 3f * density
        }

        fun pushLevel(level: Float) {
            post {
                System.arraycopy(levels, 1, levels, 0, barCount - 1)
                levels[barCount - 1] = 0.08f + level * 0.92f
                invalidate()
            }
        }

        fun setActive(value: Boolean) {
            if (active == value) return
            active = value
            if (!value) {
                for (i in levels.indices) levels[i] = 0.08f
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val center = height / 2f
            val gap = width / (barCount + 1f)
            for (i in 0 until barCount) {
                val level = levels[i]
                val half = height * level * 0.46f
                paint.color = if (active && level > 0.12f) {
                    Color.parseColor("#FF6A5B")
                } else {
                    Color.parseColor("#C2C8D2")
                }
                val x = gap * (i + 1)
                canvas.drawLine(x, center - half, x, center + half, paint)
            }
        }
    }

    /** Round flag, clipped to a circle. */
    private class FlagView(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D6DAE1")
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private var language = "en"

        fun setLanguage(code: String) {
            language = code
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
            val save = canvas.save()
            val path = Path().apply {
                addRoundRect(rect, height / 2f, height / 2f, Path.Direction.CW)
            }
            canvas.clipPath(path)
            when (language) {
                "it" -> drawItaly(canvas)
                "de" -> drawGermany(canvas)
                "en" -> drawUk(canvas)
                else -> drawLanguageBadge(canvas)
            }
            canvas.restoreToCount(save)
            canvas.drawRoundRect(rect, height / 2f, height / 2f, stroke)
        }

        private fun drawItaly(canvas: Canvas) {
            val third = width / 3f
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#11975D")
            canvas.drawRect(0f, 0f, third, height.toFloat(), paint)
            paint.color = Color.WHITE
            canvas.drawRect(third, 0f, third * 2f, height.toFloat(), paint)
            paint.color = Color.parseColor("#E33B44")
            canvas.drawRect(third * 2f, 0f, width.toFloat(), height.toFloat(), paint)
        }

        private fun drawGermany(canvas: Canvas) {
            val third = height / 3f
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#101010")
            canvas.drawRect(0f, 0f, width.toFloat(), third, paint)
            paint.color = Color.parseColor("#DD1F2D")
            canvas.drawRect(0f, third, width.toFloat(), third * 2f, paint)
            paint.color = Color.parseColor("#F7C843")
            canvas.drawRect(0f, third * 2f, width.toFloat(), height.toFloat(), paint)
        }

        private fun drawUk(canvas: Canvas) {
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#183A7A")
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

            paint.strokeCap = Paint.Cap.SQUARE
            paint.style = Paint.Style.STROKE
            paint.color = Color.WHITE
            paint.strokeWidth = height * 0.22f
            canvas.drawLine(0f, 0f, width.toFloat(), height.toFloat(), paint)
            canvas.drawLine(width.toFloat(), 0f, 0f, height.toFloat(), paint)

            paint.color = Color.parseColor("#D6243B")
            paint.strokeWidth = height * 0.11f
            canvas.drawLine(0f, 0f, width.toFloat(), height.toFloat(), paint)
            canvas.drawLine(width.toFloat(), 0f, 0f, height.toFloat(), paint)

            paint.color = Color.WHITE
            paint.strokeWidth = height * 0.34f
            canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), paint)
            canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, paint)

            paint.color = Color.parseColor("#D6243B")
            paint.strokeWidth = height * 0.18f
            canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), paint)
            canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, paint)
        }

        private fun drawLanguageBadge(canvas: Canvas) {
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#15181C")
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

            labelPaint.textSize = height * 0.42f
            val label = language.uppercase(Locale.US).take(2)
            val y = height / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(label, width / 2f, y, labelPaint)
        }
    }

    /** Classic gear icon for the settings button. */
    private class TuneButtonView(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3C434D")
            style = Paint.Style.FILL
        }
        private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FBFCFD")
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val toothW = width * 0.115f
            val toothOuter = width * 0.335f
            repeat(8) { index ->
                canvas.save()
                canvas.rotate(index * 45f, cx, cy)
                canvas.drawRoundRect(
                    RectF(cx - toothW / 2f, cy - toothOuter, cx + toothW / 2f, cy),
                    toothW * 0.4f, toothW * 0.4f, paint,
                )
                canvas.restore()
            }
            canvas.drawCircle(cx, cy, width * 0.245f, paint)
            canvas.drawCircle(cx, cy, width * 0.115f, holePaint)
        }
    }

    /** Speaker (audio) icon. */
    private class SpeakerIconView(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3C434D")
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            paint.style = Paint.Style.FILL
            val body = Path().apply {
                moveTo(w * 0.28f, h * 0.42f)
                lineTo(w * 0.40f, h * 0.42f)
                lineTo(w * 0.52f, h * 0.30f)
                lineTo(w * 0.52f, h * 0.70f)
                lineTo(w * 0.40f, h * 0.58f)
                lineTo(w * 0.28f, h * 0.58f)
                close()
            }
            canvas.drawPath(body, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = w * 0.055f
            val arc1 = RectF(w * 0.44f, h * 0.36f, w * 0.72f, h * 0.64f)
            canvas.drawArc(arc1, -55f, 110f, false, paint)
            val arc2 = RectF(w * 0.40f, h * 0.24f, w * 0.88f, h * 0.76f)
            canvas.drawArc(arc2, -55f, 110f, false, paint)
        }
    }

    /** Stop playback icon. */
    private class StopIconView(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3C434D")
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val side = w * 0.34f
            val left = (w - side) / 2f
            val top = (h - side) / 2f
            val r = w * 0.055f
            canvas.drawRoundRect(RectF(left, top, left + side, top + side), r, r, paint)
        }
    }

    /** History icon: clock face. */
    private class HistoryIconView(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3C434D")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val r = w * 0.22f
            paint.strokeWidth = w * 0.05f
            canvas.drawCircle(cx, cy, r, paint)
            paint.strokeWidth = w * 0.045f
            canvas.drawLine(cx, cy - r * 0.55f, cx, cy, paint)
            canvas.drawLine(cx, cy, cx + r * 0.45f, cy + r * 0.3f, paint)
        }
    }

    /** Keyboard icon: rounded frame + key dots + space bar. */
    private class KeyboardIconView(context: android.content.Context) : View(context) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3C434D")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3C434D")
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            stroke.strokeWidth = w * 0.05f
            val r = w * 0.06f
            canvas.drawRoundRect(RectF(w * 0.22f, h * 0.32f, w * 0.78f, h * 0.68f), r, r, stroke)

            val dotR = w * 0.028f
            val rowTop = h * 0.425f
            val rowMid = h * 0.535f
            floatArrayOf(0.34f, 0.45f, 0.56f, 0.67f).forEach { x ->
                canvas.drawCircle(w * x, rowTop, dotR, fill)
            }
            floatArrayOf(0.34f, 0.67f).forEach { x ->
                canvas.drawCircle(w * x, rowMid, dotR, fill)
            }
            stroke.strokeWidth = w * 0.045f
            canvas.drawLine(w * 0.42f, h * 0.60f, w * 0.58f, h * 0.60f, stroke)
        }
    }

    /** Camera icon: body + top bump + lens. */
    private class CameraIconView(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3C434D")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            paint.strokeWidth = w * 0.05f
            val r = w * 0.06f
            canvas.drawRoundRect(RectF(w * 0.24f, h * 0.36f, w * 0.76f, h * 0.70f), r, r, paint)
            canvas.drawLine(w * 0.40f, h * 0.36f, w * 0.44f, h * 0.28f, paint)
            canvas.drawLine(w * 0.44f, h * 0.28f, w * 0.56f, h * 0.28f, paint)
            canvas.drawLine(w * 0.56f, h * 0.28f, w * 0.60f, h * 0.36f, paint)
            canvas.drawCircle(w * 0.5f, h * 0.53f, w * 0.085f, paint)
        }
    }

    /** X (clear) icon. */
    private class ClearIconView(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3C434D")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            paint.strokeWidth = w * 0.055f
            canvas.drawLine(w * 0.36f, h * 0.36f, w * 0.64f, h * 0.64f, paint)
            canvas.drawLine(w * 0.64f, h * 0.36f, w * 0.36f, h * 0.64f, paint)
        }
    }

    /** Copy icon: two overlapping rounded squares. */
    private class CopyIconView(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3C434D")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            paint.strokeWidth = w * 0.055f
            val r = w * 0.07f
            canvas.drawRoundRect(RectF(w * 0.36f, h * 0.28f, w * 0.72f, h * 0.64f), r, r, paint)
            canvas.drawRoundRect(RectF(w * 0.28f, h * 0.36f, w * 0.64f, h * 0.72f), r, r, paint)
        }
    }
}

/** Shared microphone glyph: capsule + U-holder + stem + base. */
private fun drawMicGlyph(canvas: Canvas, cx: Float, cy: Float, s: Float, paint: Paint) {
    val capW = s * 0.34f
    val capTop = cy - s * 0.56f
    val capBottom = cy + s * 0.04f
    paint.style = Paint.Style.FILL
    canvas.drawRoundRect(
        RectF(cx - capW / 2f, capTop, cx + capW / 2f, capBottom),
        capW / 2f, capW / 2f, paint,
    )

    paint.style = Paint.Style.STROKE
    paint.strokeWidth = s * 0.11f
    val arcR = s * 0.36f
    val arcCy = cy - s * 0.04f
    canvas.drawArc(
        RectF(cx - arcR, arcCy - arcR, cx + arcR, arcCy + arcR),
        0f, 180f, false, paint,
    )
    canvas.drawLine(cx, arcCy + arcR, cx, cy + s * 0.52f, paint)
    canvas.drawLine(cx - s * 0.19f, cy + s * 0.52f, cx + s * 0.19f, cy + s * 0.52f, paint)
}
