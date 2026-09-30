package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.domain.model.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import platform.Foundation.NSUserDefaults

/** iOS [AppPreferences] over `NSUserDefaults`, namespaced by [PREFERENCES_NAME]. */
class IosAppPreferences : AppPreferences {
    private val defaults = NSUserDefaults(suiteName = PREFERENCES_NAME)

    // `boolForKey` cannot distinguish "absent" from "false", and the default here is *true* —
    // so absence is probed with objectForKey first, or a fresh install would start opted out.
    private val _shareOnPubky = MutableStateFlow(
        if (defaults.objectForKey(KEY_SHARE_ON_PUBKY) == null) {
            DEFAULT_SHARE_ON_PUBKY
        } else {
            defaults.boolForKey(KEY_SHARE_ON_PUBKY)
        },
    )
    override val shareOnPubky: Flow<Boolean> = _shareOnPubky.asStateFlow()

    override suspend fun setShareOnPubky(enabled: Boolean) {
        defaults.setBool(enabled, KEY_SHARE_ON_PUBKY)
        _shareOnPubky.update { enabled }
    }

    // No absence probe needed here: the default is the empty string, which is also what
    // `stringForKey` yields for a key that was never written.
    private val _pubkyEnvironment = MutableStateFlow(
        defaults.stringForKey(KEY_PUBKY_ENVIRONMENT) ?: DEFAULT_PUBKY_ENVIRONMENT,
    )
    override val pubkyEnvironment: Flow<String> = _pubkyEnvironment.asStateFlow()

    override suspend fun setPubkyEnvironment(name: String) {
        defaults.setObject(name, KEY_PUBKY_ENVIRONMENT)
        _pubkyEnvironment.update { name }
    }

    // Empty default again, so `stringForKey`'s null-for-absent needs no probe.
    private val _cachedStudySettings = MutableStateFlow(
        defaults.stringForKey(KEY_CACHED_STUDY_SETTINGS) ?: DEFAULT_CACHED_STUDY_SETTINGS,
    )
    override val cachedStudySettings: Flow<String> = _cachedStudySettings.asStateFlow()

    override suspend fun setCachedStudySettings(json: String) {
        defaults.setObject(json, KEY_CACHED_STUDY_SETTINGS)
        _cachedStudySettings.update { json }
    }

    private val _themeMode = MutableStateFlow(
        defaults.stringForKey(KEY_THEME_MODE)?.let(AppTheme::fromNameOrSystem) ?: DEFAULT_THEME_MODE,
    )
    override val themeMode: StateFlow<AppTheme> = _themeMode.asStateFlow()

    override suspend fun setThemeMode(theme: AppTheme) {
        defaults.setObject(theme.name, KEY_THEME_MODE)
        _themeMode.update { theme }
    }

    // The default is false, which is also what `boolForKey` answers for a key never written — so
    // absence needs no probe here, unlike [shareOnPubky].
    private val _nameNudgeDismissed = MutableStateFlow(defaults.boolForKey(KEY_NAME_NUDGE_DISMISSED))
    override val nameNudgeDismissed: Flow<Boolean> = _nameNudgeDismissed.asStateFlow()

    override suspend fun setNameNudgeDismissed(dismissed: Boolean) {
        defaults.setBool(dismissed, KEY_NAME_NUDGE_DISMISSED)
        _nameNudgeDismissed.update { dismissed }
    }

    // Same as [nameNudgeDismissed]: the default is false, which is what `boolForKey` already
    // answers for a key never written.
    private val _avatarNudgeDismissed =
        MutableStateFlow(defaults.boolForKey(KEY_AVATAR_NUDGE_DISMISSED))
    override val avatarNudgeDismissed: Flow<Boolean> = _avatarNudgeDismissed.asStateFlow()

    override suspend fun setAvatarNudgeDismissed(dismissed: Boolean) {
        defaults.setBool(dismissed, KEY_AVATAR_NUDGE_DISMISSED)
        _avatarNudgeDismissed.update { dismissed }
    }
}
