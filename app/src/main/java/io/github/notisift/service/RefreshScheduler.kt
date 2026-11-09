package io.github.notisift.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * When the drawer is redrawn. While the screen is on, bursts are coalesced into one refresh shortly after. While it
 * is off nobody can see the drawer, so every request until the screen turns on becomes one refresh then (or after
 * [SCREEN_OFF_MAX_MS] at the latest). Each redraw re-posts several rows, so this saves hundreds of updates a day
 * and the always-on display waking for each.
 *
 * A refresh that has started is never cancelled (that would leave rows half updated); a request that arrives while
 * one runs is scheduled again once it finishes, because the running one may already have read its snapshot.
 */
class RefreshScheduler(
    private val scope: CoroutineScope,
    private val isScreenOn: () -> Boolean,
    private val refresh: suspend () -> Unit,
) {
    /** The waiting or running refresh; null when idle. */
    private var pending: Job? = null
    private var running = false
    private var waitingForScreen = false
    private var again = false

    @Synchronized
    fun request() {
        when {
            running -> again = true
            isScreenOn() -> schedule(SOON_MS, waitForScreen = false)
            // One is already waiting: it will show this one too. Restarting the wait here would let a steady
            // trickle of notifications put the refresh off for ever.
            pending != null -> Unit
            else -> schedule(SCREEN_OFF_MAX_MS, waitForScreen = true)
        }
    }

    /** The screen came on: what was held back shows now. */
    @Synchronized
    fun screenOn() {
        if (waitingForScreen && pending != null && !running) schedule(0, waitForScreen = false)
    }

    /** Only called while idle or waiting, never while a refresh runs. */
    private fun schedule(delayMs: Long, waitForScreen: Boolean) {
        pending?.cancel()
        waitingForScreen = waitForScreen
        pending = scope.launch {
            delay(delayMs)
            val self = coroutineContext.job
            // Replaced by a newer schedule between the wait ending and here (another thread): that one runs instead.
            if (!start(self)) return@launch
            try {
                refresh()
            } finally {
                finish()
            }
        }
    }

    @Synchronized
    private fun start(self: Job): Boolean {
        if (pending !== self) return false
        running = true
        return true
    }

    @Synchronized
    private fun finish() {
        running = false
        pending = null
        if (again) {
            again = false
            request()
        }
    }

    companion object {
        const val SOON_MS = 1_500L
        const val SCREEN_OFF_MAX_MS = 15 * 60_000L
    }
}
