package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.util.epochMillis
import com.github.jvsena42.loopky.util.localDayIndex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Limits the "update available" prompt to once per local day on both platforms.
 *
 * The day is spent when the prompt is *shown*, so accepting, declining and killing the app over it
 * all count the same.
 */
class UpdatePromptGate(
    private val appPreferences: AppPreferences,
    private val today: () -> Int = { localDayIndex(epochMillis()) },
) {
    private val lock = Mutex()

    /** True at most once per local day. Call it only when there is a prompt to show. */
    suspend fun tryAcquire(): Boolean = lock.withLock {
        val day = today()
        if (appPreferences.updatePromptDay.first() == day) return false
        appPreferences.setUpdatePromptDay(day)
        true
    }
}
