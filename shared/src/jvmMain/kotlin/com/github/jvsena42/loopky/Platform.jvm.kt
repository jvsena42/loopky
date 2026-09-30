package com.github.jvsena42.loopky

/**
 * The desktop JVM Loopky's headless client runs on (#54).
 *
 * Reports the OS and architecture rather than just "JVM", because the one thing that varies
 * between desktop hosts is exactly that pair: JNA resolves `libpubkycore` from a per-OS resource
 * directory, and a host outside the shipped matrix fails at library load with nothing else to say.
 */
class JvmPlatform : Platform {
    override val name: String =
        "JVM ${System.getProperty("java.version")} on " +
            "${System.getProperty("os.name")} ${System.getProperty("os.arch")}"
}

actual fun getPlatform(): Platform = JvmPlatform()
