package io.github.notisift.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshSchedulerTest {
    private class Harness(scope: TestScope, var screenOn: Boolean) {
        var refreshes = 0
        val scheduler = RefreshScheduler(scope.backgroundScope, { screenOn }) { refreshes++ }
    }

    @Test fun `with the screen on a burst becomes one refresh shortly after`() = runTest {
        val h = Harness(this, screenOn = true)
        repeat(12) { h.scheduler.request() }
        advanceTimeBy(RefreshScheduler.SOON_MS - 1)
        assertEquals(0, h.refreshes)
        advanceTimeBy(2)
        assertEquals(1, h.refreshes)
    }

    @Test fun `with the screen off nothing is redrawn until it comes on, then once`() = runTest {
        val h = Harness(this, screenOn = false)
        repeat(40) { h.scheduler.request(); advanceTimeBy(10_000) }
        assertEquals(0, h.refreshes)
        h.screenOn = true
        h.scheduler.screenOn()
        runCurrent()
        assertEquals(1, h.refreshes)
    }

    @Test fun `a steady trickle with the screen off still refreshes within the limit`() = runTest {
        val h = Harness(this, screenOn = false)
        repeat(20) { h.scheduler.request(); advanceTimeBy(60_000) }
        // 20 minutes of one notification a minute: the 15-minute limit was not pushed back by each one.
        assertEquals(1, h.refreshes)
    }

    @Test fun `screen on with nothing held back does nothing`() = runTest {
        val h = Harness(this, screenOn = true)
        h.scheduler.screenOn()
        advanceTimeBy(RefreshScheduler.SCREEN_OFF_MAX_MS + 1)
        assertEquals(0, h.refreshes)
    }

    @Test fun `screen on while a refresh is coming soon does not add another`() = runTest {
        val h = Harness(this, screenOn = true)
        h.scheduler.request()
        h.scheduler.screenOn()
        advanceTimeBy(RefreshScheduler.SCREEN_OFF_MAX_MS)
        assertEquals(1, h.refreshes)
    }

    @Test fun `a request while a refresh runs is not lost, and the running one is not cancelled`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var started = 0
        var finished = 0
        var screen = false
        val scheduler = RefreshScheduler(backgroundScope, { screen }) {
            started++
            if (started == 1) gate.await()
            finished++
        }
        scheduler.request()
        advanceTimeBy(RefreshScheduler.SCREEN_OFF_MAX_MS + 1)
        assertEquals(1, started)
        // Mid-run: a new notification arrives with the screen off, then the screen comes on and another arrives.
        scheduler.request()
        screen = true
        scheduler.screenOn()
        scheduler.request()
        runCurrent()
        assertEquals("the running refresh keeps going", 1, started)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, finished)
        advanceTimeBy(RefreshScheduler.SOON_MS + 1)
        assertEquals("one more refresh for what arrived mid-run", 2, finished)
        advanceTimeBy(RefreshScheduler.SCREEN_OFF_MAX_MS)
        assertEquals(2, finished)
    }

    @Test fun `a request while a refresh runs with the screen off waits for the screen again`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var started = 0
        val scheduler = RefreshScheduler(backgroundScope, { false }) {
            started++
            if (started == 1) gate.await()
        }
        scheduler.request()
        advanceTimeBy(RefreshScheduler.SCREEN_OFF_MAX_MS + 1)
        scheduler.request()
        gate.complete(Unit)
        runCurrent()
        advanceTimeBy(RefreshScheduler.SOON_MS + 1)
        assertEquals("held again, not run straight away", 1, started)
        scheduler.screenOn()
        runCurrent()
        assertEquals(2, started)
    }

    @Test fun `a request after the screen comes on replaces the long wait`() = runTest {
        val h = Harness(this, screenOn = false)
        h.scheduler.request()
        h.screenOn = true
        h.scheduler.request()
        advanceTimeBy(RefreshScheduler.SOON_MS + 1)
        assertEquals(1, h.refreshes)
        advanceTimeBy(RefreshScheduler.SCREEN_OFF_MAX_MS)
        assertEquals(1, h.refreshes)
    }
}
