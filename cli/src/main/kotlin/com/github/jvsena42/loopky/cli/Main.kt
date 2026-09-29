package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.cli.commands.BatchSinks
import com.github.jvsena42.loopky.cli.commands.DRY_RUN_FLAG
import com.github.jvsena42.loopky.cli.commands.LoginSinks
import com.github.jvsena42.loopky.cli.commands.batch
import com.github.jvsena42.loopky.cli.commands.cardAdd
import com.github.jvsena42.loopky.cli.commands.cardEdit
import com.github.jvsena42.loopky.cli.commands.cardList
import com.github.jvsena42.loopky.cli.commands.cardRemove
import com.github.jvsena42.loopky.cli.commands.commandSurface
import com.github.jvsena42.loopky.cli.commands.completion
import com.github.jvsena42.loopky.cli.commands.deckCompact
import com.github.jvsena42.loopky.cli.commands.deckCreate
import com.github.jvsena42.loopky.cli.commands.deckDelete
import com.github.jvsena42.loopky.cli.commands.deckEdit
import com.github.jvsena42.loopky.cli.commands.deckList
import com.github.jvsena42.loopky.cli.commands.deckShow
import com.github.jvsena42.loopky.cli.commands.deckSync
import com.github.jvsena42.loopky.cli.commands.doctor
import com.github.jvsena42.loopky.cli.commands.import
import com.github.jvsena42.loopky.cli.commands.importDryRun
import com.github.jvsena42.loopky.cli.commands.login
import com.github.jvsena42.loopky.cli.commands.logout
import com.github.jvsena42.loopky.cli.commands.requireImageCheckOptions
import com.github.jvsena42.loopky.cli.commands.sweepSupersededBinary
import com.github.jvsena42.loopky.cli.commands.tagTrending
import com.github.jvsena42.loopky.cli.commands.update
import com.github.jvsena42.loopky.cli.commands.whoami
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.repository.CardRepository
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.data.repository.TagRepository
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import kotlin.system.exitProcess

/**
 * `loopky` — a headless Loopky client, so an agent can create and manage decks (#54).
 *
 * Three properties everything here is arranged around:
 *
 * - **stdout is a machine channel.** Results go there and nothing else does; progress, prompts, the
 *   QR code and every log line go to stderr. One stray line makes `--json` undecodable, which is why
 *   the JVM `Log` actual writes to stderr even for debug.
 * - **Nothing prompts.** The "announce this deck?" confirmation is gone *by construction* rather
 *   than behind a flag, because this client never requests the capability a post would need.
 * - **The exit code is the primary result.** Session expiry has its own, because it is hourly and
 *   unrecoverable without a human, and an agent that cannot tell it from a network wobble either
 *   retries forever or gives up on a working network.
 */
fun main(argv: Array<String>) {
    ProxyEnvironment.from(System.getenv()).install()
    CertificateEnvironment.from(System.getenv()).install()
    val exit = runCatching { run(argv) }.getOrElse { error ->
        // Nothing should reach here; if it does, say so honestly rather than exiting 0.
        System.err.println("loopky: ${error::class.simpleName}: ${error.message}")
        ExitCode.Internal
    }
    exitProcess(exit.code)
}

