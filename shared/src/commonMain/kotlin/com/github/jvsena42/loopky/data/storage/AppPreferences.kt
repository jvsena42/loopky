package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.domain.model.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Device-local, non-secret user preferences. Deliberately separate from [SecureSessionStore]:
 * that one exists for the signed-in session and pays keystore/keychain costs for it, and a
 * boolean the user flipped in Settings has no business sharing that door.
 *
 * Backed by `SharedPreferences` on Android and `NSUserDefaults` on iOS — the platform's plain
 * settings store, which is what these are.
 *
 * Device-local by choice for v1. If a preference ever has to survive a reinstall or reach a
 * second device it belongs in a `/pub/loopky/settings.json` record instead, and this interface is
 * the seam that would move.
 */
interface AppPreferences {
    /**
     * Whether Loopky may **ask** to announce a deck on Pubky — see
     * [com.github.jvsena42.loopky.data.repository.DiscoveryRepository.announceDeck].
     *
     * Not a visibility control. Publishing a deck already writes it publicly to the homeserver;
     * there are no private decks (spec §11). This governs only whether a post goes out to the
     * user's followers saying the deck exists.
     *
     * On by default, and **off means off**: no prompt, and nothing written. There is no
     * post-silently mode.
     *
     * Emits the current value immediately and again on every change.
     */
    val shareOnPubky: Flow<Boolean>

    suspend fun setShareOnPubky(enabled: Boolean)

    /**
     * Which Pubky environment to talk to, as a [com.github.jvsena42.loopky.data.homegate.PubkyEnvironment]
     * name, or blank to use the build's own default.
     *
     * **Debug builds only.** A release ships pointed at production and offers no way to change it,
     * for the same reason the Nexus URL is not overridable in release (#42).
     *
     * Read once at Koin start-up, not collected: the Homegate client is a singleton that captures
     * its base URL when constructed, so a change here applies on the next launch. The Settings row
     * that writes it says so.
     *
     * Emits the current value immediately and again on every change.
     */
    val pubkyEnvironment: Flow<String>

    suspend fun setPubkyEnvironment(name: String)

    /**
     * The last study settings successfully read from or written to the homeserver, as the JSON of
     * the record's `study` section — or blank if none has been.
     *
     * A **cache, not the setting**. The real record lives at `/pub/loopky/settings.json` (see
     * `SettingsRepository`), because scheduling preferences produce review state that already
     * syncs. This copy exists so a session that starts offline still schedules with the user's own
     * intervals instead of silently reverting to the built-in defaults — and it is never allowed
     * to authorize a *write*, which would let one bad read overwrite the real record.
     *
     * Emits the current value immediately and again on every change.
     */
    val cachedStudySettings: Flow<String>

    suspend fun setCachedStudySettings(json: String)

    /**
     * Light, dark, or whatever the device is doing — see [AppTheme].
     *
     * A `StateFlow` rather than a `Flow`, alone among these, because the app's root reads it to
     * decide the palette it paints the very first frame in. A `Flow` collected into Compose or
     * SwiftUI needs an initial value to hand back before the first emission, and that value is a
     * guess: on a light device with Dark chosen it is one frame of white.
     */
    val themeMode: StateFlow<AppTheme>

    suspend fun setThemeMode(theme: AppTheme)

    /**
     * Whether the reader has waved away the "add a name" prompt on their own profile.
     *
     * Device-local rather than a profile field, because it records what this person did with a
     * prompt, not who they are — and the prompt has to stay dismissed for someone who signs in on
     * a phone with no intention of naming themselves. Publishing a name retires it on its own; the
     * flag is only for the reader who never will.
     *
     * Emits the current value immediately and again on every change.
     */
    val nameNudgeDismissed: Flow<Boolean>

    suspend fun setNameNudgeDismissed(dismissed: Boolean)

    /**
     * Whether the reader has waved away the "add a photo" prompt on their own profile.
     *
     * Device-local for the same reason as [nameNudgeDismissed], and separate from it because the
     * two prompts are refused for different reasons: a photo cannot be set in Loopky at all, so
     * "not now" here often means "not until I am at a browser".
     *
     * Emits the current value immediately and again on every change.
     */
    val avatarNudgeDismissed: Flow<Boolean>

    suspend fun setAvatarNudgeDismissed(dismissed: Boolean)
}

internal const val PREFERENCES_NAME = "loopky.preferences"
internal const val KEY_SHARE_ON_PUBKY = "share_on_pubky"
internal const val DEFAULT_SHARE_ON_PUBKY = true
internal const val KEY_PUBKY_ENVIRONMENT = "pubky_environment"
internal const val DEFAULT_PUBKY_ENVIRONMENT = ""
internal const val KEY_CACHED_STUDY_SETTINGS = "cached_study_settings"
internal const val DEFAULT_CACHED_STUDY_SETTINGS = ""
internal const val KEY_THEME_MODE = "theme_mode"
internal val DEFAULT_THEME_MODE = AppTheme.System
internal const val KEY_NAME_NUDGE_DISMISSED = "name_nudge_dismissed"
internal const val DEFAULT_NAME_NUDGE_DISMISSED = false
internal const val KEY_AVATAR_NUDGE_DISMISSED = "avatar_nudge_dismissed"
internal const val DEFAULT_AVATAR_NUDGE_DISMISSED = false
