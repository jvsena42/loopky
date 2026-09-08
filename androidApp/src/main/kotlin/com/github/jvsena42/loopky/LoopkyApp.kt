package com.github.jvsena42.loopky

import android.app.Application
import coil3.SingletonImageLoader
import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.data.storage.resolveStartupEnvironment
import com.github.jvsena42.loopky.data.unsplash.deobfuscateUnsplashKey
import com.github.jvsena42.loopky.di.initKoinAndroid
import com.github.jvsena42.loopky.ui.components.loopkyImageLoader
import com.github.jvsena42.loopky.ui.importflow.sweepImportSpools
import com.github.jvsena42.loopky.util.Log
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.logger.Level
import uniffi.pubkycore.RustlsInit
import uniffi.pubkycore.initLogging

class LoopkyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Must precede any Pubky network call: without it the first TLS handshake panics on a
        // background task and sign-in fails with an unexplained "auth request expired".
        RustlsInit.init(this)
        // Before anything can spool a file of its own: whatever is there belongs to a process
        // that is gone, and a 500 MB deck is not something to leave in someone's cache.
        cacheDir.sweepImportSpools()
        // Every card picture set from an address goes through this loader, and the only thing
        // it changes is the User-Agent — hosts that refuse a generic `okhttp/4.x` (Wikimedia
        // answers 403) otherwise render as a blank half-card with nothing reporting why.
        SingletonImageLoader.setSafe(::loopkyImageLoader)
        // The one place that knows whether this is a debug build: `commonMain` cannot read
        // BuildConfig and `:shared` generates none. Log.d is a no-op until this runs, which is
        // the safe direction to fail in.
        Log.debugEnabled = BuildConfig.DEBUG
        if (BuildConfig.DEBUG) {
            // Routes the SDK's tracing plus any Rust panic to logcat under the tag `pubkycore`.
            // Debug-only — it logs network activity.
            initLogging()
        }
        initKoinAndroid(
            // A fallback only: a key the user saves in Settings wins, and this one is never
            // shown to them. Ships scrambled so it is not a literal in the dex — a speed bump
            // against APK scanners, not protection. See UnsplashKeyObfuscation.
            unsplashFallbackKey = deobfuscateUnsplashKey(BuildConfig.UNSPLASH_ACCESS_KEY_OBF),
            // Which network this build talks to, whole: the Homegate that mints signup tokens,
            // the homeserver they are valid on, the web client, and the Nexus indexer. Staging on
            // debug, production on release — see composeApp/build.gradle.kts. A debug build can
            // override it from Settings; a release cannot (#42).
            pubkyEnvironment = resolveStartupEnvironment(
                context = this,
                buildDefault = PubkyEnvironment.fromNameOrProduction(BuildConfig.PUBKY_ENV),
                allowStoredOverride = BuildConfig.DEBUG,
            ),
            // A locally-run Nexus, and nothing else (#58) — blank unless local.properties names
            // one. Guarded on DEBUG as well as pinned blank in the release build type, for the
            // same reason the environment override is: a shipped build reads production (#205).
            localNexusBaseUrl = if (BuildConfig.DEBUG) BuildConfig.LOCAL_NEXUS_BASE_URL else "",
        ) {
            // Koin's own logger, gated for the same reason as Log.d: it narrates every definition
            // it resolves, which is noise a shipped build has no use for.
            androidLogger(if (BuildConfig.DEBUG) Level.INFO else Level.NONE)
            androidContext(this@LoopkyApp)
        }
    }
}
