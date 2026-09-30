package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.FakeCardRepository
import com.github.jvsena42.loopky.cli.FakeDeckRepository
import com.github.jvsena42.loopky.cli.FakeMediaRepository
import com.github.jvsena42.loopky.cli.testDeck
import com.github.jvsena42.loopky.data.repository.impl.ImportRepositoryImpl
import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Session
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The half of `.apkg` import that spends someone's quota.
 *
 * Every other import path attaches a picture as a **remote ref** — a URL, no bytes on the wire
 * (#167). This one writes blobs to the homeserver, against a 1 GB allowance with no endpoint that
 * reports what is left and a 507 that is terminal (§8.5). So the three things that decide how much
 * it spends, and what happens when it fails part-way, are pinned here rather than discovered on a
 * real account.
 */
class ApkgUploadTest {

    @Test
    fun `a picture on the card back is uploaded and referenced by its digest`() {
        val blob = ByteArray(64) { it.toByte() }
        val apkg = ApkgFixture()
            .fields("Word", "Picture")
            .media("dog.jpg", blob)
            .note("perro", ApkgFixture.image("dog.jpg"))
            .write()
        val media = FakeMediaRepository()
        val decks = FakeDeckRepository(testDeck())

        runImport(apkg, decks, media)

        assertEquals(1, media.puts.size)
        val card = decks.published.single()
        assertEquals("perro", card.front.text)
        assertEquals(media.puts.single().digest(), card.back.imageRef?.sha256)
    }

    /**
     * One blob, one upload. `MediaIndex` already hands back a single [com.github.jvsena42.loopky
     * .domain.model.DraftCardImage] per distinct `src`, and the memo in `buildCards` is what turns
     * that into one write — a shared picture across forty cards is one deck's worth of quota, not
     * forty.
     */
    @Test
    fun `a picture shared by several notes uploads once`() {
        val blob = ByteArray(128) { it.toByte() }
        val apkg = ApkgFixture()
            .fields("Word", "Picture")
            .media("flag.png", blob)
            .note("Brasil", ApkgFixture.image("flag.png"))
            .note("Brazil", ApkgFixture.image("flag.png"))
            .note("Brésil", ApkgFixture.image("flag.png"))
            .write()
        val media = FakeMediaRepository()
        val decks = FakeDeckRepository(testDeck())

        runImport(apkg, decks, media)

        assertEquals(3, decks.published.size)
        assertEquals(1, media.puts.size)
        assertEquals(1, decks.published.map { it.back.imageRef?.sha256 }.distinct().size)
    }

    /**
     * A failed publish takes its blobs back out. Media goes up **before** the manifest exists, so
     * without this an aborted import leaves orphans under a `deckId` nothing will ever reference —
     * and quota is precisely what an abort here is likely to be about.
     */
    @Test
    fun `an aborted publish of a new deck sweeps the blobs it uploaded`() {
        val apkg = ApkgFixture()
            .fields("Word", "Picture")
            .media("dog.jpg", ByteArray(32) { it.toByte() })
            .note("perro", ApkgFixture.image("dog.jpg"))
            .write()
        val media = FakeMediaRepository()
        val decks = FakeDeckRepository(
            testDeck(),
            onPublish = { _, _ -> Result.failure(IllegalStateException("507 Insufficient Storage")) },
        )

        runCatching { runImport(apkg, decks, media) }

        assertEquals(1, media.puts.size)
        assertEquals(1, media.deleted.size)
        assertEquals(media.puts.single().digest(), media.deleted.single())
    }

    /**
     * A failed **append** does not.
     *
     * Blobs are content-addressed per deck, so the shas this run wrote are the shas the deck's
     * *already published* cards point at. Sweeping there would strip the pictures off cards that
     * were never in trouble — the one case where tidying up is destructive.
     */
    @Test
    fun `an aborted resume leaves the deck's existing blobs alone`() {
        val apkg = ApkgFixture()
            .fields("Word", "Picture")
            .media("dog.jpg", ByteArray(32) { it.toByte() })
            .note("perro", ApkgFixture.image("dog.jpg"))
            .write()
        val media = FakeMediaRepository()
        val existing = testDeck(id = "d1").copy(title = TITLE)
        val decks = FakeDeckRepository(
            existing,
            owned = listOf(existing),
            onAppend = { Result.failure(IllegalStateException("507 Insufficient Storage")) },
        )

        val error = runCatching { runImport(apkg, decks, media, "--resume") }.exceptionOrNull()

        // The append is the failure, which is the point: the blob went up first, and it must
        // still be there afterwards.
        assertTrue(error != null, "expected the append to fail")
        assertEquals(1, media.puts.size)
        assertEquals(emptyList(), media.deleted)
    }

    /**
     * A refused upload fails the import rather than publishing a deck quietly missing its
     * pictures. A deck missing media the file held is a failed import (#91), and here it would be
     * one `--json` reported as a success.
     */
    @Test
    fun `a refused upload aborts rather than dropping the picture`() {
        val apkg = ApkgFixture()
            .fields("Word", "Picture")
            .media("dog.jpg", ByteArray(32) { it.toByte() })
            .note("perro", ApkgFixture.image("dog.jpg"))
            .write()
        val decks = FakeDeckRepository(testDeck())

        val error = runCatching {
            runImport(apkg, decks, FakeMediaRepository(failAfter = 0))
        }.exceptionOrNull()

        assertTrue(error is CliError, "expected a CliError, got $error")
        assertEquals(emptyList<Card>(), decks.published)
    }

    /** A text import touches the blob store not at all — its pictures are URLs. */
    @Test
    fun `a tsv with image columns uploads nothing`() {
        val tsv = File.createTempFile("loopky-test-cards", ".tsv").apply {
            deleteOnExit()
            writeText("hola\thello\thttps://example.test/a.jpg\t\n")
        }
        val media = FakeMediaRepository()
        val decks = FakeDeckRepository(testDeck())

        runImport(tsv, decks, media)

        assertEquals(emptyList(), media.puts)
        assertEquals("https://example.test/a.jpg", decks.published.single().front.imageRef?.url)
    }

    private fun runImport(
        file: File,
        decks: FakeDeckRepository,
        media: FakeMediaRepository,
        vararg extra: String,
    ) = runBlocking {
        import(
            args = Args.parse(arrayOf("import", file.path, "--title", TITLE, *extra)),
            imports = ImportRepositoryImpl(),
            decks = decks,
            cards = FakeCardRepository(),
            media = media,
            session = testSession(),
            onProgress = {},
            onNote = {},
        )
    }

    private companion object {
        const val TITLE = "Test import"
    }
}

/** The fake's content address, mirrored so a test can say which blob it expected. */
private fun ByteArray.digest(): String = fold(7) { acc, byte -> acc * 31 + byte }.toString()

private fun testSession(): Session = Session(
    identity = PubkyIdentity(pubky = "pk:test", displayName = null, avatarUrl = null, bio = null),
    sessionSecret = "secret",
    capabilities = listOf(Capability("/pub/loopky/:rw")),
    homeserver = "hs:test",
)
