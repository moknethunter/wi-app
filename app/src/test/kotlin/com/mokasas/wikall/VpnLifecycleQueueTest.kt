package com.mokasas.wikall

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnLifecycleQueueTest {
    private val worker = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }

    @After
    fun closeWorker() {
        worker.shutdownNow()
    }

    @Test
    fun replacementInvalidatesRunningCommandAndSkipsSupersededCommand() {
        val queue = VpnLifecycleQueue(worker)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val events = mutableListOf<String>()

        queue.replace { command ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            if (queue.isCurrent(command)) events += "stale result"
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        queue.replace { events += "superseded operation" }
        queue.replace { events += "replacement" }
        release.countDown()
        drain()

        assertEquals(listOf("replacement"), events)
    }

    @Test
    fun closingCancelsPendingHealthAndOrdersCleanupBeforeNextService() {
        val previous = VpnLifecycleQueue(worker)
        val replacement = VpnLifecycleQueue(worker)
        val events = mutableListOf<String>()

        previous.replace { command ->
            previous.poll(command, 60_000) { events += "stale health" }
        }
        drain()
        previous.close { events += "cleanup" }
        replacement.replace { events += "start" }
        drain()

        assertEquals(listOf("cleanup", "start"), events)
        assertFalse(worker.queue.any())
    }

    private fun drain() {
        worker.submit {}.get(5, TimeUnit.SECONDS)
    }
}
