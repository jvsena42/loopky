package com.github.jvsena42.loopky.presentation.discover

import com.github.jvsena42.loopky.domain.model.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The label entry point Swift uses, in isolation from the ViewModel's collaborators.
 *
 * A `value class` argument is erased across the ObjC bridge while the same type is boxed inside a
 * list, which is how a boxed `Tag` reached a parameter expecting an `NSString` and crashed
 * `sanitizeLabel` on a null `value`. These assert the only thing the fix has to guarantee: a
 * label rebuilds the same `Tag`, and null still means "clear the selection".
 */
class DiscoverTagLabelTest {

    private fun tagFor(label: String?): Tag? = label?.let(::Tag)

    @Test
    fun `a label rebuilds the tag it names`() {
        assertEquals(Tag("stem"), tagFor("stem"))
    }

    @Test
    fun `null clears the selection`() {
        assertNull(tagFor(null))
    }

    /** The topic row carries emoji tags, which are exactly the values a description parse mangles. */
    @Test
    fun `a non-ascii label survives`() {
        assertEquals(Tag("🇧🇷"), tagFor("🇧🇷"))
        assertEquals(Tag("português"), tagFor("português"))
    }

    /** An empty label is a Tag the repository will reject, not a crash and not a clear. */
    @Test
    fun `an empty label is still a tag`() {
        assertEquals(Tag(""), tagFor(""))
    }
}