@Suppress("ReturnCount")
private fun run(argv: Array<String>): ExitCode {
    val args = runCatching { Args.parse(argv) }.getOrElse { error ->
        System.err.println("loopky: ${error.message}")
        System.err.println("Try `loopky --help`.")
        return ExitCode.Usage
    }

    // Version before help, and both before the empty-command check: `loopky --version` has no
    // positional words, so the "you gave me nothing" branch would answer it with the usage block.
    if (args.has("version")) {
        println(VERSION)
        return ExitCode.Ok
    }
    // `--help --json` is the same table `commands` emits, because an agent reaching for help
    // through the machine channel is asking the same question. Bare `--json` with no verb is
    // still a usage error: "you gave me nothing" is not a request for the manual.
    if (args.has("help") && args.has("json")) {
        val env = CliEnvironment.resolve(args)
        println(successEnvelope("commands", env.name, env.indexer, commandSurface().data))
        return ExitCode.Ok
    }
    if (args.has("help")) {
        commandFor(args.verb)?.let { command ->
            println(commandHelp(command))
            return ExitCode.Ok
        }
    }
    if (args.words.isEmpty() || args.has("help")) {
        println(USAGE)
        return if (args.has("help")) ExitCode.Ok else ExitCode.Usage
    }

    Log.debugEnabled = args.has("verbose")
    val environment = CliEnvironment.resolve(args)
    val installation = detectInstallation()
    val updates = Updates(UpdateChecker(environment.configHome), installation)
    // The first moment the previous image is no longer running, which is the only moment it can be
    // deleted — Windows holds the file for as long as a process is executing it, so `update` has to
    // leave it behind and somebody has to come back for it. Silent and best-effort by construction:
    // see [sweepSupersededBinary] for why a leftover copy must never fail the command in hand.
    sweepSupersededBinary(installation)

    // One `runBlocking` around the whole command rather than around `dispatch` alone, so the
    // update check can run *concurrently* with the work (#209). On the one invocation a day that
    // actually goes to the network, it overlaps a homeserver round trip instead of adding to it —
    // and both outcomes need the answer, since `update_available` travels on a failure envelope
    // as well as a success.
    return runBlocking {
        // `runSuspendCatching`, and it is structural rather than defensive. `async` here is a child
        // of `runBlocking`'s job, which is **not** a supervisor: an exception escaping this lambda
        // cancels the in-flight command at its next suspension point, and the resulting
        // `CancellationException` reaches the generic handler below — which calls `await()` inside
        // its own catch, re-throwing past `runBlocking` entirely, to exit 1 with **nothing on
        // stdout**. `check()` returns null on every path; this makes that impossible to undo.
        val update = async {
            if (UpdateChecker.enabled(args)) {
                runSuspendCatching { updates.checker.check() }.getOrNull()
            } else {
                null
            }
        }
        try {
            // Two commands before the boundary. Everything below starts Koin, which resolves
            // `PubkyClient` and therefore loads `libpubkycore` — so on a host where that load fails
            // (an old glibc, a truncated download) anything past this point fails with an error
            // about the FFI. `update` is the command you reach for when the install is *broken*, and
            // `completion` prints a static string a shell rc file evaluates. See `preKoin`.
            val result = if (args.verb in PRE_KOIN_VERBS) {
                args.requireKnownOptions()
                preKoin(args, updates)
            } else {
                // Inside the boundary, not before it: starting Koin resolves `PubkyClient`, which
                // is where a host outside the shipped matrix fails at `Native.load` — so this is
                // the last point at which such a host can still be told what is wrong with it
                // rather than about a deck that does not exist. See `requireSupportedHost`.
                requireSupportedHost()
                val koin = startCli(environment, args.has("json"))
                dispatch(args, koin.identity(), koin, environment, args.has("json"), SessionCache())
            }
            emit(args, environment, args.verb, result, updates.notice(update.await()))
            ExitCode.Ok
        } catch (error: CliError) {
            fail(args, environment, args.verb, error, updates.notice(update.await()))
            error.exitCode
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            // `Throwable`, not `Exception`. An x86-64 Mac or a Windows box fails at `Native.load`
            // with `UnsatisfiedLinkError` / `ExceptionInInitializerError` — both `Error`s — and
            // catching only `Exception` let them past `fail()` to a bare exit 1 with **nothing on
            // stdout**. That breaks the "results and failures both go there as --json" contract for
            // precisely the case the README and `Platform.jvm.kt` both name as expected.
            val code = ExitCode.of(error)
            Log.e(TAG, "command failed", error)
            val message = error.message ?: error::class.simpleName.orEmpty()
            fail(args, environment, args.verb, CliError(code, message), updates.notice(update.await()))
            code
        } finally {
            // Koin may never have started, in which case stopping it throws over the failure we are
            // already reporting.
            runCatching { stopCli() }
        }
    }
}

/**
 * Route to a command function.
 *
 * Every arm is one call to one function taking plain values and returning its `--json` shape. That
 * is load-bearing: a remote MCP server serves an audience the CLI cannot reach — a chat-only agent
 * has no shell — and should be a binding over these functions rather than a second implementation.
 */
