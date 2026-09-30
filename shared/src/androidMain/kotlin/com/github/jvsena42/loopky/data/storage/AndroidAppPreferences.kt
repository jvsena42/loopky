package com.github.jvsena42.loopky.data.storage

import android.content.Context
import android.content.SharedPreferences
import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.domain.model.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Android [AppPreferences] over plain `SharedPreferences` — no encryption, none needed. */
class AndroidAppPreferences(context: Context) : AppPreferences {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    // Mirrored into a StateFlow rather than read through callbackFlow: the file is read once at
    // construction and only this class writes it, so the flow is the cheaper source of truth and
    // a collector gets the current value without touching disk.
    private val _shareOnPubky = MutableStateFlow(
        prefs.getBoolean(KEY_SHARE_ON_PUBKY, DEFAULT_SHARE_ON_PUBKY),
    )
    override val shareOnPubky: Flow<Boolean> = _shareOnPubky.asStateFlow()

    override suspend fun setShareOnPubky(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            prefs.edit().putBoolean(KEY_SHARE_ON_PUBKY, enabled).apply()
        }
        _shareOnPubky.update { enabled }
    }

    private val _pubkyEnvironment = MutableStateFlow(
        prefs.getString(KEY_PUBKY_ENVIRONMENT, DEFAULT_PUBKY_ENVIRONMENT).orEmpty(),
    )
    override val pubkyEnvironment: Flow<String> = _pubkyEnvironment.asStateFlow()

    override suspend fun setPubkyEnvironment(name: String) {
        withContext(Dispatchers.IO) {
            prefs.edit().putString(KEY_PUBKY_ENVIRONMENT, name).apply()
        }
        _pubkyEnvironment.update { name }
    }

    private val _cachedStudySettings = MutableStateFlow(
        prefs.getString(KEY_CACHED_STUDY_SETTINGS, DEFAULT_CACHED_STUDY_SETTINGS).orEmpty(),
    )
    override val cachedStudySettings: Flow<String> = _cachedStudySettings.asStateFlow()

    override suspend fun setCachedStudySettings(json: String) {
        withContext(Dispatchers.IO) {
            prefs.edit().putString(KEY_CACHED_STUDY_SETTINGS, json).apply()
        }
        _cachedStudySettings.update { json }
    }

    private val _themeMode = MutableStateFlow(
        prefs.getString(KEY_THEME_MODE, null)?.let(AppTheme::fromNameOrSystem) ?: DEFAULT_THEME_MODE,
    )
    override val themeMode: StateFlow<AppTheme> = _themeMode.asStateFlow()

    override suspend fun setThemeMode(theme: AppTheme) {
        // Published before the write, unlike the rest: the caller repaints the whole app off this
        // flow, and a repaint that waits on the IO dispatcher is a visible lag on the tap.
        _themeMode.update { theme }
        withContext(Dispatchers.IO) {
            prefs.edit().putString(KEY_THEME_MODE, theme.name).apply()
        }
    }

    private val _nameNudgeDismissed = MutableStateFlow(
        prefs.getBoolean(KEY_NAME_NUDGE_DISMISSED, DEFAULT_NAME_NUDGE_DISMISSED),
    )
    override val nameNudgeDismissed: Flow<Boolean> = _nameNudgeDismissed.asStateFlow()

    override suspend fun setNameNudgeDismissed(dismissed: Boolean) {
        withContext(Dispatchers.IO) {
            prefs.edit().putBoolean(KEY_NAME_NUDGE_DISMISSED, dismissed).apply()
        }
        _nameNudgeDismissed.update { dismissed }
    }

    private val _avatarNudgeDismissed = MutableStateFlow(
        prefs.getBoolean(KEY_AVATAR_NUDGE_DISMISSED, DEFAULT_AVATAR_NUDGE_DISMISSED),
    )
    override val avatarNudgeDismissed: Flow<Boolean> = _avatarNudgeDismissed.asStateFlow()

    override suspend fun setAvatarNudgeDismissed(dismissed: Boolean) {
        withContext(Dispatchers.IO) {
            prefs.edit().putBoolean(KEY_AVATAR_NUDGE_DISMISSED, dismissed).apply()
        }
        _avatarNudgeDismissed.update { dismissed }
    }
}

/**
 * The environment to start Koin with: the build's own, unless a **debug** build has one stored
 * from Settings.
 *
 * Lives here rather than in the app module because it reads the preference file directly — Koin
 * is not started yet, since this value is one of its inputs, so [AppPreferences] is not available.
 *
 * [allowStoredOverride] must be `BuildConfig.DEBUG`. A release ignores the stored value entirely:
 * a shipped build must not be talkable into minting signup tokens on staging, which the production
 * homeserver would then reject — and a signup token is single-use, so that rejection is final.
 */
fun resolveStartupEnvironment(
    context: Context,
    buildDefault: PubkyEnvironment,
    allowStoredOverride: Boolean,
): PubkyEnvironment {
    if (!allowStoredOverride) return buildDefault
    val stored = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        .getString(KEY_PUBKY_ENVIRONMENT, null)
        ?.takeIf { it.isNotBlank() }
        ?: return buildDefault
    return PubkyEnvironment.fromNameOrProduction(stored)
}
