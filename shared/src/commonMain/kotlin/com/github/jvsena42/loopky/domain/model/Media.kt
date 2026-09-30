package com.github.jvsena42.loopky.domain.model

sealed class MediaRef {
    abstract val path: String
    abstract val mime: String
    abstract val sha256: String

    /**
     * Absolute `pubky://{author}/pub/loopky/decks/{deckId}/media/{sha}.{ext}` when the blob lives
     * under *another* deck — what makes cloning a media-heavy deck instant instead of re-uploading
     * hundreds of MB (#33 blocker 2). Null means the blob is under this deck's own path.
     *
     * Content addressing by sha256 is what makes this safe to resolve lazily: re-hosting the blob
     * later under the clone's own path yields the same digest, so the swap is invisible.
     */
    abstract val uri: String?

    data class Image(
        override val path: String,
        override val mime: String,
        override val sha256: String,
        val width: Int?,
        val height: Int?,
        override val uri: String? = null,
        /**
         * When set, this image is a remote web image referenced by URL (e.g. an Unsplash
         * photo) rather than a blob stored on the homeserver. For remote images [path] and
         * [sha256] are empty and no blob is uploaded.
         */
        val url: String? = null,
    ) : MediaRef() {
        val isRemote: Boolean get() = url != null
    }

    data class Audio(
        override val path: String,
        override val mime: String,
        override val sha256: String,
        val durationMs: Long?,
        override val uri: String? = null,
    ) : MediaRef()
}

/**
 * Whether [this] can be stored as a remote image ref — the one rule, in the one place every
 * client mints one through.
 *
 * **`https` only, and that is not a style preference.** Android blocks cleartext by default at
 * targetSdk 28 and up, and iOS App Transport Security does the same, so an `http://` ref is
 * unloadable on both clients *by construction*. Storing one writes a card whose picture can never
 * appear on any device Loopky ships for, and nothing downstream reports it: the write succeeds,
 * the read returns the URL intact, and the card simply renders with a blank half. Refusing here
 * is the only point in the system that can still tell the author.
 *
 * Not upgraded to `https` silently: a host that answers on one scheme and not the other would
 * turn an honest refusal into the same blank card, one layer further from the person who could
 * fix it. The author is told, and rewrites the address themselves.
 *
 * Scheme and shape only — this decides "could this ever load", never "does this resolve". Anything
 * stricter starts rejecting addresses that work.
 */
fun String.isRenderableImageUrl(): Boolean =
    startsWith(HTTPS_SCHEME, ignoreCase = true) &&
        length > HTTPS_SCHEME.length &&
        none { it.isWhitespace() }

/**
 * A remote image reference — a URL, with no blob and no upload — or `null` when [url] is not one
 * a client could render.
 *
 * The single constructor for the shape, because it used to be written out longhand in eight places
 * (`path`/`sha256` empty, `image/jpeg`) across the CLI, the shared ViewModels and Swift. A ref
 * built a second way is a ref one of the clients cannot render, and a validation added to one copy
 * is a validation the other seven do not have — which is exactly how `http://` got in.
 */
fun remoteImageRef(url: String): MediaRef.Image? = url
    .takeIf { it.isRenderableImageUrl() }
    ?.let { MediaRef.Image(path = "", mime = REMOTE_IMAGE_MIME, sha256 = "", width = null, height = null, url = it) }

private const val HTTPS_SCHEME = "https://"

/**
 * What a remote ref claims to be. A guess, and knowingly so: the bytes are never fetched, so the
 * real type is whatever the host serves. Both clients decode by content rather than by this field,
 * so a PNG behind a ref that says `image/jpeg` renders correctly.
 */
private const val REMOTE_IMAGE_MIME = "image/jpeg"
