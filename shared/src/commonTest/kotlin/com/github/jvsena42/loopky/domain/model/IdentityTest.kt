package com.github.jvsena42.loopky.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdentityTest {

    private fun identity(
        pubky: String = "rk3xq9abc",
        displayName: String? = null,
        avatarUrl: String? = null,
    ) = PubkyIdentity(
        pubky = pubky,
        displayName = displayName,
        avatarUrl = avatarUrl,
        bio = null,
    )

    private val indexer = "https://nexus.staging.pubky.app"

    private fun avatar(avatarUrl: String?) =
        identity(avatarUrl = avatarUrl).avatarDisplayUrl(indexer)

    @Test
    fun aSocialV1AvatarIsServedFromTheV1StaticRoute() {
        assertEquals(
            "$indexer/v1/static/files/bzbjrj9a8/8Z8CWH8NVYQY39ZEBFGKQWWEKG.png/main",
            avatar("pubky://bzbjrj9a8/pub/social/v1/files/8Z8CWH8NVYQY39ZEBFGKQWWEKG.png"),
        )
        assertNull(avatar("pubky://bzbjrj9a8/pub/social/v1/files/a/b.png"))
    }

    @Test
    fun displayNameWinsOverPubky() {
        assertEquals('C', identity(displayName = "Cosmic-Crystal-Panda").avatarInitial)
    }

    @Test
    fun fallsBackToPubkyWithoutADisplayName() {
        assertEquals('R', identity().avatarInitial)
    }

    @Test
    fun blankDisplayNameFallsBackToPubky() {
        assertEquals('R', identity(displayName = "   ").avatarInitial)
    }

    @Test
    fun leadingSpaceInDisplayNameIsIgnored() {
        assertEquals('A', identity(displayName = "  Ada").avatarInitial)
    }

    @Test
    fun emptyIdentityShowsAPlaceholder() {
        assertEquals('?', identity(pubky = "").avatarInitial)
    }

    @Test
    fun resolvesAPubkyAppFileUriToTheIndexerBlob() {
        // The real shape a pubky.app profile stores — a file *record*, not an image.
        assertEquals(
            "$indexer/static/files/bzbjrj9a8/0035JHD6154X0/main",
            avatar("pubky://bzbjrj9a8/pub/pubky.app/files/0035JHD6154X0"),
        )
    }

    @Test
    fun passesAPlainWebUrlThrough() {
        assertEquals("https://example.com/a.jpg", avatar("https://example.com/a.jpg"))
        assertEquals("http://example.com/a.jpg", avatar("http://example.com/a.jpg"))
    }

    @Test
    fun noAvatarResolvesToNothing() {
        assertNull(avatar(null))
        assertNull(avatar(""))
        assertNull(avatar("   "))
    }

    @Test
    fun anUnrecognisedUriFallsBackToTheInitialRatherThanABlankSlot() {
        // A pubky:// path that is not a file record has no blob to serve, and handing it to the
        // image loader is what produced the silent blank in the first place.
        assertNull(avatar("pubky://bzbjrj9a8/pub/pubky.app/profile.json"))
        assertNull(avatar("pubky://"))
        assertNull(avatar("ftp://example.com/a.jpg"))
    }

    @Test
    fun aNestedFilePathIsNotAFileId() {
        assertNull(avatar("pubky://bzbjrj9a8/pub/pubky.app/files/0035JHD/extra"))
    }

    @Test
    fun aTrailingSlashOnTheIndexerDoesNotDoubleUp() {
        assertEquals(
            "$indexer/static/files/bzbjrj9a8/0035JHD6154X0/main",
            identity(avatarUrl = "pubky://bzbjrj9a8/pub/pubky.app/files/0035JHD6154X0")
                .avatarDisplayUrl("$indexer/"),
        )
    }
}
