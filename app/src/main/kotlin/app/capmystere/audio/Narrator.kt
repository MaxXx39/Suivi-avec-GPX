package app.capmystere.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import app.capmystere.theme.ThemeSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class Sfx { CUE, ARRIVE, CLUE, FAIL }

/** Voix (synthèse vocale), sons du thème et retours haptiques. */
@Singleton
class Narrator @Inject constructor(@ApplicationContext private val context: Context) {
    var voiceEnabled = false
    var soundsEnabled = true
    var hapticsEnabled = true

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pitch = 1f
    private var rate = 1f

    private var pool: SoundPool? = null
    private var loadedTheme: String? = null
    private val soundIds = HashMap<Sfx, Int>()

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
    }

    /** Prépare la voix et charge les sons du thème (sans effet si déjà fait). */
    fun prepare(theme: ThemeSpec) {
        pitch = theme.voice.pitch
        rate = theme.voice.rate
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) tts?.language = Locale.getDefault().takeIf { it.language == "fr" } ?: Locale.FRENCH
            }
        }
        if (loadedTheme == theme.id) return
        pool?.release()
        soundIds.clear()
        val p = SoundPool.Builder().setMaxStreams(2)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build()
        for (sfx in Sfx.entries) {
            val path = theme.sounds[sfx.name.lowercase()] ?: continue
            runCatching { context.assets.openFd("themes/${theme.id}/$path").use { soundIds[sfx] = p.load(it, 1) } }
        }
        pool = p
        loadedTheme = theme.id
    }

    fun speak(text: String, interrupt: Boolean = true) {
        if (!voiceEnabled || !ttsReady || text.isBlank()) return
        tts?.apply {
            setPitch(pitch)
            setSpeechRate(rate)
            speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
        }
    }

    fun play(sfx: Sfx) {
        if (!soundsEnabled) return
        soundIds[sfx]?.let { pool?.play(it, 1f, 1f, 1, 0, 1f) }
    }

    /** Motifs haptiques : un tic pour une annonce, une signature plus riche pour les moments forts. */
    fun haptic(sfx: Sfx) {
        if (!hapticsEnabled) return
        val v = vibrator ?: return
        val (timings, amplitudes) = when (sfx) {
            Sfx.CUE -> longArrayOf(0, 35) to intArrayOf(0, 140)
            Sfx.ARRIVE -> longArrayOf(0, 60, 90, 60, 90, 180) to intArrayOf(0, 160, 0, 200, 0, 255)
            Sfx.CLUE -> longArrayOf(0, 30, 60, 30, 60, 30) to intArrayOf(0, 120, 0, 170, 0, 230)
            Sfx.FAIL -> longArrayOf(0, 160) to intArrayOf(0, 90)
        }
        runCatching { v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1)) }
    }

    fun stopSpeaking() { tts?.stop() }

    fun release() {
        tts?.shutdown(); tts = null; ttsReady = false
        pool?.release(); pool = null; loadedTheme = null
    }
}
