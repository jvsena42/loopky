package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.util.Log
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A string-keyed store held in one JSON file, mode 0600 — the desktop stand-in for the
 * `SharedPreferences` / `EncryptedSharedPreferences` pair the Android stores sit on.
 *
 * Two instances, not one, so the line Android draws between a preference and a credential
 * survives: [ConfigHome] holds a `preferences.json` and a `secrets.json`. Both are 0600 — the
 * split is so that a `preferences.json` stays a file a human can safely open, read and hand to a
 * bug report, which is not true of the other one. What neither reproduces is encryption at rest;
 * see [ConfigHome] for why that is the deliberate choice on a headless box rather than an
 * oversight.
 *
 * Values are cached in memory and the whole file is rewritten on every change: this holds a
 * session, a handful of preferences and a review journal, not a database. The rewrite is atomic
 * (temp file, then `ATOMIC_MOVE`) so a process killed mid-write leaves the previous contents
 * rather than a truncated file — which matters, because one of the things kept here is the
 * journal of reviews that have not reached the homeserver.
 */
internal class JsonFileStore(internal val file: Path) {

    private val lock = ReentrantLock()
    private var cache: MutableMap<String, String>? = null

    fun string(key: String): String? = lock.withLock { load()[key] }

    fun set(key: String, value: String) = lock.withLock {
        val map = load()
        if (map[key] == value) return@withLock
        map[key] = value
        persist(map)
    }

    fun remove(key: String) = lock.withLock {
        val map = load()
        if (map.remove(key) == null) return@withLock
        persist(map)
    }

    private fun load(): MutableMap<String, String> = cache ?: read().also { cache = it }

    private fun read(): MutableMap<String, String> {
        if (!Files.exists(file)) return mutableMapOf()
        // A file we can no longer decode is treated as empty rather than fatal, matching the
        // Android vaults: the stored values are gone either way, and the choice is between a
        // client that starts signed out and one that cannot start.
        return runCatching {
            json.decodeFromString<Map<String, String>>(Files.readString(file)).toMutableMap()
        }.getOrElse {
            Log.w(TAG, "$file is unreadable — starting from empty", it)
            mutableMapOf()
        }
    }

    /**
     * **Restricted before it holds anything**, which is not what this did before (#301).
     *
     * The temp file used to be created with no attributes and narrowed after `writeString`, so the
     * secret existed at the ambient mode for the length of a write. That was only ever safe by
     * accident — `createTempFile` happens to make 0600 on POSIX — and on Windows, where nothing
     * narrowed it at all, the accident was the whole guarantee. Same order `TerminalQr.writePng`
     * states: permissions first, content second, so the readable window never exists.
     */
    private fun persist(map: Map<String, String>) {
        ConfigHome.prepare(file.parent)
        val temp = OwnerOnly.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        runCatching {
            Files.writeString(temp, json.encodeToString(map))
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure {
            Files.deleteIfExists(temp)
            throw it
        }
    }

    private companion object {
        const val TAG = "Loopky/JsonFileStore"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Non-secret settings — the desktop counterpart of Android's plain `SharedPreferences`. */
internal fun preferencesStore(home: Path): JsonFileStore =
    JsonFileStore(ConfigHome.prepare(home).resolve("preferences.json"))

/**
 * The session secret, a held key and the signup token — Android's `SECRETS_SERVICE_NAME` vault.
 * Separate from [preferencesStore] so that clearing preferences cannot discard a credential.
 */
internal fun secretsStore(home: Path): JsonFileStore =
    JsonFileStore(ConfigHome.prepare(home).resolve("secrets.json"))
