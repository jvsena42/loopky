package com.github.jvsena42.loopky.data.pubky

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Paths pinned against `pubky-social-specs` 1.0.0-rc.2 (`src/uri/builders.rs`) and 0.x. */
class SocialLayoutTest {

    private val v0 = SocialLayout.Legacy
    private val v1 = SocialLayout.SocialV1

    @Test
    fun legacyPathsAreThePubkyAppOnes() {
        assertEquals("pubky://$PK/pub/pubky.app/profile.json", v0.profile(PK))
        assertEquals("pubky://$PK/pub/pubky.app/follows/$OTHER", v0.follow(PK, OTHER))
        assertEquals("pubky://$PK/pub/pubky.app/mutes/$OTHER", v0.mute(PK, OTHER))
        assertEquals("pubky://$PK/pub/pubky.app/posts/$POST", v0.post(PK, POST))
        assertEquals(v0.post(PK, POST), v0.postVersion(PK, POST))
        assertEquals("pubky://$PK/pub/pubky.app/tags/$TAG", v0.tag(PK, TAG))
    }

    @Test
    fun socialV1PathsFollowTheSpec() {
        assertEquals("pubky://$PK/pub/social/v1/profile.json", v1.profile(PK))
        assertEquals("pubky://$PK/pub/social/v1/follows/$OTHER.json", v1.follow(PK, OTHER))
        assertEquals("pubky://$PK/priv/social/v1/mutes/$OTHER.json", v1.mute(PK, OTHER))
        assertEquals("pubky://$PK/pub/social/v1/posts/$POST", v1.post(PK, POST))
        assertEquals("pubky://$PK/pub/social/v1/posts/$POST/$POST.json", v1.postVersion(PK, POST))
        assertEquals("pubky://$PK/pub/social/v1/tags/$TAG.json", v1.tag(PK, TAG))
    }

    @Test
    fun keysAreReadBackFromListedEntries() {
        assertEquals(OTHER, v0.followeeOf(v0.follow(PK, OTHER)))
        assertEquals(OTHER, v1.followeeOf(v1.follow(PK, OTHER)))
        assertEquals(OTHER, v1.muteeOf(v1.mute(PK, OTHER)))
        // Each layout reads only its own entries.
        assertNull(v1.followeeOf(v0.follow(PK, OTHER)))
        assertNull(v0.followeeOf(v1.follow(PK, OTHER)))
    }

    @Test
    fun aMissingEpochIsV0() {
        assertEquals(SocialEpoch.V0, SocialEpoch.fromWire(null))
        assertEquals(SocialEpoch.V1, SocialEpoch.fromWire("v1"))
        assertEquals(SocialEpoch.V0, SocialEpoch.fromWire("v9"))
    }

    private companion object {
        const val PK = "o1gg96ewuojmopcjbz8895478wdtxtzzuxnfjjz8o8e77csa1ngo"
        const val OTHER = "pxnu33x7jtpx9ar1ytsi4yxbp6a5o36gwhffs8zoxmbuptici1jy"
        const val POST = "0034A0X7NJ52G"
        const val TAG = "8Z8CWH8NVYQY39ZEBFGKQWWEKG"
    }
}
