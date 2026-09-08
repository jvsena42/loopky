import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.internal.os.OperatingSystem

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    // :cli is a plain JVM module. Declared here so the version resolves once, like every other
    // plugin in this build.
    alias(libs.plugins.kotlinJvm) apply false
    // `native-image` for :cli (#210). Declared here for the same reason as the rest, and left
    // unapplied everywhere else — nothing but the headless client is shipped as a binary.
    alias(libs.plugins.graalvmNative) apply false
    alias(libs.plugins.detekt) apply false
}

subprojects {
    apply(plugin = rootProject.libs.plugins.detekt.get().pluginId)

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        parallel = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        basePath = rootProject.projectDir.absolutePath
        source.setFrom(
            files(
                "src/commonMain/kotlin",
                "src/androidMain/kotlin",
                "src/iosMain/kotlin",
                // The JVM family (#54): `jvmSharedMain` is Android + desktop, `jvmMain` is the
                // desktop half alone. Listed explicitly, like every other set here — a source set
                // missing from this list is simply not linted, and nothing reports it.
                "src/jvmSharedMain/kotlin",
                "src/jvmMain/kotlin",
                "src/commonTest/kotlin",
                "src/jvmTest/kotlin",
                "src/androidHostTest/kotlin",
                // :cli is a plain JVM module, so its sources are where a JVM module puts them
                // rather than in a KMP source set.
                "src/main/kotlin",
                "src/test/kotlin",
            )
        )
    }

    dependencies {
        "detektPlugins"(rootProject.libs.detekt.formatting)
        "detektPlugins"(rootProject.libs.detekt.compose.rules)
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = "17"
        reports {
            html.required.set(true)
            xml.required.set(true)
            sarif.required.set(true)
            txt.required.set(false)
            md.required.set(false)
        }
        exclude("**/build/**", "**/generated/**", "**/uniffi/**")
    }
}

tasks.register("detektAll") {
    group = "verification"
    description = "Runs detekt on all subprojects."
    dependsOn(subprojects.map { "${it.path}:detekt" })
}

tasks.register<Exec>("lintSwift") {
    group = "verification"
    description = "Lints the iOS Swift sources with SwiftLint (config in iosApp/.swiftlint.yml)."
    workingDir = rootProject.file("iosApp")
    // No-op with a friendly hint if SwiftLint isn't installed, so the task never blocks contributors.
    commandLine(
        "sh", "-c",
        "command -v swiftlint >/dev/null 2>&1 && swiftlint lint --strict " +
            "|| echo 'SwiftLint not installed — skipping. Install with: brew install swiftlint'",
    )
}

/**
 * What CI runs, in one command (#239).
 *
 * The job list used to exist only as five `run:` lines in `.github/workflows/ci.yml` and a prose
 * summary in CLAUDE.md, so reproducing a CI failure locally meant reconstructing it from memory
 * and getting it subtly different each time.
 *
 * The **host-conditional half is the point**: `:shared:compileTestKotlinIosSimulatorArm64` and
 * `lintSwift` are the two checks a Linux runner cannot perform, so on a Mac this is strictly
 * stronger than CI rather than differently weak. CI covers them on its own `macos-14` job, but
 * only when the paths that can break them changed.
 *
 * That first one compiles `commonTest` as well as `commonMain`, and the test half is not
 * incidental: Kotlin/Native refuses characters in a backticked name that the JVM accepts, so a
 * test the Android backend compiles can break `:shared:allTests` on every Mac with both required
 * CI contexts green.
 *
 * Deliberately **not** here: `:cli:nativeCompile`. It needs a GraalVM 25 in `GRAALVM_HOME`, which
 * a checkout does not come with, and adding it would make the one command every contributor is
 * told to run fail on a machine where nothing is wrong. CI builds both binary rows instead.
 */
tasks.register("ciCheck") {
    group = "verification"
    description = "What CI runs, in one command (plus the iOS checks, on a Mac)."
    dependsOn(
        "detektAll",
        ":shared:testAndroidHostTest",
        ":shared:assembleAndroidMain",
        ":androidApp:testDebugUnitTest",
        ":shared:jvmTest",
        ":cli:test",
        ":androidApp:assembleDebug",
        ":cli:installDist",
    )
    if (OperatingSystem.current().isMacOsX) {
        dependsOn(":shared:compileTestKotlinIosSimulatorArm64", "lintSwift")
    }
}
