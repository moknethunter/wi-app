package com.mokasas.wikall

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Service instances share the worker because EasyTier has one registry per process. */
internal class VpnLifecycleQueue(
    private val worker: ScheduledThreadPoolExecutor = processWorker,
) {
    private val generation = AtomicLong()
    private var pendingPoll: ScheduledFuture<*>? = null

    fun isCurrent(command: Long): Boolean = generation.get() == command

    @Synchronized
    fun replace(action: (Long) -> Unit) {
        val command = invalidate()
        worker.execute {
            if (isCurrent(command)) action(command)
        }
    }

    @Synchronized
    fun poll(command: Long, delay: Long, action: () -> Unit) {
        if (!isCurrent(command)) return
        pendingPoll = worker.schedule({
            if (isCurrent(command)) action()
        }, delay, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun close(cleanup: () -> Unit) {
        invalidate()
        worker.execute(cleanup)
    }

    private fun invalidate(): Long {
        val command = generation.incrementAndGet()
        pendingPoll?.cancel(false)
        pendingPoll = null
        return command
    }

    private companion object {
        val processWorker = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "WiKall-VPN")
        }.apply {
            removeOnCancelPolicy = true
        }
    }
}
