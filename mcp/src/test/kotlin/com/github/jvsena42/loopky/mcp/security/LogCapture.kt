package com.github.jvsena42.loopky.mcp.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/** Everything logback's root logger sees, at every level, until [stop]. */
class LogCapture private constructor(private val root: Logger, private val appender: ListAppender<ILoggingEvent>) {
    fun lines(): List<String> = appender.list.map { event ->
        event.formattedMessage + (event.throwableProxy?.let { " ${it.className}: ${it.message}" } ?: "")
    }

    fun stop() {
        root.detachAppender(appender)
        appender.stop()
    }

    companion object {
        fun start(): LogCapture {
            val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            root.level = Level.TRACE
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            root.addAppender(appender)
            return LogCapture(root, appender)
        }
    }
}
