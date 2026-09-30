package com.github.jvsena42.loopky.platform

import com.github.jvsena42.loopky.util.Log

/**
 * [BackgroundTasks] for a process that ends when its command does.
 *
 * Both jobs are *deferred* work — a media re-host pass and a chunk-compaction pass, asked for by
 * repositories on the way past and performed when the platform judges the moment suitable. A CLI
 * invocation has no such moment: there is no later, and the constraints the Android scheduler
 * enforces (unmetered network, healthy battery) are exactly the ones nobody consented to spending
 * inline in the middle of an import.
 *
 * So this records the ask and drops it. Neither job is required for correctness — a clone's media
 * stays pinned to the source author's blobs and reads fine (#53), and a deck with holes in its
 * chunks is a deck with holes, which every reader already handles (#51). What the CLI owes the
 * user instead is an explicit way to run them, which is `loopky deck compact <deckId>`, not a
 * surprise pass in the middle of something else.
 */
class InlineBackgroundTasks : BackgroundTasks {

    override fun scheduleMediaRehost() {
        Log.d(TAG, "media re-host requested; a headless invocation has no deferred window — skipped")
    }

    override fun scheduleDeckCompaction() {
        Log.d(TAG, "deck compaction requested; a headless invocation has no deferred window — skipped")
    }

    private companion object {
        const val TAG = "Loopky/BackgroundTasks"
    }
}
