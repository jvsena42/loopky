# Homebrew formula for `loopky`.
#
# **This file is a template and lives here rather than where Homebrew reads it.** A tap is its own
# repository — `jvsena42/homebrew-loopky` — and this one has to be created by hand, once, with a
# `Formula/` directory in it. After that `release.yml`'s `homebrew` job renders this file into
# `Formula/loopky.rb` there on every final tag, given a `HOMEBREW_TAP_TOKEN` that can push to it.
#
# The three `__…__` placeholders are named rather than left as an anonymous run of zeroes because
# **the two sums are not interchangeable** — one per row — and a template that repeats the same
# dummy value twice can be filled in the wrong order without anything noticing. The job also
# refuses to push a formula with a `__…__` still in it, which is what catches a renamed placeholder;
# left in, it installs nothing and says so only at `brew install` time.
#
# One bottle, for Apple Silicon. An Intel Mac is not a target (#54) and the formula says so out
# loud rather than installing something that fails at its first homeserver call: there is one
# `darwin-aarch64` row of `libpubkycore` and no `lipo`.
class Loopky < Formula
  desc "Headless Loopky client — create and manage Pubky flashcard decks from a terminal"
  homepage "https://github.com/jvsena42/loopky"
  version "__VERSION__"
  license "MIT"

  on_macos do
    on_arm do
      url "https://github.com/jvsena42/loopky/releases/download/v#{version}/loopky-macos-aarch64"
      sha256 "__SHA256_MACOS_AARCH64__"
    end
    on_intel do
      odie "loopky ships one macOS build and it is for Apple Silicon. See cli/README.md."
    end
  end

  on_linux do
    on_intel do
      url "https://github.com/jvsena42/loopky/releases/download/v#{version}/loopky-linux-x86-64"
      sha256 "__SHA256_LINUX_X86_64__"
    end
    # Spelled out for the same reason as the Intel-Mac branch. Left off the end, this arch gets a
    # formula error about a missing `url` — Homebrew's words about our file, rather than ours
    # about the missing build.
    on_arm do
      odie "there is no Linux arm64 build. What is missing is a libpubkycore for that host, " \
           "not this client — see shared/src/jvmMain/resources/README.md."
    end
  end

  def install
    bin.install Dir["loopky*"].first => "loopky"
    # The download is a bare executable, not an archive, so it arrives 0644 and `bin.install`
    # keeps the mode. Without this the install dies one line down with
    # `brew: exec failed (EACCES)` and never reaches a user.
    (bin/"loopky").chmod 0555
    # Generated from the binary just installed rather than shipped as files, so the completions
    # cannot describe a surface this build does not have. Homebrew runs `loopky completion <shell>`
    # for each of the three and puts the output where each shell looks.
    generate_completions_from_executable(bin/"loopky", "completion", shells: [:bash, :zsh, :fish])
  end

  test do
    assert_match "loopky", shell_output("#{bin}/loopky --version")
    # Exit 3 is "not signed in", which is the correct answer on a machine with no session and the
    # cheapest proof that the binary got as far as parsing a command.
    assert_match "not_signed_in", shell_output("#{bin}/loopky whoami --json", 3)
  end
end
