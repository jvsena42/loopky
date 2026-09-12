package com.github.jvsena42.loopky.data.anki

import com.github.jvsena42.loopky.domain.model.DraftCardImage
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Desktop JVM `.apkg` reader.
 *
 * Identical to Android's but for where SQLite comes from: a desktop JVM has none in the platform,
 * so this is the one place `:shared` takes a JDBC driver. Everything else is [JvmApkgReader].
 *
 * Bulk Anki import is the most CLI-shaped job there is (#46), which is why this is on the critical
 * path for the JVM target rather than a nice-to-have.
 */
actual object ApkgReader {

    private val reader = JvmApkgReader(
        object : AnkiDbOpener {
            override fun <T> use(file: File, read: (AnkiDb) -> T): T =
                DriverManager.getConnection(sqliteReadOnlyUrl(file))
                    .use { connection -> read(JdbcAnkiDb(connection)) }
        },
    )

    actual fun canRead(header: ByteArray): Boolean = reader.canRead(header)

    actual suspend fun readNotes(
        path: String,
        mapping: ApkgFieldMapping?,
        compressImage: suspend (ByteArray, String) -> DraftCardImage,
    ): Result<ApkgImport> = reader.readNotes(path, mapping, compressImage)
}

/**
 * How a spooled collection is opened: read-only, and by a **URI** rather than by a path.
 *
 * `mode=ro` because nothing here writes, and a read-write open of a collection whose journal is
 * missing is how a driver ends up modifying a file we spooled out of somebody's archive.
 *
 * **`toURI()`, never `absolutePath`, and the reason is a write rather than a failed read.**
 * Everything after `jdbc:sqlite:` is a SQLite URI, and `sqlite3ParseUri` copies bytes verbatim
 * apart from `%HH`, `?` and `#`. So a path carrying one of those three is silently cut: measured on
 * sqlite-jdbc 3.53.4.0, `hash#1.sqlite` and `q?x.sqlite` both open the *truncated* name and leave a
 * stray 0-byte file behind, and `pct%41.sqlite` fails `SQLITE_CANTOPEN`. The stray file is the
 * finding — `mode=ro` was being swallowed into the fragment or the filename, so the old form could
 * **create** a file while asking to open one read-only. A space needs no escaping and was never
 * affected.
 *
 * **On Windows it was not broken, and saying otherwise was a guess.** A plain temp path carries no
 * `?`, `#` or `%`, and `winFullPathname` takes both `C:\Users\…` and the `/C:/…` form `toURI()`
 * produces. Checked by running `data.anki` on `windows-latest` against the old form: green. The
 * drive-letter colon and backslashes still are not a URI, so this was never *safe* there — but the
 * defect this fixes is the POSIX one above (#301).
 *
 * A function rather than an expression inside the opener so it can be tested directly. The file
 * this is called with is always a `createTempFile` spool, so the awkward path is the *system temp
 * directory's*, which no test driving `readNotes` can influence — that is why the obvious test,
 * importing from an awkward path, passes with or without the fix.
 */
internal fun sqliteReadOnlyUrl(file: File): String = "jdbc:sqlite:${file.toURI()}?mode=ro"

/**
 * [AnkiDb] over JDBC.
 *
 * Like `AndroidAnkiDb`, all this does is fetch rows — every query and every bit of interpretation
 * lives in the shared `ApkgCollection.kt`.
 */
internal class JdbcAnkiDb(private val connection: Connection) : AnkiDb {

    override fun query(sql: String, args: List<String>): List<AnkiRow> =
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeQuery().use { rs ->
                val columns = rs.metaData.columnCount
                buildList {
                    while (rs.next()) {
                        // Materialised per row rather than handing back the ResultSet: the shared
                        // reader walks the list more than once, and a forward-only cursor closes
                        // with its statement.
                        add(
                            JdbcRow(
                                (1..columns).map { index ->
                                    Triple(
                                        runCatching { rs.getString(index) }.getOrNull(),
                                        runCatching { rs.getBytes(index) }.getOrNull(),
                                        runCatching { rs.getInt(index) }.getOrDefault(0),
                                    )
                                },
                            ),
                        )
                    }
                }
            }
        }

    private class JdbcRow(private val values: List<Triple<String?, ByteArray?, Int>>) : AnkiRow {
        override fun text(index: Int): String? = values.getOrNull(index)?.first
        override fun blob(index: Int): ByteArray? = values.getOrNull(index)?.second
        override fun int(index: Int): Int = values.getOrNull(index)?.third ?: 0
    }
}