@Suppress("CyclomaticComplexMethod", "LongParameterList")
private suspend fun dispatch(
    args: Args,
    identity: IdentityRepository,
    koin: Koin,
    environment: CliEnvironment,
    /**
     * Whether **this invocation** asked for the machine channel.
     *
     * Passed rather than read off [args], because `batch` re-enters here with an operation's own
     * `Args` — built from a JSON `argv` array that never carries `--json`, since the flag is on
     * the outer command line. Deriving it here meant `loopky batch ops.ndjson --json` printed a
     * 20,000-card import's whole progress stream to stderr, in exactly the mode that suppresses it.
     */
    json: Boolean,
    /** Resolved once for the process; see [SessionCache]. `batch` re-enters here with the same one. */
    sessions: SessionCache,
): CommandResult {
    // Two sinks, because they are two different things and collapsing them silenced a warning in the
    // mode an agent runs. `progress` is a counter — thousands of lines on a large import — so it is
    // suppressed under `--json`, where the result carries the same numbers. `note` is something the
    // caller needs to *know* and goes to stderr always: an agent capturing stderr for diagnostics
    // must not get an empty file because it asked for JSON.
    val progress: (String) -> Unit = { line -> if (!json) System.err.println(line) }
    val note: (String) -> Unit = System.err::println
    // stdout, and only in the human mode — the same split `emit` makes for a single command. A
    // batch operation's *result* is a result, so it belongs on the channel results go to.
    val text: (String) -> Unit = { line -> if (!json) println(line) }
    // Here rather than in `run`, so a `batch` line is held to the same table a command line is —
    // a flag the surface does not describe must not be silently dropped in either (#257, item 5).
    args.requireKnownOptions()
    // Beside it because it asks the same kind of question — is this invocation self-consistent —
    // before any of it runs. `--check-images-concurrency` is otherwise read only from behind
    // `--check-images`, so on its own it was accepted and ignored.
    args.requireImageCheckOptions()
    return when (val verb = args.verb) {
        "login" -> login(
            args,
            identity,
            koin.get<SecureSessionStore>(),
            environment,
            LoginSinks({ line -> if (json) println(line) }, System.err::println),
        )
        "logout" -> logout(identity)
        "whoami" -> whoami(identity, koin.get<PubkyClient>(), koin.get<SecureSessionStore>(), environment)
        "doctor" -> doctor(args, koin.get<PubkyClient>()::resolveHttps, environment)

        "deck list" -> authed(sessions, identity, environment) { deckList(koin.decks()) }
        "deck show" -> authed(sessions, identity, environment) { deckShow(args, koin.decks()) }
        // A dry run with a minted id reads nothing from the homeserver, so like `import --dry-run`
        // it must not put a sign-in in front of checking a card file (#367).
        "deck create" -> {
            val offline = args.has(DRY_RUN_FLAG) && args.option("id") == null
            deckCreate(args, koin.decks(), if (offline) null else sessions.require(identity, environment), note, progress)
        }
        "deck edit" -> authed(sessions, identity, environment) { deckEdit(args, koin.decks()) }
        "deck delete" -> authed(sessions, identity, environment) { deckDelete(args, koin.decks()) }
        "deck sync" -> authed(sessions, identity, environment) { deckSync(args, koin.decks(), koin.cards()) }
        "deck compact" -> authed(sessions, identity, environment) { deckCompact(args, koin.decks()) }

        "card list" -> authed(sessions, identity, environment) { cardList(args, koin.decks(), koin.cards(), note) }
        "card add" -> authed(sessions, identity, environment) {
            cardAdd(args, koin.decks(), koin.cards(), note, progress)
        }
        "card edit" -> authed(sessions, identity, environment) { cardEdit(args, koin.decks(), koin.cards(), note) }
        "card rm" -> authed(sessions, identity, environment) { cardRemove(args, koin.decks()) }

        // `--dry-run` deliberately sits outside `authed`: it reads a local file and writes
        // nothing, so requiring a live session would put a sign-in between an agent and the check
        // that stops it publishing 9,000 cards of database ids (#96) or spending its whole media
        // quota on one deck.
        "import" -> if (args.has(DRY_RUN_FLAG)) {
            importDryRun(args, koin.get<ImportRepository>())
        } else {
            authed(sessions, identity, environment) { session ->
                import(
                    args = args,
                    imports = koin.get<ImportRepository>(),
                    decks = koin.decks(),
                    cards = koin.cards(),
                    media = koin.get<MediaRepository>(),
                    session = session,
                    onProgress = progress,
                    onNote = note,
                )
            }
        }

        // No session: a Nexus read is plain HTTP against a public index.
        "tag trending" -> tagTrending(args, koin.get<TagRepository>(), environment)

        // Recursive on purpose, and it is the whole design: every operation goes back through
        // this same `when`, so a batch cannot accept a command the CLI does not have or spell one
        // differently. `Batch.kt` knows nothing about Koin — it is handed this lambda.
        //
        // [sessions] is the *same* cache, which is what makes the claim about amortising the
        // session true: `authed` resolves through it, so the first operation that needs a session
        // pays for it and the rest do not. Passing a fresh one here would put a `revalidateSession`
        // round trip on every operation under `LOOPKY_SESSION`.
        "batch" -> batch(
            args,
            { operation -> dispatch(operation, identity, koin, environment, json, sessions) },
            BatchSinks({ line -> if (json) println(line) }, text, note),
        )

        // `update` and `completion` are deliberately absent: both are handled in `run` before
        // Koin starts, since neither may depend on an install healthy enough to load the FFI.
        // Both are still one function taking plain values, so the MCP-binding shape below is
        // unaffected.

        // The message stays one line. `--json` puts it in an `error.message`, and pasting a
        // 60-line usage block into a JSON string helps nobody parsing it; `--help` is where the
        // usage lives, and the human path prints it below. A near miss adds a clause, not a line.
        else -> throw CliError(ExitCode.Usage, args.unknownCommandMessage())
    }
}

