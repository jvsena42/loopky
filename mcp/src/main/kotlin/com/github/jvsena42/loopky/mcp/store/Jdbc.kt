package com.github.jvsena42.loopky.mcp.store

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant

internal fun Connection.exec(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { statement ->
        statement.bind(params)
        statement.executeUpdate()
    }

internal fun <T> Connection.query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bind(params)
        statement.executeQuery().use { rows ->
            buildList { while (rows.next()) add(map(rows)) }
        }
    }

internal fun <T> Connection.queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
    query(sql, *params, map = map).firstOrNull()

private fun PreparedStatement.bind(params: Array<out Any?>) {
    params.forEachIndexed { index, value ->
        when (value) {
            null -> setNull(index + 1, Types.NULL)
            is Instant -> setLong(index + 1, value.toEpochMilli())
            is ByteArray -> setBytes(index + 1, value)
            is Int -> setInt(index + 1, value)
            is Long -> setLong(index + 1, value)
            else -> setString(index + 1, value.toString())
        }
    }
}

internal fun ResultSet.instant(column: String): Instant = Instant.ofEpochMilli(getLong(column))

internal fun ResultSet.instantOrNull(column: String): Instant? {
    val value = getLong(column)
    return if (wasNull()) null else Instant.ofEpochMilli(value)
}
