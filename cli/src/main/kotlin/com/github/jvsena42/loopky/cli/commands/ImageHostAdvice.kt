package com.github.jvsena42.loopky.cli.commands

/**
 * A Wikimedia file outside Commons. `/wikipedia/en/` and the other per-language paths are where
 * a Wikipedia keeps files it may only use under fair use — film posters, logos, album covers —
 * and a public deck is not an encyclopedia article. The picture renders, which is why only this
 * says anything: `--check-images` answers 200 for it.
 */
internal fun nonFreeWikimediaAdvice(url: String): String? {
    val project = WIKIMEDIA_PROJECT.find(url)?.groupValues?.get(1) ?: return null
    if (project == COMMONS_PROJECT) return null
    return "$url\n" +
        "  This file is on the $project Wikipedia, not on Wikimedia Commons. Those paths hold files a " +
        "Wikipedia article may use under fair use and a public deck may not. Use a file under " +
        "/wikipedia/commons/, after reading its licence."
}

/**
 * A host that forbids embedding, or serves somebody else's picture with no licence to read.
 *
 * A short list of the ones agents reach for, each a suffix of the host: a search engine's
 * thumbnail cache, the social and pin boards, and the two stock sites whose terms rule it out.
 * Advice like the rest — the list is a snapshot of other people's terms.
 */
internal fun hotlinkAdvice(url: String): String? {
    val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
    val (_, why) = NO_HOTLINK_HOSTS.firstOrNull { (suffix, _) -> host == suffix || host.endsWith(".$suffix") }
        ?: return null
    return "$url\n  $why Use a picture from a source whose licence allows it — `loopky doctor` lists them."
}

private val WIKIMEDIA_PROJECT = Regex("""^https://(?:upload|thumb)\.wikimedia\.org/wikipedia/([a-z0-9-]+)/""")
private const val COMMONS_PROJECT = "commons"

private const val SEARCH_THUMBNAIL =
    "This is a search engine's thumbnail of someone else's picture, with no licence to read."

private val NO_HOTLINK_HOSTS = listOf(
    "pixabay.com" to "Pixabay's terms forbid hotlinking, and a phone fetches the picture from its host every time.",
    "pinimg.com" to "This is a Pinterest copy of someone else's picture, with no licence to read.",
    "pinterest.com" to "This is a Pinterest page or copy of someone else's picture, with no licence to read.",
    "cdninstagram.com" to "This is an Instagram picture: its address expires, and it has no licence to read.",
    "instagram.com" to "This is an Instagram picture: its address expires, and it has no licence to read.",
    "fbcdn.net" to "This is a Facebook picture: its address expires, and it has no licence to read.",
    "encrypted-tbn0.gstatic.com" to SEARCH_THUMBNAIL,
    "encrypted-tbn1.gstatic.com" to SEARCH_THUMBNAIL,
    "encrypted-tbn2.gstatic.com" to SEARCH_THUMBNAIL,
    "encrypted-tbn3.gstatic.com" to SEARCH_THUMBNAIL,
    "plus.unsplash.com" to "Unsplash+ pictures are paid and licensed separately from the free Unsplash Licence.",
)
