import com.android.build.api.dsl.KotlinMultiplatformAndroidCompilation
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        namespace = "com.github.jvsena42.loopky.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        // Host tests are opt-in under this plugin, and forgetting the opt-in is silent: AGP only
        // warns when a `src/androidHostTest` directory exists, and there isn't one — `commonTest`
        // is what runs here. Without this the ~1,373-test Android run simply disappears.
        //
        // `isReturnDefaultValues` because commonTest exercises shared classes that log through
        // android.util.Log, which throws "not mocked" on a host JVM otherwise.
        withHostTest {
            isReturnDefaultValues = true
        }
    }

    /**
     * The desktop JVM the headless client runs on (#54) — Linux x86_64 first, since that is where
     * an agent actually runs, with macOS arm64 as the developer's machine.
     *
     * JVM 17 rather than the Android target's 11: nothing consumes this from a `minSdk` 29 app,
     * and 17 is what the root build already compiles detekt and every Gradle task against.
     */
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
        // SQLite for reading an Anki `.apkg`'s collection. zlib, which the same reader needs for
        // inflate, is already a Kotlin/Native platform library and needs no interop.
        iosTarget.compilations.getByName("main").cinterops.create("sqlite3")
    }
    
    /**
     * `jvmSharedMain`: the JVM family, Android and the desktop `jvm()` target together.
     *
     * A good deal of what used to sit in `androidMain` was only there by accident of being first —
     * `java.time`, `java.security`, `java.util.zip`, `HttpURLConnection`, and the UniFFI-generated
     * JNA bindings, which have no Android imports at all.
     *
     * Sharing them is not a tidiness preference. `uniffi/pubkycore/pubkycore.kt` is a *generated*
     * file, regenerated in `pubky-core-ffi-fork` and checked in here; a second copy under
     * `jvmMain` would have to stay byte-identical to this one forever, and nothing would report it
     * when it stopped. One copy, two targets.
     *
     * Added *through* the default template rather than with bare `dependsOn` edges, which silently
     * switch the template off — `iosMain` stops belonging to any compilation and the iOS build
     * loses every actual in it, with a warning as the only sign.
     */
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withCompilations { it is KotlinMultiplatformAndroidCompilation }
                withJvm()
            }
        }
    }

    sourceSets {
        val jvmSharedMain by getting
        jvmSharedMain.dependencies {
            // compileOnly, because the two targets need different *flavours* of the same artifact
            // at runtime: Android takes `jna@aar` (native .so bundled inside), the desktop target
            // takes the plain jar. An `implementation` here would put both on Android's runtime
            // classpath and duplicate every class in it.
            compileOnly(libs.jna)
        }

        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            api(libs.koin.core)
            implementation(libs.koin.core.viewmodel)
            // `api` so the ViewModel type stays visible to the platform UI layers (and the
            // exported iOS framework) that consume the shared ViewModels.
            api(libs.androidx.lifecycle.viewmodel)
        }
        iosMain.dependencies {
            // iOS's Keychain-backed stores. Down here for the same reason as androidMain's copy:
            // KVault publishes no `jvm` artifact, so a `commonMain` declaration breaks the
            // desktop target (#54, shared prerequisite 1).
            implementation(libs.kvault)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.koin.test)
        }
        jvmMain.dependencies {
            implementation(libs.jna)
            // The `.apkg` collection reader. Android has SQLite in the platform; a desktop JVM
            // does not, and bulk Anki import is the most CLI-shaped job there is (#46).
            implementation(libs.sqlite.jdbc)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.koin.android)
            // Android's `SecureSessionStore` and the secrets vault. Down here rather than in
            // `commonMain` because KVault 1.12.0 publishes android + iOS artifacts and **nothing
            // else** — no `jvm`, no `watchos` — so a `commonMain` declaration fails to resolve for
            // the desktop target for a one-line reason that has nothing to do with the code.
            implementation(libs.kvault)
            // The media re-host job (#53). Lives here rather than in :androidApp because
            // PlatformModule.android.kt binds it and :shared cannot depend on the app module.
            implementation(libs.androidx.work.runtime)
            // JNA is required by the UniFFI-generated Kotlin bindings (uniffi.pubkycore).
            // `@aar` pulls the Android-flavored artifact with the native .so bundled.
            implementation("${libs.jna.get().module}:${libs.versions.jna.get()}@aar")
            // `org.rustls.platformverifier.CertificateVerifier`, which libpubkycore.so calls back
            // into over JNI to verify a TLS certificate against the Android trust store.
            //
            // Without it EVERY Pubky HTTPS request that needs webpki verification dies with
            // "failed to call native verifier", surfaces as `HTTP transport error: error sending
            // request`, and — because `isNetworkFailure()` matches "transport" — is reported to
            // the user as "You're offline" on a device whose connection is fine. Reads served off
            // a pkarr-derived certificate verify by another path and still succeed, which is what
            // made this look intermittent rather than total.
            //
            // `RustlsInit` is the other half and is not enough on its own: it hands the crate the
            // JVM and Context, and the class it then looks up has to be on the classpath.
            implementation(libs.rustls.platform.verifier)
        }
    }
}


