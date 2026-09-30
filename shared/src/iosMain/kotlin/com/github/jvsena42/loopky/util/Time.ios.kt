package com.github.jvsena42.loopky.util

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitEra
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970

private const val MILLIS_PER_SECOND = 1000

internal actual fun epochMillis(): Long =
    (NSDate().timeIntervalSince1970 * MILLIS_PER_SECOND).toLong()

/**
 * Day ordinality within the era, from the current calendar — the Foundation equivalent of
 * `toEpochDay()`. The absolute value is irrelevant; only equality between two of them is used.
 */
internal actual fun localDayIndex(millis: Long): Int {
    val date = NSDate.dateWithTimeIntervalSince1970(millis.toDouble() / MILLIS_PER_SECOND)
    // `smaller`, not `unit`: that is what the generated binding calls the first parameter of
    // `ordinalityOfUnit:inUnit:forDate:`. Wrong, this does not compile for any iOS target.
    return NSCalendar.currentCalendar.ordinalityOfUnit(
        smaller = NSCalendarUnitDay,
        inUnit = NSCalendarUnitEra,
        forDate = date,
    ).toInt()
}

internal actual fun localMinuteOfDay(millis: Long): Int {
    val date = NSDate.dateWithTimeIntervalSince1970(millis.toDouble() / MILLIS_PER_SECOND)
    val parts = NSCalendar.currentCalendar.components(
        NSCalendarUnitHour or NSCalendarUnitMinute,
        fromDate = date,
    )
    return parts.hour.toInt() * MINUTES_PER_HOUR + parts.minute.toInt()
}

private const val MINUTES_PER_HOUR = 60
