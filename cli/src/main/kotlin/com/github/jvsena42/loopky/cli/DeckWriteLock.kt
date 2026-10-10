package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.cli.commands.DRY_RUN_FLAG
import com.github.jvsena42.loopky.data.storage.ConfigHome
import kotlinx.coroutines.delay
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * One `loopky` write at a time per deck, across processes on this machine.
 *
 * A deck's manifest is one record rewritten whole, and the mutex that serializes those writes
 * lives inside a process. Two invocations each read the manifest, patch it and write it back, and
 * the later write drops what the earlier one added — both exit 0. That was a sentence in the
 * agent skill and nothing else; an OS file lock under the config home makes the second wait.
 *
 * **Best effort, and it says so by staying out of the way.** It cannot see another machine, or
 * a process with a different `LOOPKY_CONFIG_HOME`, and a config home that cannot hold a lock
 * file (read-only, a sandbox) runs the command unlocked rather than failing a write over
 * bookkeeping. The lock dies with its process, so a killed run leaves nothing to clean up.
 */
internal object DeckWriteLock {

    /** The deck [args] is about to write, or null for a read, a dry run or a command with no deck yet. */
    fun target(args: Args): String? {
        if (args.has(DRY_RUN_FLAG)) return null
        val deckId = when (args.verb) {
            in WRITE_VERBS -> args.word(2)
            // A minted id cannot collide; a supplied one is how two retries of one create meet.
            "deck create" -> args.option("id")?.trim()
            else -> null
        }
        return deckId?.takeIf { FILE_SAFE.matches(it) }
    }

    suspend fun <T> holding(
        deckId: String?,
        onNote: (String) -> Unit,
        home: Path = ConfigHome.resolve(),
        block: suspend () -> T,
    ): T {
        val channel = deckId?.let { open(home, it) } ?: return block()
        return channel.use {
            val lock = acquire(it, deckId, onNote)
            try {
                block()
            } finally {
                runCatching { lock?.release() }
            }
        }
    }

    private fun open(home: Path, deckId: String): FileChannel? =
        try {
            val dir = ConfigHome.prepare(home.resolve(LOCK_DIR))
            FileChannel.open(dir.resolve("$deckId.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        } catch (@Suppress("SwallowedException") unavailable: IOException) {
            null
        } catch (@Suppress("SwallowedException") unavailable: SecurityException) {
            null
        }

    /** Null when the lock cannot be asked for at all; the command then runs without one. */
    private suspend fun acquire(channel: FileChannel, deckId: String, onNote: (String) -> Unit): FileLock? {
        var announced = false
        while (true) {
            val lock = try {
                channel.tryLock()
            } catch (@Suppress("SwallowedException") heldInThisProcess: OverlappingFileLockException) {
                null
            } catch (@Suppress("SwallowedException") unavailable: IOException) {
                return null
            }
            if (lock != null) return lock
            if (!announced) {
                onNote("loopky: another loopky command is writing deck $deckId - waiting for it to finish.")
                announced = true
            }
            delay(POLL_MILLIS)
        }
    }

    private val WRITE_VERBS = setOf(
        "deck edit", "deck delete", "deck compact",
        "card add", "card edit", "card rm", "card mv", "card reorder",
    )
    private val FILE_SAFE = Regex("[A-Za-z0-9_-]{1,64}")
    private const val LOCK_DIR = "locks"
    private const val POLL_MILLIS = 250L
}
