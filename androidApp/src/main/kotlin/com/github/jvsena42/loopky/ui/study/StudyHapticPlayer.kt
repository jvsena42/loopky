package com.github.jvsena42.loopky.ui.study

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.github.jvsena42.loopky.presentation.study.StudyHaptic

/**
 * Plays the study loop's [StudyHaptic] vocabulary as patterns a thumb can actually tell apart.
 *
 * `HapticFeedbackConstants.CONFIRM`/`REJECT` are **prebaked**: the framework names an effect and
 * the vibrator HAL renders it, so a device whose actuator has no distinct rendering for one of them
 * falls back to the same generic buzz for both — right and wrong feel identical, on hardware where
 * `dumpsys vibrator_manager` on an emulator had reported two different prebaked effects. What
 * survives every actuator is **rhythm**, so the three verdicts are waveforms instead: a success
 * rises over two pulses, a warning is one blunt pulse, a failure stutters three times and fades.
 *
 * [StudyHaptic.Tick] stays on the framework constant. It is an acknowledgement rather than a
 * verdict, it fires on nearly every tap, and the platform's own tick is better tuned for that than
 * anything hand-rolled here.
 */
internal class StudyHapticPlayer(
    private val context: Context,
    /** For [StudyHaptic.Tick], and for a device with no actuator of its own to drive. */
    private val framework: HapticFeedback,
) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }

    fun play(pattern: StudyHaptic) {
        val waveform = pattern.waveform()
        val actuator = vibrator?.takeIf { it.hasVibrator() }
        if (waveform == null || actuator == null) {
            framework.performHapticFeedback(pattern.feedbackType())
            return
        }
        // The system mutes a USAGE_TOUCH vibration when haptic feedback is off, but that is the one
        // thing an OEM is likely to get wrong, and buzzing at a reader who turned haptics off is
        // worse than not buzzing at all. `Settings.System` reads are cached in-process.
        if (!hapticFeedbackEnabled()) return
        actuator.playAsTouch(waveform.effect(actuator))
    }

    private fun hapticFeedbackEnabled(): Boolean = Settings.System.getInt(
        context.contentResolver,
        Settings.System.HAPTIC_FEEDBACK_ENABLED,
        1,
    ) != 0
}

/**
 * Timings and per-pulse strengths of one pattern, in the `createWaveform` layout: entry 0 is a
 * delay, and they alternate off/on from there.
 */
private class Waveform(val timings: LongArray, val amplitudes: IntArray) {
    /** Rhythm alone on an actuator that cannot vary its strength — which is the point of it. */
    fun effect(vibrator: Vibrator): VibrationEffect =
        if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(timings, amplitudes, -1)
        } else {
            VibrationEffect.createWaveform(timings, -1)
        }
}

/** Light, then strong: two rising pulses. */
private val SUCCESS = Waveform(longArrayOf(0, 28, 72, 58), intArrayOf(0, 90, 0, 255))

/**
 * The day's goal. [SUCCESS]'s rise, drawn out over four pulses and landing on a long one — the
 * only pattern here allowed past a quarter of a second, because it fires once a day.
 */
private val CELEBRATION = Waveform(
    longArrayOf(0, 35, 45, 35, 45, 35, 55, 140),
    intArrayOf(0, 85, 0, 140, 0, 195, 0, 255),
)

/** One blunt pulse — no rise, no stutter. Long enough not to be read as a tick. */
private val WARNING = Waveform(longArrayOf(0, 95), intArrayOf(0, 165))

/** Three pulses, fading: the only pattern here that repeats. */
private val FAILURE = Waveform(longArrayOf(0, 55, 45, 55, 45, 60), intArrayOf(0, 255, 0, 190, 0, 125))

private fun StudyHaptic.waveform(): Waveform? = when (this) {
    StudyHaptic.Tick -> null
    StudyHaptic.Success -> SUCCESS
    StudyHaptic.Celebration -> CELEBRATION
    StudyHaptic.Warning -> WARNING
    StudyHaptic.Failure -> FAILURE
}

private fun Vibrator.playAsTouch(effect: VibrationEffect) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
    } else {
        @Suppress("DEPRECATION")
        vibrate(effect, TOUCH_AUDIO_ATTRIBUTES)
    }
}

private val TOUCH_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    .build()

/**
 * The fallback path, for [StudyHaptic.Tick] and for a device with no actuator: the shared
 * vocabulary in Android's own constants. Here — and only here — [StudyHaptic.Warning] and
 * [StudyHaptic.Failure] collapse, because the framework has one "that did not work" constant.
 */
internal fun StudyHaptic.feedbackType(): HapticFeedbackType = when (this) {
    StudyHaptic.Tick -> HapticFeedbackType.ContextClick
    StudyHaptic.Success, StudyHaptic.Celebration -> HapticFeedbackType.Confirm
    StudyHaptic.Warning, StudyHaptic.Failure -> HapticFeedbackType.Reject
}
