package com.github.jvsena42.loopky.domain.model

/**
 * What a pubky looks like as text.
 *
 * Lives in the domain rather than beside the link parser because two unrelated things turn on the
 * exact shape — which screen a pasted string opens, and whether an announcement can mention its
 * author — and one definition of the alphabet is one place to be wrong about it.
 */
object Pubky {

    /** z-base-32, the alphabet a pubky is encoded in. */
    private const val Z_BASE_32 = "ybndrfg8ejkmcpqxot1uwisza345h769"

    /** A 32-byte key in z-base-32. */
    const val LENGTH = 52

    /** True when [candidate] is a whole key. Exact: a bare token has no scheme vouching for it. */
    fun isKey(candidate: String): Boolean =
        candidate.length == LENGTH && candidate.all { it in Z_BASE_32 }

    /**
     * True when [candidate] could be the *beginning* of a key — what search has to work with when
     * someone was handed part of one. Deliberately loose where [isKey] is exact: it only rules out
     * text that could not be a key at all, so a name in the search box costs no prefix lookup.
     * [minLength] is the caller's floor; the indexer has one of its own.
     */
    fun isKeyPrefix(candidate: String, minLength: Int): Boolean =
        candidate.length in minLength..LENGTH && candidate.all { it in Z_BASE_32 }
}
