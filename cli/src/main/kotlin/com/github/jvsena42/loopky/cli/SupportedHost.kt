package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.platform.DesktopNativeRow
import com.github.jvsena42.loopky.platform.desktopNativeRow
import com.github.jvsena42.loopky.platform.unsupportedDesktopHostMessage

/**
 * A shipped host as a *release asset*, which is the only part of the matrix the CLI owns (#210).
 *
 * Which hosts exist and why the other two do not is [DesktopNativeRow], in `:shared` beside the
 * libraries themselves — one table, because a second copy here would drift from the one the FFI
 * actually loads. What this adds is the download name, and it is the same table `cli/install.sh`
 * carries in shell: `loopky update` fetches by that name, so the two have to stay in step.
 *
 * The refusal happens at the CLI's command boundary rather than at the FFI, because this is where
 * a process can exit with something an agent can branch on. Without it an unshipped host is not
 * refused, it is *misdiagnosed*: `Native.load` throws `UnsatisfiedLinkError("… not found in
 * resource path …")`, `isNotFound()` matches those two words, and the answer is
 * [ExitCode.NotFound] — an agent is told the deck it asked for does not exist, on a machine where
 * no deck could ever be read.
 */
internal enum class SupportedHost(val row: DesktopNativeRow, val asset: String) {
    LinuxX64(DesktopNativeRow.LinuxX64, "loopky-linux-x86-64"),
    MacArm64(DesktopNativeRow.MacArm64, "loopky-macos-aarch64"),
}

internal fun hostSupport(
    osName: String = System.getProperty("os.name").orEmpty(),
    arch: String = System.getProperty("os.arch").orEmpty(),
): SupportedHost? = desktopNativeRow(osName, arch)?.let { row ->
    SupportedHost.entries.first { it.row == row }
}

/** The message a refused host gets: what it is, why there is no build for it, and what to do. */
internal fun unsupportedHostMessage(
    osName: String = System.getProperty("os.name").orEmpty(),
    arch: String = System.getProperty("os.arch").orEmpty(),
): String = unsupportedDesktopHostMessage(osName, arch)

/**
 * Refuse a host before anything tries to load the library on it.
 *
 * Called from the command boundary rather than from `main`, so `--version` and `--help` still
 * answer on a machine that cannot run anything else — an agent working out *why* the binary is
 * refusing needs those two to work.
 */
internal fun requireSupportedHost() {
    if (hostSupport() == null) throw CliError(ExitCode.UnsupportedHost, unsupportedHostMessage())
}
