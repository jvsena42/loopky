# `loopky` as a Linux x86_64 binary, and as an image you can run it from (#210).
#
# Four targets:
#
#   toolchain  GraalVM and a C toolchain, with no source in it — the layer worth caching, and the
#              one to `docker run` interactively when a native build needs iterating on
#   builder    that, plus a compiled image
#   export     the binary alone, for `--output type=local`
#   runtime  a runnable container, which is the other half of the install story rather than an
#            alternative to it: a sandbox that lets you choose an image needs no install step
#
#   docker build -f cli/Dockerfile --target export --output type=local,dest=cli/build/native/linux-x86-64 .
#   docker build -f cli/Dockerfile --target runtime -t loopky .
#
# **Ubuntu 22.04 is the base on purpose and is not free to change.** A `native-image` binary links
# against the glibc of the machine that built it, and the floor is not ours to pick: the
# `libpubkycore.so` this dlopens already needs GLIBC_2.34. 22.04 ships 2.35, so building here
# reaches everything from RHEL 9 and Debian 12 upward. Building on a newer runner instead — 24.04
# is glibc 2.39 — would produce a binary that refuses to start on hosts the library itself is
# perfectly happy on, and the error is `version 'GLIBC_2.39' not found`, at exec, with nothing
# about loopky in it.
FROM --platform=linux/amd64 ubuntu:22.04 AS toolchain

ARG GRAALVM_VERSION=25.0.2
ARG GRAALVM_URL=https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-${GRAALVM_VERSION}/graalvm-community-jdk-${GRAALVM_VERSION}_linux-x64_bin.tar.gz

# `build-essential` and `zlib1g-dev` are `native-image`'s own requirements — it links the image
# with the system `cc` — not the application's.
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install --no-install-recommends -y \
        ca-certificates curl build-essential zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /opt/graalvm \
    && curl -fsSL "${GRAALVM_URL}" | tar xz -C /opt/graalvm --strip-components=1

ENV JAVA_HOME=/opt/graalvm \
    GRAALVM_HOME=/opt/graalvm \
    PATH=/opt/graalvm/bin:$PATH \
    GRADLE_USER_HOME=/gradle

FROM toolchain AS builder

WORKDIR /src
COPY . /src

# No Android SDK is installed and none is needed: `:cli` and `:shared`'s jvm() target configure
# and build without one. `--no-daemon` because the container exits with the build.
RUN ./gradlew --no-daemon --stacktrace :cli:nativeCompile

# **The whole of what `native-image` produced, not just the binary.** Copying `loopky` alone
# would make any "is this one file?" check downstream a tautology — the answer would be 1 whatever
# the build emitted. The real guard is `checkNativeImageIsOneFile` inside the builder above, which
# fails the `docker build`; this stage copies the directory so a caller can *see* the property
# rather than take it on trust, and so the assertion in CI is an assertion.
FROM scratch AS export
COPY --from=builder /src/cli/build/native/nativeCompile/ /

# A runnable image.
#
# `ca-certificates` is belt-and-braces rather than the usual "otherwise TLS fails", and it is worth
# knowing which. Both halves of the client carry their own roots: the Java side uses the `cacerts`
# `native-image` baked into the binary, and `libpubkycore`'s rustls falls back to bundled webpki
# roots on Linux. `debian:bookworm-slim` has no `/etc/ssl/certs` at all and both Nexus and the auth
# relay were reached from it unchanged. This is here for a host that needs the *system* store —
# a private CA in front of a self-hosted homeserver — and costs one small layer.
FROM --platform=linux/amd64 debian:bookworm-slim AS runtime

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install --no-install-recommends -y ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /src/cli/build/native/nativeCompile/loopky /usr/local/bin/loopky

# A non-root user, because the sandbox this runs in usually is one, and because
# `$XDG_CONFIG_HOME/loopky` holding a 0600 session file is meaningless when everything is uid 0.
RUN useradd --create-home --uid 1000 loopky
USER loopky
ENV HOME=/home/loopky \
    XDG_CONFIG_HOME=/home/loopky/.config

# The image says what it is, rather than being guessed at (#209). `loopky update` replaces a file,
# which inside a container is a change that vanishes with the container — so it refuses here and
# says `docker pull` instead. The two marker files (`/.dockerenv`, `/run/.containerenv`) cover an
# image somebody else built this binary into; this covers ours, exactly, on any runtime.
#
# The version *check* still runs: an image pinned to an old tag in a long-lived sandbox is
# precisely the stale-client problem, and being told which command to run beats being told nothing.
ENV LOOPKY_CONTAINER=1

# `LOOPKY_SESSION` is how a container signs in — there is no stored session in an image and nobody
# is watching its terminal to scan a QR code (Architecture.md §13.5).
ENTRYPOINT ["loopky"]
CMD ["--help"]
