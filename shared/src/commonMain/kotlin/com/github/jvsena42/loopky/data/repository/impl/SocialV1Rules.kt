package com.github.jvsena42.loopky.data.repository.impl

// What the pubky-social-specs 1.0.0-rc.2 reader refuses, for the records Loopky writes (#616).

/** JavaScript's `Number.MAX_SAFE_INTEGER`: the spec refuses any integer past it. */
private const val LABEL_MAX = 20
private const val PUBKY_LENGTH = 52
private const val Z_BASE32 = "ybndrfg8ejkmcpqxot1uwisza345h769"
private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

/** A profile name and bio as both the 0.x and v1 specs bound them, counted after the frozen trim. */
internal fun isProfileName(name: String) = codePoints(frozenTrim(name)) in PROFILE_NAME_LENGTH
internal fun isProfileBio(bio: String) = codePoints(frozenTrim(bio)) <= PROFILE_BIO_MAX
internal fun isProfileNameTooLong(name: String) = codePoints(frozenTrim(name)) > PROFILE_NAME_LENGTH.last

private val PROFILE_NAME_LENGTH = 3..50
private const val PROFILE_BIO_MAX = 160

internal fun isSafeInteger(value: Long) = value in 0..MAX_SAFE_INTEGER

/**
 * The part of the spec's canonical-reference rule a Loopky record could break: no whitespace or
 * control character anywhere, and in a `pubky://` path no empty segment and no `%`, `?` or `#`.
 */
internal fun isPlainReference(uri: String): Boolean {
    if (uri.any { it in FROZEN_WHITESPACE || it.isISOControl() }) return false
    if (!uri.startsWith("pubky://")) return true
    val segments = uri.removePrefix("pubky://").split('/')
    return segments.all { it.isNotEmpty() && it.none { c -> c == '%' || c == '?' || c == '#' } }
}
internal const val MICROS_PER_MILLI = 1_000L

internal fun codePoints(text: String): Int = text.count { !it.isLowSurrogate() }

/** `PubkyId`: 52 z-base32 characters, the last carrying only `y` or `o` for its filler bits. */
internal fun isPubky(key: String) =
    key.length == PUBKY_LENGTH && key.all { it in Z_BASE32 } && key.last() in PUBKY_LAST

/** `validate_tag_label`: 1–20 code points, no whitespace, comma or colon. */
internal fun isLabel(label: String) =
    codePoints(label) in 1..LABEL_MAX && label.none { it in FROZEN_WHITESPACE || it == ',' || it == ':' }

/**
 * The spec's 25 `White_Space` code points at Unicode 15.1, frozen because ids depend on the trim;
 * Kotlin's own `trim` and `isWhitespace` disagree with it on U+0085 and U+001C–U+001F.
 */
private val FROZEN_WHITESPACE = setOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u0020', '\u0085', '\u00A0',
    '\u1680', '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005', '\u2006',
    '\u2007', '\u2008', '\u2009', '\u200A', '\u2028', '\u2029', '\u202F', '\u205F',
    '\u3000',
)
private val PUBKY_LAST = setOf('y', 'o')

internal fun frozenTrim(text: String): String = text.trim { it in FROZEN_WHITESPACE }
