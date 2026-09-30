package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * What `card add` and `import --resume` both mean by "this card is already in the deck".
 *
 * The separator between the four fields is `NUL` rather than nothing, and this is the reason:
 * without it, two different cards whose fields happen to concatenate to the same string are one
 * card, and the second is silently dropped as a duplicate. No card text can contain a NUL, so the
 * boundary is unambiguous.
 */
class CardIdentityTest {

    private fun card(front: String, back: String) = Card(
        id = "c",
        deckId = "d",
        updatedAt = 0L,
        front = CardSide(text = front),
        back = CardSide(text = back),
    )

    @Test
    fun `two cards whose sides concatenate the same way are still two cards`() {
        assertNotEquals(card("ab", "c").identityOf(), card("a", "bc").identityOf())
    }

    /** Case and surrounding whitespace are not what makes a card different. */
    @Test
    fun `identity ignores case and padding`() {
        assertEquals(card("Brasília", "Capital").identityOf(), card("  brasília ", "CAPITAL").identityOf())
    }
}
