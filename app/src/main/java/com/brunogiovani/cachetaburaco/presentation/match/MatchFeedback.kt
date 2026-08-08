package com.brunogiovani.cachetaburaco.presentation.match

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.brunogiovani.cachetaburaco.R

enum class FeedbackCue {
    Select,
    Draw,
    Place,
    Error,
    Nudge,
    Turn,
    Victory,
    RoundEnd,
    OpponentMorto
}

class MatchFeedback(private val context: Context) {
    private val audioManager: AudioManager? = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val sounds = mapOf(
        FeedbackCue.Select to soundPool.load(context, R.raw.card_place, 1),
        FeedbackCue.Draw to soundPool.load(context, R.raw.card_draw, 1),
        FeedbackCue.Place to soundPool.load(context, R.raw.card_place, 1),
        FeedbackCue.Error to soundPool.load(context, R.raw.invalid_action, 1),
        FeedbackCue.Nudge to soundPool.load(context, R.raw.turn_nudge, 1),
        FeedbackCue.Turn to soundPool.load(context, R.raw.turn_start, 1),
        FeedbackCue.Victory to soundPool.load(context, R.raw.victory_chime, 1),
        FeedbackCue.RoundEnd to soundPool.load(context, R.raw.round_end, 1),
        FeedbackCue.OpponentMorto to soundPool.load(context, R.raw.turn_nudge, 1)
    )

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        }.getOrNull()
    } else {
        runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }.getOrNull()
    }

    fun play(cue: FeedbackCue) {
        val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

        if (shouldPlaySound(ringerMode)) {
            sounds[cue]?.let { soundId ->
                soundPool.play(soundId, 0.75f, 0.75f, 1, 0, 1f)
            }
        }

        if (shouldVibrate(ringerMode)) {
            val duration = when (cue) {
                FeedbackCue.Select -> 12L
                FeedbackCue.Draw -> 22L
                FeedbackCue.Place -> 32L
                FeedbackCue.Error -> 70L
                FeedbackCue.Nudge -> 55L
                FeedbackCue.Turn -> 35L
                FeedbackCue.Victory -> 120L
                FeedbackCue.RoundEnd -> 80L
                FeedbackCue.OpponentMorto -> 60L
            }
            val amplitude = when (cue) {
                FeedbackCue.Error -> 210
                FeedbackCue.Nudge -> 150
                FeedbackCue.Victory -> 190
                FeedbackCue.RoundEnd -> 170
                FeedbackCue.OpponentMorto -> 180
                else -> 95
            }

            vibrator?.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        }
    }

    fun release() {
        soundPool.release()
    }

    companion object {
        // Extraido do play() pra dar pra testar sem precisar de um AudioManager
        // real: silencioso corta som e vibracao; so-vibrar corta som mas mantem
        // a vibracao, que e uma preferencia explicita do usuario pra feedback tatil.
        internal fun shouldPlaySound(ringerMode: Int): Boolean {
            return ringerMode == AudioManager.RINGER_MODE_NORMAL
        }

        internal fun shouldVibrate(ringerMode: Int): Boolean {
            return ringerMode != AudioManager.RINGER_MODE_SILENT
        }
    }
}

@Composable
fun rememberMatchFeedback(): MatchFeedback {
    val context = LocalContext.current.applicationContext
    val feedback = remember(context) { MatchFeedback(context) }
    DisposableEffect(feedback) {
        onDispose { feedback.release() }
    }
    return feedback
}
