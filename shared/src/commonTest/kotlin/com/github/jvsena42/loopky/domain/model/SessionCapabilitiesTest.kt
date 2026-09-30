package com.github.jvsena42.loopky.domain.model

import com.github.jvsena42.loopky.testing.fakeSession
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a session is allowed to write, read off the capabilities the homeserver granted it.
 *
 * The question matters because the headless client asks for `/pub/loopky/:rw` and nothing else
 * (#54), so code shared with the two apps has to be able to tell "this write is not mine to make"
 * from "this write failed".
 */
class SessionCapabilitiesTest {

    @Test
    fun `the apps' session can write pubky app`() {
        assertTrue(fakeSession().canWritePubkyApp)
    }

    @Test
    fun `a loopky-only session cannot`() {
        val session = fakeSession().copy(capabilities = listOf(Capability("/pub/loopky/:rw")))
        assertFalse(session.canWritePubkyApp)
    }

    @Test
    fun `a session with no capabilities at all cannot`() {
        assertFalse(fakeSession().copy(capabilities = emptyList()).canWritePubkyApp)
    }

    /** A grant on `/pub/` covers everything below it; equality would read it as narrower. */
    @Test
    fun `a broader grant covers the namespace`() {
        val session = fakeSession().copy(capabilities = listOf(Capability("/pub/:rw")))
        assertTrue(session.canWritePubkyApp)
    }

    /** Read is not write. A `:r` on the same prefix grants nothing this question is asking about. */
    @Test
    fun `read-only on the same prefix is not write`() {
        val session = fakeSession().copy(capabilities = listOf(Capability("/pub/pubky.app/:r")))
        assertFalse(session.canWritePubkyApp)
    }

    @Test
    fun `a capability with no action letters grants nothing`() {
        assertFalse(Capability("/pub/pubky.app/").grantsWriteTo("/pub/pubky.app/"))
    }

    /** A sibling namespace is not a prefix of this one, however similar the spelling. */
    @Test
    fun `a neighbouring namespace does not grant it`() {
        assertFalse(Capability("/pub/pubky.apple/:rw").grantsWriteTo("/pub/pubky.app/"))
    }
}
