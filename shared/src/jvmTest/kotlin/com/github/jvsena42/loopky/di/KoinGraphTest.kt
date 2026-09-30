package com.github.jvsena42.loopky.di

import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.DiscoveryRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.data.repository.KeyBackupRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.data.repository.SettingsRepository
import com.github.jvsena42.loopky.data.repository.SignupRepository
import com.github.jvsena42.loopky.data.repository.SrsRepository
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.platform.PassThroughMediaProcessor
import org.koin.core.context.stopKoin
import org.koin.mp.KoinPlatform
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The DI graph resolves.
 *
 * Nothing else in the suite constructs it: every other test builds its subject directly or over a
 * fake, so a binding Koin resolves differently after an upgrade first shows up when an app
 * launches. That is a real gap rather than a theoretical one — the graph is assembled from
 * `sharedModule` plus a per-platform module, and CI launches no activity.
 *
 * The desktop target is the one place this is cheap to check: `initKoinJvm` takes the same
 * `sharedModule` the apps use, so a break in the shared half is caught here even though the
 * platform half is the JVM's.
 */
class KoinGraphTest {

    @AfterTest
    fun tearDown() = stopKoin()

    @Test
    fun `every repository resolves`() {
        startGraph()
        val koin = KoinPlatform.getKoin()
        // Named individually rather than reflected over, so adding a repository without a binding
        // fails here rather than passing an empty loop.
        koin.get<IdentityRepository>()
        koin.get<KeyBackupRepository>()
        koin.get<DeckRepository>()
        koin.get<CardRepository>()
        koin.get<SignupRepository>()
        koin.get<ImportRepository>()
        koin.get<TagRepository>()
        koin.get<DiscoveryRepository>()
        koin.get<SrsRepository>()
        koin.get<MediaRepository>()
        koin.get<SettingsRepository>()
    }

    private fun startGraph() {
        initKoinJvm(
            pubkyEnvironment = PubkyEnvironment.Staging,
            mediaProcessor = PassThroughMediaProcessor(),
            // A temp config home, so the test never reads or writes a developer's real session.
            configHome = createTempDirectory("loopky-koin-test"),
        )
    }
}
