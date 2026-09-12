package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.platform.isMacOs
import java.nio.file.Path

/**
 * The desktop [SecureSessionStore], chosen by host (#213).
 *
 * macOS gets the Keychain and Linux gets the 0600 file, and the split is not an inconsistency —
 * it is the two rows having genuinely different users. The Linux row's primary target is the
 * headless box an agent runs on, where libsecret is usually *absent*, so a keyring default would
 * fail exactly where the tool is meant to work ([ConfigHome]). The macOS row is the developer's
 * machine: there is a human at the keyboard and the Keychain is always there.
 *
 * **An explicit config home keeps everything in it.** `LOOPKY_CONFIG_HOME` exists so a container
 * or a test can point state somewhere disposable — a Keychain item is not disposable, and one
 * shared across every config home would make `LOOPKY_CONFIG_HOME=/tmp/x loopky login` overwrite
 * the caller's real session. So the Keychain is used only when the resolved home *is* the
 * platform default, which is also what keeps this testable without touching the real keychain.
 *
 * That gate belongs to the *Keychain* rather than to the idea of a secure item, and the difference
 * will matter when a second host arrives: an item addressed by service name is shared by every
 * config home, where one that lives *inside* the config home is as disposable as the directory
 * holding it. [keychainEligible] is therefore named for the thing it gates, not generalised.
 *
 * **`XDG_CONFIG_HOME` does the same thing, and it is the one that will surprise people.**
 * [ConfigHome.resolve] returns `$XDG_CONFIG_HOME/loopky` before it ever reaches
 * [ConfigHome.platformDefault], so a Mac user who exports it — common among exactly the dotfiles
 * crowd this tool is for — gets the file store, never migrates, and is told only by `whoami`'s
 * `session_store`. That follows from the same rule rather than contradicting it: the variable
 * means "keep everything here" too. It is written down here, in [ConfigHome], and in `--help`
 * because nothing about the behaviour announces itself.
 */
internal fun desktopSecureSessionStore(
    configHome: Path,
    secrets: JsonFileStore,
    item: SecureItem? = if (keychainEligible(configHome)) SecurityCliKeychain() else null,
): SecureSessionStore {
    val file = FileSecureSessionStore(secrets)
    return if (item == null) file else SecureItemSessionStore(item, file)
}

/** Whether this host and this [configHome] are the pair the Keychain is used for. */
internal fun keychainEligible(
    configHome: Path,
    macOs: Boolean = isMacOs(),
    toolPresent: Boolean = keychainToolPresent(),
    default: Path = ConfigHome.platformDefault(),
): Boolean = macOs &&
    toolPresent &&
    configHome.toAbsolutePath().normalize() == default.toAbsolutePath().normalize()
