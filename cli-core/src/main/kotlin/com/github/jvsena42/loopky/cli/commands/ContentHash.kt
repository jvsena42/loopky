package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.ImportDraft
import com.github.jvsena42.loopky.domain.model.frontBackOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.MessageDigest

/**
 * `--expect-hash`: a dry run's `content_hash`, handed back to the real call.
 *
 * The dry run and the real call are two requests carrying the same content, and nothing made them
 * carry the *same* content: an agent that fixed a card between the two, or rebuilt the payload,
 * published something nobody had pre-flighted (#559). With this the real call refuses, as a usage
 * error, anything whose hash differs — stateless, so the server keeps no plan between the two.
 */
const val EXPECT_HASH_OPTION = "expect-hash"

/** One card as the hash sees it: the two sides' text and picture address, in that order. */
internal class HashedCard(
    val frontText: String?,
    val frontImage: String?,
    val backText: String?,
    val backImage: String?,
)

internal fun Card.hashed(): HashedCard =
    HashedCard(front.text, front.imageRef?.url, back.text, back.imageRef?.url)

/**
 * A digest of what a write would put on the homeserver.
 *
 * Over the request's **content** — the deck metadata as it would be stored, tags folded, and every
 * card side in order — and never over what a run mints for itself: card ids, timestamps, and the
 * deck id when none was given. Nor over the dedupe outcome: which rows `card add` skips depends on
 * the deck, and a retry after a partial write has to match the dry run it came from. An `.apkg`'s
 * embedded pictures are not in it either, since a dry run never holds their bytes.
 */
internal fun contentHash(command: String, deckId: String?, metadata: JsonObject?, cards: List<HashedCard>): String {
    val content = buildJsonObject {
        put("command", command)
        put("deck_id", deckId)
        metadata?.let { put("deck", it) }
        put("cards", JsonArray(cards.map { it.toJson() }))
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(content.toString().toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

/** The deck fields `deck create` and `import` take from the command line, as they would be stored. */
internal fun Args.hashedDeckMetadata(): JsonObject = buildJsonObject {
    val frontLang = language("front-lang")
    val backLang = language("back-lang")
    put("title", option("title")?.trim()?.takeIf { it.isNotEmpty() })
    put("description", deckDescription())
    putJsonArray("tags") { deckTags(options("tag"), frontLang, backLang).forEach { add(it.value) } }
    put("cover_url", option("cover-url")?.trim()?.takeIf { it.isNotEmpty() })
    put("cover_emoji", option("cover-emoji")?.takeIf { it.isNotBlank() })
    put("front_lang", frontLang)
    put("back_lang", backLang)
    STUDY_MODES.forEach { put(it, flagOrNull(it)) }
}

/** [actual], or a refusal of the write when `--expect-hash` was given and [actual] is not it. */
internal fun Args.requireExpectedHash(actual: String): String {
    val expected = option(EXPECT_HASH_OPTION)?.trim() ?: return actual
    if (expected.isEmpty()) {
        throw CliError(ExitCode.Usage, "--$EXPECT_HASH_OPTION is empty. Pass the content_hash a --dry-run reported.")
    }
    if (!expected.equals(actual, ignoreCase = true)) {
        throw CliError(
            ExitCode.Usage,
            "This content is not what the dry run checked: --$EXPECT_HASH_OPTION is $expected, and this " +
                "call would write $actual. Nothing was written. Dry-run this content and read its advice again.",
        )
    }
    return actual
}

/** `card add`'s digest: over every row, before the dedupe, so a retry after a partial write still matches. */
internal fun List<CardFileRow>.contentHashOf(deckId: String, now: Long): String =
    contentHash(CARD_ADD, deckId, metadata = null, map { it.toCard(deckId, now, index = 0).hashed() })

private const val CARD_ADD = "card add"

/**
 * `import`'s digest, the same from the dry run and the real run: both read the kept rows of the
 * same parse, before `--resume` filters anything out.
 */
internal fun ImportRepository.contentHashOf(args: Args, draft: ImportDraft): String =
    contentHash(
        command = IMPORT,
        deckId = null,
        metadata = args.hashedDeckMetadata(),
        cards = keptRows().map { row ->
            val (front, back) = draft.frontBackOf(row)
            HashedCard(
                frontText = front.takeIf { it.isNotBlank() },
                frontImage = rowImage(row.index, isFront = true)?.url,
                backText = back.takeIf { it.isNotBlank() },
                backImage = rowImage(row.index, isFront = false)?.url,
            )
        },
    )

private const val IMPORT = "import"

private fun HashedCard.toJson() = buildJsonArray {
    listOf(frontText, frontImage, backText, backImage).forEach { add(JsonPrimitive(it)) }
}

private val STUDY_MODES = listOf("listen", "speak", "type", "reverse")
