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
| `win32-x86-64/pubkycore.dll` | Windows x86_64 | the fork's `desktop-windows.yml` — see below |

**Do not edit these; they are build output.** Regenerate them in the fork and copy the
`bindings/desktop/` tree here — the same arrangement `shared/src/androidMain/jniLibs` already has
for the four Android ABIs.

**`pubkycore.dll` carries no `lib` prefix** — that is JNA's Windows spelling, and the two other
rows' prefixes are equally not ours to choose.

**The Windows row is the one nobody can rebuild here.** `x86_64-pc-windows-msvc` needs the
Microsoft linker and the Windows SDK, so unlike the Linux row there is no container that
cross-builds it from a Mac; it comes from the fork's `desktop-windows.yml`, which also asserts the
DLL needs no Visual C++ redistributable. That assertion is load-bearing rather than tidiness: a
Rust MSVC cdylib links `vcruntime140.dll` dynamically by default, that library is **not** part of
Windows, and a machine without the redistributable fails `LoadLibrary` — which JNA reports as
"…not found in resource path…", which the shared classifier reads as a 404. The build is green
either way, because a CI runner has the redistributable installed.

One host is absent on purpose. An **Intel Mac** is not a target, so there is one `darwin-aarch64`
row rather than two and a `lipo`; the CLI refuses it by name in `SupportedHost`, *before* the
lookup, because a miss here is not merely unclassified — `Native.load` throws "…not found in
resource path…" and the shared classifier reads those two words as a 404, so the machine that can
never run the client reports that the deck does not exist. **ARM64 Windows** is refused the same
way and for a narrower reason: the x64 binary runs there under emulation, but a JVM reporting
`aarch64` cannot load an x64 DLL into its own process.

These files also decide the native binary's glibc floor, which is **2.34** — higher than anything
`native-image` itself needs. That is why `cli/Dockerfile` builds inside `ubuntu:22.04`: matching
the library's floor rather than the build runner's.

`UniffiPubkyClientJvmTest` is what proves a row actually loads. The 1,271 shared tests run against
a fake client and pass identically on a machine where this directory is empty, the architecture is
wrong, or the file is one level off — none of which surfaces until the first homeserver call, as
an ordinary-looking transport error.
