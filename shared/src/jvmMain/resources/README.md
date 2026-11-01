# Native libraries for the desktop JVM target

`libpubkycore` for every desktop host the JVM target supports, laid out the way **JNA** looks for
it: `Platform.getNativeLibraryResourcePrefix()` maps a host to one of these directory names, and
`Native.load("pubkycore")` — the call the UniFFI-generated bindings make — extracts the matching
file from the classpath at runtime.

That layout is the whole point. It means the native library ships **inside the jar**: nobody
installing the CLI fetches one by hand, sets `-Djna.library.path`, or runs `ldconfig`.

| Directory | Host | Built by |
| --- | --- | --- |
| `linux-x86-64/libpubkycore.so` | Linux x86_64 (glibc) | `pubky-core-ffi-fork/build_desktop.sh linux` |
| `darwin-aarch64/libpubkycore.dylib` | macOS on Apple Silicon | `pubky-core-ffi-fork/build_desktop.sh macos` |

**Do not edit these; they are build output.** Regenerate them in the fork and copy the
`bindings/desktop/` tree here — the same arrangement `shared/src/androidMain/jniLibs` already has
for the four Android ABIs.

Two hosts are absent on purpose. An **Intel Mac** is not a target, so there is one `darwin-aarch64`
row rather than two and a `lipo`; the CLI refuses it by name in `SupportedHost`, *before* the
lookup, because a miss here is not merely unclassified — `Native.load` throws "…not found in
resource path…" and the shared classifier reads those two words as a 404, so the machine that can
never run the client reports that the deck does not exist. **Windows** is refused the same way:
its users are pointed at the hosted MCP connector (`https://mcp.loopky.app/mcp`) instead, which
needs no install.

These files also decide the native binary's glibc floor, which is **2.34** — higher than anything
`native-image` itself needs. That is why `cli/Dockerfile` builds inside `ubuntu:22.04`: matching
the library's floor rather than the build runner's.

`UniffiPubkyClientJvmTest` is what proves a row actually loads. The 1,271 shared tests run against
a fake client and pass identically on a machine where this directory is empty, the architecture is
wrong, or the file is one level off — none of which surfaces until the first homeserver call, as
an ordinary-looking transport error.
