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
import com.github.jvsena42.loopky.cli.commands.import
import com.github.jvsena42.loopky.cli.commands.importDryRun
import com.github.jvsena42.loopky.cli.commands.login
import com.github.jvsena42.loopky.cli.commands.logout
import com.github.jvsena42.loopky.cli.commands.requireImageCheckOptions
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
        System.err.println("\n" + USAGE)
        // Repeated below the block for the same reason as in [fail]: the manual buries it.
        System.err.println("\nloopky: ${error.message}")
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
    if (args.words.isEmpty() || args.has("help")) {
        println(USAGE)
        return if (args.has("help")) ExitCode.Ok else ExitCode.Usage
    }

    Log.debugEnabled = args.has("verbose")
    val environment = CliEnvironment.resolve(args)
    val updates = Updates(UpdateChecker(environment.configHome), detectInstallation())

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
                val koin = startCli(environment)
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

        "deck list" -> authed(sessions, identity, environment) { deckList(koin.decks()) }
        "deck show" -> authed(sessions, identity, environment) { deckShow(args, koin.decks()) }
        "deck create" -> authed(sessions, identity, environment) { session ->
            deckCreate(args, koin.decks(), session, note, progress)
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
        if (error.exitCode == ExitCode.Usage) {
            System.err.println("\n" + USAGE)
            // And again underneath it. Sixty lines of manual scroll the one line that says what
            // was actually wrong off the top of the terminal, and an agent capturing stderr reads
            // the tail — so the whole output ended up saying nothing actionable (#257, item 5).
            System.err.println("\nloopky: ${error.message}")
        }
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

    IDENTITY
      login [--export] [--qr-out FILE] [--url-only] [--timeout SECONDS]
                                Print a QR code for Pubky Ring and wait for approval.
                                --export also prints the session secret for LOOPKY_SESSION.
                                --timeout bounds the wait and exits 13, which is what an
                                unattended caller needs: killing the process instead skips the
                                sweep that deletes a --qr-out file still holding a live auth URL.
      logout                    Forget the stored session.
      whoami                    Pubky, homeserver, capabilities, environment, and whether the
                                session is still accepted.

    DECKS
      deck list
      deck show <deckId>
      deck create --title T [--description D] [--tag T]... [--cover-url URL]
                  [--cover-emoji E] [--from-file F] [--check-images] [--dry-run]
                  [--id DECKID] [--if-not-exists]
                  [--listen] [--speak] [--type] [--reverse]
                  [--front-lang BCP47] [--back-lang BCP47]
                                --id publishes under an id you choose instead of a fresh one, so
                                a run that was killed mid-flight is addressable; with
                                --if-not-exists the deck that is already there is returned
                                untouched and the result says created: false. That pair is the
                                idempotent form — without it, an existing id is refused rather
                                than published over, because a publish replaces the whole chunk
                                table and would take the deck's cards with it.
                                A declared pair also labels the deck — "spanish" plus the
                                "language" umbrella — so someone learning it can find the deck.
                                Ordinary tags you can remove. A deck with no pair gets neither.
                                --dry-run runs all of that — the id check, this command’s own
                                card-file reader, every row, --check-images — and stops before the
                                publish. It is the pre-flight for a file you are about to publish
                                with; import --dry-run reads a different format.
      deck edit <deckId> [--title T] [--description D] [--cover-url URL] [--cover-emoji E]
                  [--tag T]... [--clear-tags] [--clear-cover]
                  [--listen|--no-listen] [--speak|--no-speak]
                  [--type|--no-type] [--reverse|--no-reverse]
                  [--front-lang BCP47] [--back-lang BCP47]
                                One manifest write; cards are never read or rewritten. A flag you
                                do not pass leaves that field alone, `--description=` clears it,
                                and --tag replaces the tag set rather than appending to it.
                                Naming a pair reconciles its language labels, which is also how a
                                deck published before they existed gains them: restate the pair.
      deck delete <deckId>
      deck sync <deckId>
      deck compact <deckId>     Fold away the holes card deletes leave in the chunk table.

    CARDS
      card list <deckId> [--limit N] [--cursor TOKEN] [--missing-image|--has-image]
                                Plain, it reads the whole deck. --limit/--cursor walk the manifest's
                                chunk table and fetch only the records a page needs, which is what
                                makes a 4,000-card deck affordable to iterate on; --json carries
                                next_cursor while there is more. The two filters narrow what comes
                                back and compose with both — there is no server-side filter to ask
                                for instead, so without --limit they save the output, not the fetch.
      card add <deckId> --front F --back B [--front-image URL] [--back-image URL]
                                Add --check-images to any of these to HEAD every distinct picture
                                URL first. Warns, never refuses; see CARD IMAGES.
      card add <deckId> --from-file cards.tsv|cards.jsonl [--dry-run]
                                A file is appended in groups of 100 — one chunk write and one
                                manifest patch each, not one of both per card — so a large batch
                                lands in seconds and reports N/M as it goes. --dry-run reads,
                                validates and dedupes the whole file and writes nothing.
      card edit <deckId> <cardId> [--front F] [--back B] [--front-image URL] [--back-image URL]
      card edit <deckId> --from-file edits.jsonl
                                A batch is idempotent, so re-running the same file is the way to
                                pick one back up: a row already holding what it asks for is
                                skipped, not rewritten. Everything is validated before anything is
                                written, one refused row does not end the batch, and the result —
                                on the failure envelope too — reports written / skipped / failed
                                with the card id and reason for each failure.
      card rm <deckId> <cardId>

    IMPORT
      import <file|-> --title T [--separator auto|tab|comma|semicolon|pipe|dash|colon|blank|markdown]
                      [--description D] [--tag T]... [--resume]
                      [--front-lang BCP47] [--back-lang BCP47]
      import <deck.apkg> --title T [--front-field N|name] [--back-field N|name]
                      An Anki export. Same command, same parser spine; the fields are named, so
                      --front-field/--back-field pick which two become the card. Numbers are
                      1-based, matching the labels an unnamed field is shown under.
      import <file> --dry-run [--json] [--check-images]
                      Report what would be published — for an .apkg, its field names with a
                      sample of each, the note count, the dropped-note breakdown and what its
                      pictures would spend. Writes nothing and needs no session.

    DISCOVERY
      tag trending [--limit N]  Read the Nexus indexer. No session, no capability.

    UPDATE
      update                    Replace this binary with the newest release, after checking the
                                digest published beside it. Refuses, with the right command, on a
                                Homebrew or .deb install, in a container, and on the jar.
      update --check            Ask without doing.

    AGENTS
      commands                  Print this surface as JSON on stdout: every verb, the operands it
                                takes and their arity, its flags, whether it needs a session, and
                                every exit code it can produce. Generated from the same table the
                                completion scripts are, so it cannot describe a surface this
                                binary does not have. `loopky --help --json` prints the same thing.
                                Needs no session and no network.

      --json shape              One line per result, and the command's own shape is always under
                                "data" — never at the top level:

                                  {"schema":1,"ok":true,"command":"card list","environment":"…",
                                   "indexer":"…","update_available":null,"data":{…}}

                                A failure is the same object with "ok":false and an "error"
                                {"code","exit","message"}, on stdout too, so one stream carries
                                both outcomes. What "data" holds, for the reads worth piping:

                                  card list   data.cards[], data.count, data.card_count,
                                              data.next_cursor. A card is {"id","front":{"text",
                                              "image":{"url","mime",…}},"back":{…}} — front is an
                                              OBJECT with .text, not a string.
                                  deck list   data.decks[], data.count
                                  deck show   data.deck
                                  card add    data.written, data.skipped, data.cards[],
                                              data.failures[], data.image_checks[],
                                              data.image_advice[]
                                  import      data.deck, data.cards_written, data.image_checks[],
                                              data.image_advice[]

                                Under --dry-run, deck create's data.created says whether the deck
                                WOULD be published: with data.dry_run it distinguishes all four
                                outcomes, so --id X --if-not-exists --dry-run answers "is this id
                                free" in one field.

                                A card file takes the flat {"front":"…"} shape AND that nested one,
                                so `card list --json | jq -c .data.cards[] > f` and
                                `card edit <deckId> --from-file f` is a round trip. See CARD FILES.

      batch <file|->            Run a file of operations against one session — one JSON object per
                                line, {"argv": ["card", "add", "deckid", "--front", "a",
                                "--back", "b"]}, with an optional "id" echoed back. The bare array
                                works too. Every homeserver command pays process start, the FFI
                                load and the session round trip; a *sequence of different*
                                commands is the one shape that had no amortised form, and it is
                                the shape an agent produces. Each line is parsed and dispatched by
                                the same code a command line is, so a batch can never accept
                                something the CLI does not.

                                Under --json each operation streams a line of its own —
                                "event":"operation", carrying that command's whole result — and
                                the final envelope summarises. A failed operation does not end the
                                run unless --stop-on-error; the exit code is the first failure's,
                                because session_expired and storage_full say different things
                                about re-running the file. Nothing is transactional and nothing
                                rolls back: re-run it. card add, card edit and
                                deck create --id --if-not-exists are all idempotent.

    SHELL
      completion bash|zsh|fish  Print a completion script on stdout, generated from this binary's
                                own command table — so it cannot describe a surface the binary
                                does not have. Nothing it offers touches the network: a deck id
                                would be a homeserver round trip on a keypress.

                                  eval "${'$'}(loopky completion bash)"        # in ~/.bashrc
                                  loopky completion zsh > "${'$'}{fpath[1]}/_loopky"
                                  loopky completion fish > ~/.config/fish/completions/loopky.fish

                                Regenerate it after an upgrade; a stale script offers flags that
                                are refused.

    GLOBAL
      --json                    Machine-readable output on stdout. Stable, versioned schema.
      --dry-run                 Read and report; write nothing. On import, deck create and card add
                                — each through its own path, so what it reports is what that
                                command would do.
      --env staging|production  Which network to talk to. Defaults to production.
      --no-update-check         Do not look for a newer release on this invocation.
      --verbose                 Debug logging on stderr.
      --help, --version

    ENVIRONMENT
      LOOPKY_SESSION            A session secret. Read *before* the stored session, and the only
                                way in on a sandbox that has no stored one. Mint it with
                                `loopky login --export` on a machine with a human at it.
      LOOPKY_ENV                staging | production. --env wins. Defaults to production.
      LOOPKY_CONFIG_HOME        Where state lives. Defaults to ${'$'}XDG_CONFIG_HOME/loopky. On macOS
                                the session is in the login Keychain instead — unless this or
                                XDG_CONFIG_HOME is set, either of which keeps everything under the
                                directory it names. `loopky whoami` reports both.
      LOOPKY_NO_UPDATE_CHECK    Set to anything to never look for a newer release. The check is
                                cached for a day, runs alongside the command, and can never fail
                                it — but a pipeline that wants no surprises can switch it off.

    CARD FILES
      TSV:   front <TAB> back <TAB> front_image_url <TAB> back_image_url   (last two optional)
      JSONL: {"id":"…","front":"…","back":"…","front_image_url":"…","back_image_url":"…"}
             "id" is for `card edit`; a field that is absent is left unchanged.

             A row may also be a card in the shape `card list --json` emits —
             {"id":"…","front":{"text":"…","image":{"url":"…"}},"back":{…}} — so a deck can be
             read, edited with jq and fed straight back. Absent still means unchanged; an
             explicit null clears. An image with no url is a homeserver blob this format cannot
             name, so it is left alone and reported.

             That shape comes out of the envelope at data.cards[], not at the top level. See
             "--json shape" under AGENTS for the whole of it.

    CARD IMAGES
      A card picture is a URL. Nothing is uploaded and no media quota is spent — but nothing is
      fetched either, so this client cannot tell you the picture loads. It can only tell you what
      is knowably wrong, and these two account for most of it:

        https:// only.  Android and iOS both refuse cleartext, so an http:// address is a card
                        whose picture cannot render on either. Refused, not stored.

        Wikimedia serves thumbnails at 120, 250, 330, 500, 960, 1280 and 1920 px and answers 400
        for every other width, so .../thumb/…/800px-Name.jpg is a blank card on both apps. Drop
        the /thumb/ segment and the NNNpx- prefix to get the full-size original, which is always
        served. Warned about on stderr, never fatal — the list is theirs to change.

        Only the FINAL extension is judged. Commons renders a TIFF or an SVG source to a raster
        thumbnail and keeps the source extension in the middle of the URL, so
        …/Cell.tif/lossy-page1-500px-Cell.tif.jpg and …/Sign.svg/500px-Sign.svg.png are both
        ordinary pictures. …/Cell.tif and …/Sign.svg, ending there, are not.

        From the imageinfo API, strip the ?utm_source=…&utm_campaign=imageinfo query it appends to
        url and thumburl, and rewrite the thumb.wikimedia.org host it hands back to
        upload.wikimedia.org — the rules above are written for that one.

      Beyond that, prefer a host that serves images to anyone: some refuse an unfamiliar client
      outright, and the result is the same blank card with nothing reporting it.

      --check-images asks. One HEAD per DISTINCT URL, on deck create, card add, card edit and
      import (including --dry-run, where it is worth the most). It reports the status and the content type of
      everything that is not a 2xx image — a dead link, a renamed file, a host refusing an
      unfamiliar client, or a .stl behind a perfectly ordinary-looking address. Opt-in because it
      is the only flag here that makes requests of its own, and it warns rather than refusing: a
      host having a bad minute must not be able to fail an import. Findings also travel in --json
      as image_checks.

      It separates WRONG from COULD NOT BE CHECKED, and the difference is the whole of its
      usefulness at scale. A 429, a timeout or a 5xx says nothing about the picture, so it is
      counted apart and marked unverified in --json rather than folded into "look wrong". Requests
      run 3 at a time and a 429 is retried with backoff, because at eight in flight this check
      rate-limited itself into 432 false findings on one 475-picture deck, burying the run's one
      real finding under them. Neither bucket prints more than 20 lines; --json carries them all.
      --check-images-concurrency N (up to 16) is there for a host that is not Wikimedia:
      against Wikimedia, raising it measured slower as well as noisier — 250 URLs answer
      clean in about 80 seconds as it stands.

      What a rule can say WITHOUT asking any host — an undecodable format, a thumbnail width
      Wikimedia does not serve — travels separately, as image_advice, on deck create, card add,
      card edit and import. Reported whether or not --check-images was passed, which is why it is
      two arrays rather than one: they answer different questions and only one of them is opt-in.
      One row per DISTINCT URL, as above, each listing every card and side it is on:
      {"url": "…", "where": ["Card 201 front image", …], "advice": "…"}. On stderr it is printed
      last, after everything the network had to say, and capped at 20 entries like the buckets
      above.

    EXIT CODES
      0 ok                      6 not found
      1 internal                7 storage full (507 — terminal, never retried)
      2 usage                   8 environment mismatch
      3 not signed in           9 bad input
      4 session expired        10 no build for this host
      5 network                11 update found but not applied (a managed install)
                              12 the homeserver answered 5xx — not your input, and worth retrying
                              13 login --timeout ran out before anyone approved

    NOTES
      Sessions are stored as a mode-0600 file, not in an OS keyring. libsecret is usually absent
      on the headless box this is built for, so a keyring default would fail exactly where the
      tool is meant to work. What is stored is a capability-scoped, expiring session — never a
      secret key, which never leaves Pubky Ring.

      This client asks Ring for /pub/loopky/:rw and nothing else. It therefore cannot post,
      follow, or edit a profile under any bug or any prompt injection, and there is no announce
      flag to pass.

      A newer release is reported on stderr and in the --json envelope's `update_available`,
      never acted on by itself. The check is one cached-for-a-day HTTPS GET against the same
      release page the installer uses, so it adds no host to an allowlist, and a check that fails
      is silent rather than fatal.

      An .apkg's pictures are the one thing this tool uploads bytes for, and it uploads them at
      full resolution — it ships no image codec, where the apps shrink every picture to 1024px
      JPEG. That is spent against a 1 GB homeserver quota nothing can read back, so `--dry-run`
      reports the total first. An .apkg's own deck description and note tags are reported and
      never adopted; pass them back as --description / --tag if they are right.
""".trimIndent()
