package com.github.jvsena42.loopky.ui.components

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.github.jvsena42.loopky.BuildConfig
import okhttp3.OkHttpClient

/**
 * The app's Coil loader, which exists for one reason: a **descriptive `User-Agent`**.
 *
 * A card picture set from an address (#167) is fetched from whatever host the author named, and
 * Coil's default request goes out as `okhttp/4.x`. Wikimedia — the single most likely source for
 * an agent writing a deck, and where `loopky card add --back-image` sends people — refuses a
 * generic library agent outright:
 *
 * ```
 * HTTP/2 403   server: HAProxy
 * Please set a user-agent and respect our robot policy https://w.wiki/4wJS.
 * ```
 *
 * There is nothing to see when that happens. `AsyncImage` has no error slot here, so the card
 * renders with a blank half where the picture is, the deck looks broken rather than unfetched,
 * and no log line says why. Worse, the *write* succeeded: `loopky` stores a URL and never fetches
 * it, so `--json` reports a card with an image and the agent that made it has no way to find out
 * otherwise.
 *
 * The header follows Wikimedia's policy — an identifying name, a version, and a link that reaches
 * a human — because that is the rule this fixes, and it is the same shape every other host that
 * blocks generic agents asks for. It is a *contact* string, not a disguise: impersonating a
 * browser would work today and is the thing the policy is written against.
 */
private const val USER_AGENT = "Loopky/${BuildConfig.VERSION_NAME} (+https://github.com/jvsena42/loopky)"

/**
 * Built once and shared, because an [OkHttpClient] owns a connection pool and a dispatcher: one
 * per image request would open a fresh pool per card.
 */
fun loopkyImageLoader(context: PlatformContext): ImageLoader {
    val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build(),
            )
        }
        .build()
    return ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
        .build()
}
