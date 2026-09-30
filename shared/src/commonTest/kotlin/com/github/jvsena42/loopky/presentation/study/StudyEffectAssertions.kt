package com.github.jvsena42.loopky.presentation.study

/**
 * The effects a *screen* has to act on.
 *
 * Haptics ride the same flow and land beside almost every other effect, so an assertion about what
 * the platform was asked to do has to say it means that and not the buzz next to it.
 */
internal fun List<StudySessionEffect>.excludingHaptics(): List<StudySessionEffect> =
    filterNot { it is StudySessionEffect.Haptic }

internal fun List<StudySessionEffect>.haptics(): List<StudyHaptic> =
    filterIsInstance<StudySessionEffect.Haptic>().map { it.pattern }