/** Resolve the session once, before the command runs, so a missing one fails the same way everywhere. */
private suspend inline fun authed(
    sessions: SessionCache,
    identity: IdentityRepository,
    environment: CliEnvironment,
    block: (Session) -> CommandResult,
): CommandResult = block(sessions.require(identity, environment))

private fun Koin.identity() = get<IdentityRepository>()
private fun Koin.decks() = get<DeckRepository>()
private fun Koin.cards() = get<CardRepository>()

private fun emit(
    args: Args,
    env: CliEnvironment,
    command: String,
    result: CommandResult,
    notice: UpdateNotice,
) {
    if (args.has("json")) {
        println(successEnvelope(command, env.name, env.indexer, result.data, notice.available))
    } else if (result.text.isNotEmpty()) {
        println(result.text)
    }
    noteUpdate(notice)
}

private fun fail(
    args: Args,
    env: CliEnvironment,
    command: String,
    error: CliError,
    notice: UpdateNotice,
) {
    if (args.has("json")) {
        // The failure envelope goes to **stdout**, like a success: a caller parsing `--json` has to
        // be able to read the error out of the same stream, and splitting the two would make the
        // machine channel say nothing at all about half the outcomes.
        println(failureEnvelope(command, env.name, env.indexer, error, notice.available))
    } else {
        System.err.println("loopky: ${error.message}")
        // The command's own synopsis, not the manual: an agent capturing stderr reads the tail, and
        // hundreds of lines of manual buried the one that said what was wrong (#257, item 5).
        if (error.exitCode == ExitCode.Usage) commandFor(command)?.let { System.err.println("\n" + commandHelp(it)) }
    }
    noteUpdate(notice)
}

/**
 * The update notice: **stderr, once, whatever `--json` says** (#209). Never stdout, the machine
 * channel — and not suppressed under `--json` either, since stdout and stderr are two channels here
 * rather than one that switches off.
 */
private fun noteUpdate(notice: UpdateNotice) {
    val update = notice.available ?: return
    System.err.println("loopky: " + updateNotice(update, notice.installation))
}

private const val TAG = "Loopky/Cli"

/** Handled in [run] rather than in [dispatch]; see the note at its call site. */
private const val UPDATE_VERB = "update"

/** The same, for the one other command that must work on an install too broken to load the FFI. */
private const val COMPLETION_VERB = "completion"

/** And the surface table, which is a constant this binary was compiled with. */
private const val COMMANDS_VERB = "commands"

private val PRE_KOIN_VERBS = setOf(UPDATE_VERB, COMPLETION_VERB, COMMANDS_VERB)

/**
 * The two commands that run before Koin, and therefore before `libpubkycore` is loaded. Neither needs
 * a session, a homeserver or a native library, and both have a reason to work without one: `update`
 * is what you reach for when the install is *broken*, and `completion` prints a static string a shell
 * rc file will `eval` on a machine this binary may not even be shipped for.
 */
private suspend fun preKoin(args: Args, updates: Updates): CommandResult = when (args.verb) {
    COMPLETION_VERB -> completion(args)
    COMMANDS_VERB -> commandSurface()
    else -> update(args, updates.checker, updates.installation)
}

/**
 * Two numbers that move independently. [CLI_VERSION] is generated from `loopkyCliVersion` in
 * `gradle.properties`; [SCHEMA_VERSION] is the `--json` envelope's, and the one a caller branches on.
 */
private val VERSION = "loopky $CLI_VERSION (schema $SCHEMA_VERSION)"

