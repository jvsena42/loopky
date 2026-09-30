package com.github.jvsena42.loopky.platform

import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.data.repository.DeckRepository
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.dateWithTimeIntervalSinceNow

/**
 * [BackgroundTasks] over `BGTaskScheduler`.
 *
 * **Unverified on a device.** A simulator registers the handlers but refuses every submission
 * (`BGTaskSchedulerErrorCodeUnavailable`), so no task has been observed running.
 *
 * iOS differs from WorkManager in two ways that shape this:
 * - The handler must be registered **before the app finishes launching**, hence [register] being
 *   called from the Koin bootstrap rather than lazily on first schedule.
 * - The system decides when — and whether — a processing task runs, so a pass may sit for days.
 *   That is acceptable for both jobs: re-hosting is opportunistic by design (#65 already covers
 *   the blobs the user actually looks at), and compaction only ever buys request count back.
 */
/*
 * The repositories arrive as providers, not instances, and that is load-bearing.
 *
 * `DeckRepositoryImpl` takes a `BackgroundTasks` (it schedules compaction and re-hosting), so
 * asking for a `DeckRepository` here closes a construction cycle:
 * `BackgroundTasks -> DeckRepository -> BackgroundTasks`. Koin's `SingleInstanceFactory` holds a
 * non-reentrant lock while it builds, so re-entering the same factory on the same thread does not
 * fail cleanly — on Kotlin/Native it segfaults, which is what `doInitKoin` did on every launch.
 *
 * Android never hits it: `AndroidBackgroundTasks` takes only a `Context`, and its WorkManager
 * workers resolve their repositories when they run. Deferring the `get()` to task-execution time
 * does the same thing here, and neither repository is touched before a task actually runs.
 */
@OptIn(ExperimentalForeignApi::class)
class IosBackgroundTasks(
    private val identityProvider: () -> IdentityRepository,
    private val decksProvider: () -> DeckRepository,
) : BackgroundTasks {

    private val identity: IdentityRepository get() = identityProvider()
    private val decks: DeckRepository get() = decksProvider()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Install the task handler. Must run before the app finishes launching, and the identifier
     * must also appear in `Info.plist` under `BGTaskSchedulerPermittedIdentifiers` — without it
     * `BGTaskScheduler` rejects both the registration and the submission.
     */
    fun register() {
        val registered = BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
            identifier = MEDIA_REHOST_TASK_ID,
            usingQueue = null,
        ) { task -> task?.let(::runSweep) }
        Log.d(TAG, "register: $MEDIA_REHOST_TASK_ID registered=$registered")

        val compaction = BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
            identifier = DECK_COMPACTION_TASK_ID,
            usingQueue = null,
        ) { task -> task?.let(::runCompaction) }
        Log.d(TAG, "register: $DECK_COMPACTION_TASK_ID registered=$compaction")
    }

    override fun scheduleMediaRehost() {
        val request = BGProcessingTaskRequest(MEDIA_REHOST_TASK_ID).apply {
            // Matches Android's UNMETERED + battery-not-low: a cloned Anki deck's media runs to
            // hundreds of MB. `requiresExternalPower` would be stricter than the Android side and
            // in practice means "only overnight while charging".
            setRequiresNetworkConnectivity(true)
            setRequiresExternalPower(false)
            setEarliestBeginDate(NSDate.dateWithTimeIntervalSinceNow(EARLIEST_BEGIN_SECONDS))
        }
        submit(request)
    }

    override fun scheduleDeckCompaction() {
        val request = BGProcessingTaskRequest(DECK_COMPACTION_TASK_ID).apply {
            setRequiresNetworkConnectivity(true)
            setRequiresExternalPower(false)
            setEarliestBeginDate(NSDate.dateWithTimeIntervalSinceNow(EARLIEST_BEGIN_SECONDS))
        }
        submit(request)
    }

    /**
     * `submitTaskRequest:error:` reports through its `NSError**` and never throws, so the error
     * pointer is the only way to see a refusal — an identifier missing from `Info.plist`, or
     * `BGTaskSchedulerErrorCodeUnavailable` on a simulator. A refusal is logged, never raised: the
     * caller is a clone or a delete, and failing to schedule must not fail it.
     */
    private fun submit(request: BGProcessingTaskRequest) = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val accepted = BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error.ptr)
        if (accepted) {
            Log.d(TAG, "submit: ${request.identifier} accepted")
        } else {
            Log.e(TAG, "submit: ${request.identifier} refused — ${error.value?.localizedDescription}")
        }
    }

    private fun runCompaction(task: BGTask) = run(task, ::scheduleDeckCompaction) {
        passOver(decks.decksPendingCompaction()) { id -> decks.compactDeck(id).map { it.complete } }
    }

    private fun runSweep(task: BGTask) = run(task, ::scheduleMediaRehost) {
        passOver(decks.decksPendingRehost()) { id -> decks.rehostPendingMedia(id).map { it.complete } }
    }

    /**
     * Run [work] as a `BGTask`: signed in first, and cancellable from the expiration handler.
     *
     * A submitted request is consumed once it runs and there is no `retry()`, so [reschedule] is
     * how a pass asks for another — but only when [work] says one is needed, mirroring the Android
     * workers' `retry()`/`success()`. Rescheduling unconditionally kept a processing task pending
     * forever, signed out or with nothing to do. A full quota never reschedules (§8.5).
     */
    private fun run(task: BGTask, reschedule: () -> Unit, work: suspend () -> Boolean) {
        val job = scope.launch {
            // runSuspendCatching, not runCatching: the expiration handler cancels this job, and a
            // plain runCatching would swallow that and go on to reschedule and report success —
            // both of which the handler has already done.
            val again = runSuspendCatching {
                identity.loadPersistedSession() != null && work()
            }.getOrElse {
                Log.e(TAG, "run: FAILED — ${it.message}", it)
                it.toErrorReason() != ErrorReason.StorageFull
            }
            if (again) reschedule()
            task.setTaskCompletedWithSuccess(true)
        }
        task.expirationHandler = {
            Log.w(TAG, "run: expired, cancelling")
            job.cancel()
            reschedule()
            task.setTaskCompletedWithSuccess(false)
        }
    }

    /** Whether any deck wants another pass. A 507 on one deck stops the whole pass without one. */
    private suspend fun passOver(pending: List<Deck>, pass: suspend (String) -> Result<Boolean>): Boolean {
        var unfinished = false
        for (deck in pending) {
            val complete = pass(deck.id).getOrElse { err ->
                if (err.toErrorReason() == ErrorReason.StorageFull) {
                    Log.e(TAG, "passOver: out of storage on ${deck.id} — giving up", err)
                    return false
                }
                Log.e(TAG, "passOver: ${deck.id} failed — ${err.message}", err)
                false
            }
            if (!complete) unfinished = true
        }
        return unfinished
    }

    private companion object {
        const val TAG = "Loopky/BgTasks"

        /** A few minutes out, so a clone's sweep is not competing with the clone itself. */
        const val EARLIEST_BEGIN_SECONDS = 300.0
    }
}
