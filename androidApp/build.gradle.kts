import java.util.Base64
import java.util.Properties

/**
 * Which Pubky network the build talks to. `PubkyEnvironment` fuses everything scoped to one:
 * the Homegate that issues signup tokens, the homeserver those tokens are good for, the pubky.app
 * web client, and the Nexus indexer. Only the name is threaded through BuildConfig, so there is no
 * second value that can be set to a different network by accident (#205) — a token is single-use,
 * so spending one minted on the wrong environment loses it for good, and a mismatched indexer
 * answers for the other network instead of failing.
 */
val PUBKY_ENV_STAGING = "Staging"
val PUBKY_ENV_PRODUCTION = "Production"

/**
 * Must stay byte-for-byte identical to `OBFUSCATION_SALT` in `UnsplashKeyObfuscation.kt`, which
 * holds the inverse. Written twice because a Gradle script cannot import from `commonMain`;
 * `UnsplashKeyObfuscationTest` is what catches the two drifting apart.
 *
 * Not a secret. It ships in the same APK as the thing it scrambles.
 */
val UNSPLASH_OBFUSCATION_SALT = "loopky.unsplash.v1".toByteArray()

/**
 * XOR against [UNSPLASH_OBFUSCATION_SALT], then Base64 — just enough that the key is not a literal
 * in the dex. Blank stays blank, so `UnsplashClient.hasFallbackKey` keeps meaning what it says on a
 * build with no key configured.
 */
fun obfuscateUnsplashKey(key: String): String {
    if (key.isEmpty()) return ""
    val raw = key.toByteArray()
    val salted = ByteArray(raw.size) { i ->
        (raw[i].toInt() xor UNSPLASH_OBFUSCATION_SALT[i % UNSPLASH_OBFUSCATION_SALT.size].toInt()).toByte()
    }
    return Base64.getEncoder().encodeToString(salted)
}

/**
 * Local, untracked configuration: the SDK path, the Unsplash key and the four signing constants.
 *
 * Read through `providers.fileContents` so the configuration cache tracks `local.properties` as an
 * input. A plain `File.inputStream()` read at configuration time is untracked, so editing the file
 * would leave a stale cached configuration behind — and a stale one here means a release built
 * against yesterday's signing settings.
 */
val localProps = Properties().apply {
    providers.fileContents(
        rootProject.layout.projectDirectory.file("local.properties")
    ).asText.orNull?.let { load(it.reader()) }
}

/**
 * A local-configuration value, from `local.properties` on a developer machine and from
 * `LOOPKY_<name>` in the environment otherwise.
 *
 * The environment half exists because the signed release is built by `release.yml` on a tag, and a
 * runner has no `local.properties`. Writing one from the secrets would work and is deliberately
 * not what happens: it would leave the keystore password sitting in a file for the rest of the
 * job, where every later step and every `cat` in a debugging session can reach it.
 *
 * **That only holds with the configuration cache off, which is why the release job passes
 * `--no-configuration-cache`.** `providers.environmentVariable` is read at configuration time, so
 * with the cache on (the default here) Gradle tracks the value as a fingerprint input and
 * serialises the resulting signing config into `.gradle/configuration-cache/` — back inside the
 * workspace, which is the exposure this was avoiding. Changing only the password prints "cannot be
 * reused because environment variable 'LOOPKY_KEYSTORE_PASSWORD' has changed", so it is tracked,
 * and `gradle/actions/setup-gradle` refuses to cache that data without an encryption key for the
 * same reason. Do not drop the flag from the release build to save a configuration phase.
 *
 * `LOOPKY_`-prefixed, because `KEY_ALIAS` and `KEY_PASSWORD` are names another tool can plausibly
 * already have set in the environment, and picking one up by accident signs the release with
 * something nobody chose. Read through `providers` so the configuration cache tracks both sources.
 */
fun localConfig(name: String): String? =
    (localProps.getProperty(name) ?: providers.environmentVariable("LOOPKY_$name").orNull)
        ?.trim()
        ?.ifEmpty { null }

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.github.jvsena42.loopky"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.github.jvsena42.loopky"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 26
        versionName = "0.11.1"

        // Unsplash key for the "from web" image search; blank → gallery-only fallback.
        //
        // Emitted scrambled, and only scrambled — a plain constant put the literal in classes3.dex
        // where `strings | grep` finds it, which is how leaked keys are actually found. This is a
        // speed bump against automated scanners, not protection: the key ends up in a live
        // Authorization header, so a proxy reads it regardless. See UnsplashKeyObfuscation.
        val unsplashKey = localConfig("UNSPLASH_ACCESS_KEY") ?: ""
        buildConfigField("String", "UNSPLASH_ACCESS_KEY_OBF", "\"${obfuscateUnsplashKey(unsplashKey)}\"")
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        // Absent on a machine with no keystore and on ordinary CI, where release builds stay
        // unsigned. The four constants come from the gitignored local.properties or from the
        // release workflow's environment, and are never read anywhere else — refer to them by
        // name only.
        val keystoreFile = localConfig("KEYSTORE_FILE")
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = requireNotNull(localConfig("KEYSTORE_PASSWORD")) {
                    "KEYSTORE_FILE is set but KEYSTORE_PASSWORD is not"
                }
                keyAlias = requireNotNull(localConfig("KEY_ALIAS")) {
                    "KEYSTORE_FILE is set but KEY_ALIAS is not"
                }
                keyPassword = requireNotNull(localConfig("KEY_PASSWORD")) {
                    "KEYSTORE_FILE is set but KEY_PASSWORD is not"
                }
            }
        }
    }
    buildTypes {
        getByName("debug") {
            // Staging is the dev-only default, and it moves the whole environment together —
            // gate, homeserver, web client and indexer. The build-time default only; a debug
            // build can also switch environment at runtime from Settings, which takes effect on
            // the next launch.
            val debugEnv = localConfig("PUBKY_ENV") ?: PUBKY_ENV_STAGING
            buildConfigField("String", "PUBKY_ENV", "\"$debugEnv\"")

            // The one sanctioned way to read an indexer the environment did not choose: a Nexus
            // running on your own machine (#58). Named for exactly that — to point a debug build
            // at another *network*, set PUBKY_ENV, which moves the indexer with it. Blank means
            // the environment decides, which is what it should do.
            val localNexus = localConfig("LOCAL_NEXUS_BASE_URL") ?: ""
            buildConfigField("String", "LOCAL_NEXUS_BASE_URL", "\"$localNexus\"")
        }
        getByName("release") {
            // Null without a keystore, and then the build produces an unsigned APK rather than
            // failing — the release skill is what verifies the signature before publishing.
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            // Never overridable: a release must read the same network its users publish to (#42).
            // One value now decides all four endpoints, so there is nothing else to pin.
            buildConfigField("String", "PUBKY_ENV", "\"$PUBKY_ENV_PRODUCTION\"")
            buildConfigField("String", "LOCAL_NEXUS_BASE_URL", "\"\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(projects.shared)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.materialIconsExtended)
    implementation(libs.compose.ui)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.androidx.lifecycle.viewmodelCompose)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.viewmodel)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.koin.android)
    implementation(libs.play.services.code.scanner)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.zxing.core)

    debugImplementation(libs.compose.uiTooling)

    testImplementation(libs.kotlin.test)
}

