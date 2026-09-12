package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.util.Log
import com.sun.jna.Function
import com.sun.jna.Platform
import com.sun.jna.WString

/**
 * Quiet `libpubkycore`'s own `tracing` output unless the user asked for it.
 *
 * With no `RUST_LOG` set the SDK installs a subscriber that defaults to roughly
 * `pubky=info,pubkycore=debug` and writes ANSI-coloured lines to stderr — four of them before
 * `login` has printed anything of its own, interleaved with the QR code. It is genuinely useful
 * when a relay or a TLS handshake is misbehaving and pure noise the rest of the time, so `warn` is
 * a **default** and never an override: `RUST_LOG=debug loopky …` still works and is the first
 * thing to try when a homeserver call fails for no visible reason.
 *
 * The jar's start script does this with one line of shell. A binary has no start script, and a
 * process cannot change its own environment through `System.getenv` — that map is a copy the
 * runtime made at startup, and Rust reads the real `environ`. So the only way to set it from
 * inside is the libc call the shell would have made, which JNA is already linked against for the
 * FFI. `Function.getFunction` rather than a `Library` interface deliberately: an interface would
 * be a second dynamic proxy to register with `native-image`, for one call.
 *
 * Best-effort in every direction. A host where the symbol cannot be resolved gets the SDK's own
 * default, which is verbose rather than broken, and that is not a reason to refuse to run.
 *
 * **It costs about 120 ms**, and it is worth knowing where that goes: not in the call, which is
 * one `setenv`, but in JNA's own initialisation — `Native`'s static block unpacks
 * `libjnidispatch` out of the binary's resources before any foreign call can be made. Every
 * command that reaches the homeserver pays that anyway, so this adds nothing to the ones that
 * matter; what it does cost is the handful that would have exited without touching the FFI at all
 * (`whoami` with no session, `tag trending`), which go from ~8 ms to ~130 ms. `--version` and
 * `--help` are answered before this is reached and stay at ~5 ms.
 *
 * The cheaper arrangement — call this at the first FFI use rather than at start-up — needs a hook
 * inside `:shared`, and Koin resolves `PubkyClient` for any command that touches a repository, so
 * a binding-time hook would fire at the same moment this does. Not worth the machinery for the
 * commands it would actually save.
 */
internal fun defaultRustLogToWarn(
    env: (String) -> String? = System::getenv,
    osName: String = System.getProperty("os.name").orEmpty(),
) {
    if (env("RUST_LOG") != null) return
    runCatching {
        if (isWindowsOs(osName)) {
            // `msvcrt` has no `setenv`, and porting to `_putenv_s` would not help: Rust's
            // `std::env` on Windows reads the process environment *block* through
            // `GetEnvironmentVariableW`, not the CRT's copy, so only the Win32 setter is observed
            // by the layer this is for (#301). `W`, because the wide form is the one that takes
            // `WString`.
            Function.getFunction("kernel32", "SetEnvironmentVariableW")
                .invokeInt(arrayOf<Any>(WString("RUST_LOG"), WString(RUST_LOG_DEFAULT)))
        } else {
            Function.getFunction(Platform.C_LIBRARY_NAME, "setenv")
                // `Any` explicitly: the array mixes two Strings and an Int, and Kotlin would
                // otherwise infer an intersection type for it.
                .invokeInt(arrayOf<Any>("RUST_LOG", RUST_LOG_DEFAULT, OVERWRITE_EXISTING))
        }
    }.onFailure { Log.d(TAG, "could not default RUST_LOG: ${it.message}") }
}

/**
 * `warn`, not `off`. A warning from the SDK is something the user should see — it is the layer
 * that knows about relays, DHT lookups and TLS, and nothing above it can re-report what it drops.
 */
private const val RUST_LOG_DEFAULT = "warn"

/** `setenv`'s third argument. Reached only when the variable is unset, so it never overwrites. */
private const val OVERWRITE_EXISTING = 1

private const val TAG = "Loopky/Cli"