/**
 * Two invariants this module's build silently loses if the Android target's type changes under it.
 *
 * `jvmShared` is a *custom* hierarchy group, and its membership predicate has to name a type. When
 * the group stops matching the Android target, Gradle does not complain — it builds the group
 * without it, `androidMain` falls back to depending on `commonMain`, and the first sign is the
 * generated `uniffi.pubkycore` bindings vanishing from the Android compile (KT-80409). The iOS
 * check is the older trap the source-set comment above describes, asserted rather than trusted.
 */
afterEvaluate {
    val androidMain = kotlin.sourceSets.getByName("androidMain")
    check(androidMain.dependsOn.any { it.name == "jvmSharedMain" }) {
        "androidMain no longer depends on jvmSharedMain — the `jvmShared` group stopped matching " +
            "the Android target (KT-80409), and uniffi.pubkycore is about to vanish from the " +
            "Android compile."
    }
    check(kotlin.sourceSets.findByName("iosMain") != null) {
        "iosMain is gone — the default hierarchy template is switched off."
    }
}

/**
 * The four `libpubkycore.so` are what every Pubky call runs on, and an AAR built without them is
 * still a perfectly valid AAR: nothing fails until the first FFI call on a device, long past CI.
 * `src/androidMain/jniLibs` is picked up by convention rather than by anything written down here,
 * so this asserts the convention still holds — the same reason `:cli` has
 * `checkNativeImageIsOneFile`.
 */
val checkJniLibsArePackaged = tasks.register("checkJniLibsArePackaged") {
    group = "verification"
    description = "Fails if the AAR is missing libpubkycore.so for any shipped ABI."
    val abis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
    // A local, not the project: reading `layout` inside `doLast` captures the script object, which
    // the configuration cache refuses to serialize. Same reason `:cli` does this.
    val aarDir = layout.buildDirectory.dir("outputs/aar").get().asFile
    doLast {
        val aar = aarDir.listFiles().orEmpty().firstOrNull { it.extension == "aar" }
        checkNotNull(aar) {
            "no AAR in $aarDir — this task finalizes the bundle task rather than building one. " +
                "Run `:shared:assembleAndroidMain`."
        }
        val present = ZipFile(aar).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.endsWith("/libpubkycore.so") }
                .map { it.name.substringBeforeLast('/').substringAfterLast('/') }
                .toSet()
        }
        val missing = abis - present
        check(missing.isEmpty()) {
            "${aar.name} is missing libpubkycore.so for ${missing.joinToString()}. Every Pubky " +
                "call goes through it, and an app shipped without one fails at the first FFI " +
                "call rather than at build time. Check that src/androidMain/jniLibs is still " +
                "being packaged by the Android target."
        }
    }
}

/**
 * Finalizes the bundle task rather than being depended on, so the assertion rides along with any
 * build that produces an AAR instead of only with the workflows that remember to ask for it — the
 * same wiring as `:cli:nativeCompile` and `checkNativeImageIsOneFile`.
 *
 * Note that `:androidApp:assembleDebug` does **not** reach here: AGP consumes this module's
 * intermediate artifacts, not the packaged AAR. `:shared:assembleAndroidMain` is what produces one,
 * and that is what `ciCheck` and CI ask for.
 */
tasks.matching { it.name == "bundleAndroidMainAar" }.configureEach {
    finalizedBy(checkJniLibsArePackaged)
}
