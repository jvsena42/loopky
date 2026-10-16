# The hosted MCP server (#214) as one image, the unit CD deploys by digest (#486).
#
#   docker build -f mcp/Dockerfile -t loopky-mcp .
#
# **Jammy, not bookworm or noble, for the same reason `cli/Dockerfile` is on 22.04:** the
# `libpubkycore.so` JNA extracts at runtime needs GLIBC_2.34, and the base has to carry at least
# that. Temurin's jammy images do (2.35); a musl/alpine base does not load it at all.
FROM --platform=linux/amd64 eclipse-temurin:17-jdk-jammy AS builder

ENV GRADLE_USER_HOME=/gradle
WORKDIR /src
COPY . /src

# No Android SDK in the image and none needed: `:mcp` reaches only `:shared`'s jvm() target.
RUN ./gradlew --no-daemon --stacktrace :mcp:installDist

FROM --platform=linux/amd64 eclipse-temurin:17-jre-jammy AS runtime

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /src/mcp/build/install/loopky-mcp /opt/loopky-mcp

# Non-root, and a fixed uid so the host's data directory can be chowned to it without guessing.
RUN useradd --system --uid 10001 --home-dir /nonexistent --shell /usr/sbin/nologin loopky \
    && mkdir -p /data && chown loopky /data
USER loopky

# JNA and sqlite-jdbc unpack their native libraries into /tmp and load them from there, so the
# read-only root needs a tmpfs at /tmp that allows exec — Docker's default tmpfs is noexec, and
# the server then dies at the first database open. The heap cap keeps a shared VPS's other tenant
# fed (#486). RUST_LOG=warn because libpubkycore logs relay channel URLs at INFO, and a pending
# sign-in's channel is not something a log should hold.
ENV LOOPKY_MCP_HOST=0.0.0.0 \
    LOOPKY_MCP_PORT=8080 \
    LOOPKY_MCP_DATA_DIR=/data \
    RUST_LOG=warn \
    JAVA_OPTS="-Xmx384m -XX:+ExitOnOutOfMemoryError -Djna.tmpdir=/tmp"

EXPOSE 8080
VOLUME ["/data"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/healthz || exit 1

ENTRYPOINT ["/opt/loopky-mcp/bin/loopky-mcp"]
