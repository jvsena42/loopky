rootProject.name = "Loopky"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    // 1.0.0 is the first release that runs on Gradle 9: 0.10.0 reads `JvmVendorSpec.IBM_SEMERU`,
    // which Gradle 9 removed. It only bites where a toolchain has to be *provisioned* — the CLI's
    // container has GraalVM 25 as its JAVA_HOME and `:cli` pins `jvmToolchain(17)`, so the
    // resolver runs there and `NoSuchFieldError` fails the build at configuration. A developer
    // machine with a local JDK 17 never calls it, which is why this passes locally either way.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        // The JVM half of the `rustls-platform-verifier` Rust crate, vendored under
        // `shared/libs/maven` in Maven layout.
        //
        // Not on Maven Central: the crate ships this AAR inside its own source tree
        // (`rustls-platform-verifier-android`), expecting each app to publish it locally. Copied
        // into the repo rather than resolved from `~/.cargo/registry` so CI and a fresh clone
        // build the same artifact — the registry path carries a checksum hash that changes with
        // the crate version and does not exist until someone has run `cargo build`.
        //
        // Scoped to the `rustls` group so it can never answer for anything else.
        maven {
            url = uri("${rootDir}/shared/libs/maven")
            content { includeGroup("rustls") }
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":androidApp")
include(":shared")
// The headless client (#54). A plain JVM module on :shared's jvm() target — no Android, no
// Compose, no presentation layer; it consumes the repositories directly.
include(":cli")