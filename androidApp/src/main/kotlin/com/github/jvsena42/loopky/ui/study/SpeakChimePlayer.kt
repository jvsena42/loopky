package com.github.jvsena42.loopky.ui.study

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import com.github.jvsena42.loopky.R

/**
 * The chime for a correct Speak attempt. A [SoundPool] rather than a `MediaPlayer` because the clip
 * is loaded once per study screen and has to start the instant the verdict lands.
 *
 * Sonification, and silent unless the ringer is on: it is feedback the reader did not ask for, so
 * it follows the phone's own mute like a keyboard click, unlike Listen, which plays because the
 * reader asked to hear the card.
 */
internal class SpeakChimePlayer(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val pool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val soundId = pool.load(context, R.raw.speak_correct, 1)

    fun play() {
        if (audioManager?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        pool.play(soundId, 1f, 1f, 1, 0, 1f)
    }

    fun release() = pool.release()
}
