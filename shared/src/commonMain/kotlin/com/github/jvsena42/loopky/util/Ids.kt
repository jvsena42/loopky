package com.github.jvsena42.loopky.util

/**
 * A client-side id for a deck or a card.
 *
 * 12 characters from a 36-symbol alphabet is ~62 bits of entropy — collisions are negligible, which
 * matters more than it used to: ids are no longer scoped to one account. Following someone else's
 * deck puts two authors' ids in the same caches, and cloning mints a fresh id for every copied card
 * precisely so review state cannot bleed between an original and its copy.
 *
 * Public rather than `internal` because `:cli` mints deck and card ids too (#54), and a second
 * scheme in a second module is exactly the kind of drift that produces decks one client can open
 * and the other cannot.
 */
fun generateId(): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    return (1..ID_LENGTH).map { chars.random() }.joinToString("")
}

private const val ID_LENGTH = 12
