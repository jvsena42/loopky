package com.github.jvsena42.loopky.locale

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Loopky's language, held by the device rather than by Loopky.
 *
 * On API 33+ this *is* the framework's per-app language preference — the same value the system's
 * own Settings → Apps → Loopky → Language screen writes. A change made in either place is visible
 * in the other, it survives a reinstall-from-backup of the setting, and the framework recreates
 * the activity itself. Nothing is stored by the app on that path, deliberately: a second copy is
 * a second thing to be stale, and it is the copy the system Settings screen would not update.
 *
 * Below 33 there is no such store. The tag is kept in [PREFS] and applied by [wrap] from
 * `MainActivity.attachBaseContext`, which is the whole of the backport — no AppCompat, no
 * `AppCompatActivity`, and no theme change to a `Theme.AppCompat` descendant.
 *
 * `null` means "follow the device" and is the default. It is never spelled `"en"`: a user whose
 * device is Portuguese and who has expressed no preference must keep getting Portuguese when a
 * later release adds a third language.
 */
object AppLocale {

    /**
     * The BCP-47 tags Loopky ships translations for, in the order the picker lists them.
     *
     * Must stay in step with the `res/values-…` directories and with `res/xml/locales_config.xml`,
     * which is what the system Settings screen reads. Nothing checks the three against each other.
     */
    val SUPPORTED = listOf("en", "pt-BR")

    private const val PREFS = "loopky.locale"
    private const val KEY_TAG = "app_locale_tag"

    /** The chosen tag, or `null` when Loopky is following the device language. */
    fun current(context: Context): String? {
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)
                ?.applicationLocales
                ?.takeIf { !it.isEmpty }
                ?.get(0)
                ?.toLanguageTag()
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null)
        }
        return tag?.let(::match)
    }

    /**
     * Records [tag] as the app language, or clears it back to the device language when `null`.
     *
     * On API 33+ the framework applies it and recreates the activity; below that the caller has
     * to recreate, because nothing else will.
     */
    fun set(context: Context, tag: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                LocaleList.forLanguageTags(tag.orEmpty())
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .apply { if (tag == null) remove(KEY_TAG) else putString(KEY_TAG, tag) }
                .apply()
        }
    }

    /**
     * Applies the stored language to [base], for `Activity.attachBaseContext` on API < 33.
     *
     * A no-op on 33+, where the framework has already resolved the configuration by the time an
     * activity is attached — overriding it here would fight the system Settings screen.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = current(base) ?: return base
        val locale = Locale.forLanguageTag(tag)
        // Also the default for java.text and anything else that formats without being handed a
        // locale, so a date under a Portuguese app does not come back in the device's language.
        Locale.setDefault(locale)
        val config = android.content.res.Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(config)
    }

    /**
     * Whether the language survives a reinstall and shows up in the device's own settings, which
     * is only true where the framework owns it.
     */
    val isDeviceManaged: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * The entry in [SUPPORTED] that [tag] selects, or `null`.
     *
     * Matched on the language subtag as well as the whole tag, because the system hands back what
     * it resolved rather than what was asked for — a device set to `pt-PT` or a bare `pt` both
     * have to land on the one Portuguese translation Loopky ships, not on nothing.
     */
    private fun match(tag: String): String? {
        SUPPORTED.firstOrNull { it.equals(tag, ignoreCase = true) }?.let { return it }
        val language = Locale.forLanguageTag(tag).language
        return SUPPORTED.firstOrNull { Locale.forLanguageTag(it).language == language }
    }

    /** The language's own name for itself — "English", "Português (Brasil)". */
    fun displayName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }
}