/** `internal` so `CompletionTest` can check that every command in the table is documented here. */
internal val USAGE = """
    loopky — a headless Loopky client for decks and cards.

    USAGE
      loopky <command> [options]
      loopky <command> --help       that command's options
      loopky commands --json        the whole surface as JSON: verbs, operands, flags, exit codes

    IDENTITY
      login [--export] [--qr-out FILE] [--url-only] [--timeout SECONDS]
                                Print a QR code for Pubky Ring and wait for approval. --export also
                                prints the session secret for LOOPKY_SESSION. --timeout exits 13
                                when nobody approves in time.
      logout                    Forget the stored session.
      whoami                    Pubky, homeserver, capabilities, environment, session status.
      doctor [--homeserver <pubky>]
                                Check every host loopky needs through the configured proxy and
                                print the allowlist. No session needed. See NETWORK.

    DECKS
      deck list
      deck show <deckId>
      deck create --title T [--description D] [--tag T]... [--cover-url URL] [--cover-emoji E]
                  [--from-file F] [--check-images] [--dry-run] [--id DECKID] [--if-not-exists]
                  [--listen] [--speak] [--type] [--reverse] [--front-lang BCP47] [--back-lang BCP47]
                                --id with --if-not-exists is the idempotent form: an existing deck
                                is returned untouched (created: false). Without --if-not-exists an
                                existing id is refused. A language pair also tags the deck
                                ("spanish" plus "language"). --dry-run validates everything and
                                publishes nothing.
      deck edit <deckId> [--title T] [--description D] [--cover-url URL] [--cover-emoji E]
                  [--tag T]... [--clear-tags] [--clear-cover]
                  [--listen|--no-listen] [--speak|--no-speak] [--type|--no-type]
                  [--reverse|--no-reverse] [--front-lang BCP47] [--back-lang BCP47]
                                Metadata only; cards are untouched. An omitted flag leaves its field
                                alone, `--description=` clears it, --tag replaces the whole set.
      deck delete <deckId>
      deck sync <deckId>
      deck compact <deckId>     Fold away the holes card deletes leave in the chunk table.

    CARDS
      card list <deckId> [--limit N] [--cursor TOKEN] [--missing-image|--has-image]
                                --limit/--cursor page through large decks; --json carries
                                next_cursor while there is more.
      card add <deckId> --front F --back B [--front-image URL] [--back-image URL]
      card add <deckId> --from-file cards.tsv|cards.jsonl [--dry-run]
                                Written 100 cards per request.
      card edit <deckId> <cardId> [--front F] [--back B] [--front-image URL] [--back-image URL]
      card edit <deckId> --from-file edits.jsonl
                                Idempotent: re-run the same file to resume. Reports written /
                                skipped / failed per card.
      card rm <deckId> <cardId>
                                card add and card edit also take --check-images; see CARD IMAGES.

    IMPORT
      import <file|-> --title T [--separator auto|tab|comma|semicolon|pipe|dash|colon|blank|markdown]
                      [--description D] [--tag T]... [--resume]
                      [--front-lang BCP47] [--back-lang BCP47]
      import <deck.apkg> --title T [--front-field N|name] [--back-field N|name]
                      An Anki export; the two fields become front and back (numbers are 1-based).
      import <file> --dry-run [--json] [--check-images]
                      Report what would be published. Writes nothing, needs no session.

    DISCOVERY
      tag trending [--limit N]  Read the Nexus indexer. No session needed.

    UPDATE
      update                    Replace this binary with the newest release. Refuses (exit 11) on
                                Homebrew, .deb, containers and the jar, naming the right command.
      update --check            Ask without doing.

    AGENTS
      commands                  This surface as JSON (same as `loopky --help --json`).
      batch <file|->            Run many operations on one session — one JSON object per line,
                                {"argv": ["card", "add", "<deckId>", "--front", "a", "--back", "b"]},
                                with an optional "id" echoed back. Under --json each operation
                                streams its own line. Keeps going after a failure unless
                                --stop-on-error; exits with the first failure's code. Nothing rolls
                                back — re-run it (card add, card edit and deck create --id
                                --if-not-exists are idempotent).

      --json shape              One JSON object per result, the command's data always under "data":

                                  {"schema":1,"ok":true,"command":"card list","environment":"…",
                                   "indexer":"…","update_available":null,"data":{…}}

                                A failure is the same object with "ok":false and
                                "error":{"code","exit","message"}, also on stdout.

                                  card list   data.cards[], data.count, data.card_count,
                                              data.next_cursor. A card is {"id","front":{"text",
                                              "image":{"url",…}},"back":{…}} — front is an OBJECT.
                                  deck list   data.decks[], data.count
                                  deck show   data.deck
                                  card add    data.written, data.skipped, data.cards[],
                                              data.failures[], data.image_checks[],
                                              data.image_advice[]
                                  import      data.deck, data.cards_written, data.image_checks[],
                                              data.image_advice[]

    SHELL
      completion bash|zsh|fish  Print a completion script. Regenerate it after an upgrade.

                                  eval "${'$'}(loopky completion bash)"        # in ~/.bashrc
                                  loopky completion zsh > "${'$'}{fpath[1]}/_loopky"
                                  loopky completion fish > ~/.config/fish/completions/loopky.fish

    GLOBAL
      --json                    Machine-readable output on stdout. Stable, versioned schema.
      --dry-run                 Write nothing (import, deck create, card add).
      --env staging|production  Which network to talk to. Defaults to production.
      --no-update-check         Do not look for a newer release on this invocation.
      --verbose                 Debug logging on stderr.
      --help, --version

    ENVIRONMENT
      LOOPKY_SESSION            A session secret, read before the stored session. Mint it with
                                `loopky login --export` where a human can approve.
      LOOPKY_ENV                staging | production. --env wins.
      LOOPKY_CONFIG_HOME        Where state lives; defaults to ${'$'}XDG_CONFIG_HOME/loopky or the
                                platform's config directory. On macOS the session is in the
                                Keychain unless this or XDG_CONFIG_HOME is set. `loopky whoami`
                                reports both.
      LOOPKY_NO_UPDATE_CHECK    Never look for a newer release.

    NETWORK
      Behind an allowlist proxy, these hosts must be allowed (production):

        httprelay.pubky.app     login
        pkarr.pubky.app         finding a homeserver (pkarr.pubky.org works instead)
        pkarr.pubky.org
        homeserver.pubky.app    every deck read and write
        nexus.pubky.app         tag trending and indexer reads

      Recommended, for card pictures:

        upload.wikimedia.org    where Wikimedia images are served; --check-images HEADs them
        commons.wikimedia.org   finding a picture and its URL

      `loopky doctor` checks each one and prints the list for your homeserver and environment.
      Exit 14 means a proxy refused a host: allowlisting it is the fix, retrying is not.

    CARD FILES
      TSV:   front <TAB> back <TAB> front_image_url <TAB> back_image_url   (last two optional)
      JSONL: {"id":"…","front":"…","back":"…","front_image_url":"…","back_image_url":"…"}
             "id" is for `card edit`; an absent field is left unchanged, an explicit null clears.
             The nested shape `card list --json` emits (data.cards[]) is accepted too, so a deck
             can be read, edited with jq and written back.

    CARD TEXT
      A parenthesized aside is shown but never part of the answer — typing, Speak and Listen all
      drop it:   hola (informal)     ねこ (neko)     猫（ねこ）
      Put a register, sense or reading in brackets; otherwise it becomes part of the answer.

    CARD IMAGES
      A picture is an https URL; nothing is uploaded and nothing is fetched.
        - https:// only. http:// is refused — neither app renders it.
        - Wikimedia thumbnails exist only at 120, 250, 330, 500, 960, 1280 and 1920 px; any other
          width is a blank card. Or drop /thumb/ and the NNNpx- prefix for the original.
        - Only the final extension counts: …/Sign.svg/500px-Sign.svg.png is a PNG, …/Sign.svg is not.
        - From the imageinfo API, strip the ?utm_… query and use upload.wikimedia.org as the host.

      --check-images sends one HEAD per distinct URL and warns (never refuses) about anything that
      is not a 2xx image. Rate limits and timeouts are reported as unverified, not as wrong.
      --check-images-concurrency N (default 3, up to 16). Findings are in --json as image_checks;
      rule-based warnings that need no request are always in image_advice.

    EXIT CODES
      0 ok                      6 not found
      1 internal                7 storage full (terminal, never retried)
      2 usage                   8 environment mismatch
      3 not signed in           9 bad input
      4 session expired        10 no build for this host
      5 network                11 update found but not applied (a managed install)
                              12 the homeserver answered 5xx — worth retrying
                              13 login --timeout ran out before anyone approved
                              14 a proxy refused the host — allowlist it; retrying will not help
                              15 the certificate is not trusted — usually a proxy re-signing TLS

    NOTES
      The session can write /pub/loopky/ and nothing else: no posts, follows or profile edits.
      An .apkg's pictures are uploaded at full resolution against a 1 GB quota; --dry-run reports
      the total first.
""".trimIndent()
