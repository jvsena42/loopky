package com.github.jvsena42.loopky.platform

/**
 * The desktop hosts `libpubkycore` is built for, laid out the way JNA looks a library up
 * (`shared/src/jvmMain/resources/README.md`).
 *
 * Two hosts are absent **by decision**. An **Intel Mac** is not a target, one `darwin-aarch64` row
 * rather than two and a `lipo`, because no part of the workload that motivated the desktop build
 * runs on one (#54). **Windows** is not one either: its users reach Loopky through the hosted MCP
 * connector, which needs no install, and the row cost a runner, a DPAPI store and a VC++
 * redistributable precondition of its own.
 */
enum class DesktopNativeRow(val jnaPrefix: String, val label: String) {
    LinuxX64("linux-x86-64", "Linux x86_64"),
    MacArm64("darwin-aarch64", "macOS on Apple Silicon"),
}

/** The row this host loads, or null when there is no build for it. */
fun desktopNativeRow(
    osName: String = System.getProperty("os.name").orEmpty(),
    arch: String = System.getProperty("os.arch").orEmpty(),
): DesktopNativeRow? {
    val os = osName.lowercase()
    val cpu = arch.lowercase()
    return when {
        os.startsWith("linux") && (cpu == "amd64" || cpu == "x86_64") -> DesktopNativeRow.LinuxX64
        os.startsWith("mac") && (cpu == "aarch64" || cpu == "arm64") -> DesktopNativeRow.MacArm64
        else -> null
    }
}

/** True on macOS, whatever the architecture — the question [ConfigHome] and the Keychain ask. */
internal fun isMacOs(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
    osName.startsWith("Mac", ignoreCase = true)

/** What a refused host is told: what it is, why there is no build for it, and what to do. */
fun unsupportedDesktopHostMessage(
    osName: String = System.getProperty("os.name").orEmpty(),
    arch: String = System.getProperty("os.arch").orEmpty(),
): String {
    val os = osName.lowercase()
    val cpu = arch.lowercase()
    val advice = when {
        os.startsWith("mac") ->
            "loopky ships one macOS build and it is for Apple Silicon. If this is an Apple " +
                "Silicon Mac, you are on an x86_64 JVM under Rosetta: use the native binary, " +
                "which has no JVM to get wrong — or, for this jar, reinstall an arm64 JDK."
        os.startsWith("windows") -> WINDOWS_ADVICE
        else -> "The builds are ${DesktopNativeRow.entries.joinToString(" and ") { it.label }}."
    }
    return "loopky has no build for $osName ($cpu). $advice"
}

/**
 * Where Windows users are sent instead of a build. Also written by hand in `cli/install.sh`,
 * `cli/install.ps1` and the agent skill; `ConnectorUrlTest` holds all of them to this value.
 */
const val CONNECTOR_URL = "https://mcp.loopky.app/mcp"

private const val WINDOWS_ADVICE =
    "On Windows, use the Loopky connector for Claude instead — " +
        "https://claude.ai/directory/loopky, or $CONNECTOR_URL as a custom connector — " +
        "or run the Linux build inside WSL."

/**
 * Refuse a host before anything asks JNA to load a library it has no row for (#213).
 *
 * The failure this replaces is not a missing feature, it is a **wrong diagnosis**. `Native.load`
 * finds no matching directory on the classpath and throws
 * `UnsatisfiedLinkError("Unable to load library 'pubkycore': … not found in resource path …")`,
 * which the shared classifier reads as a 404 — so an Intel Mac is told the record it asked for
 * does not exist, on a machine where no record could ever be read.
 *
 * `:cli` refuses earlier still, at its command boundary, so it can exit with a code an agent can
 * branch on. This is the same fact enforced for every other desktop consumer of `:shared`, which
 * reaches the FFI through Koin and never sees that boundary.
 */
fun requireSupportedDesktopHost() {
    if (desktopNativeRow() == null) error(unsupportedDesktopHostMessage())
}
