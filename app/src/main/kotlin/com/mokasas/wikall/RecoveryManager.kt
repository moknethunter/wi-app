package com.mokasas.wikall

import android.os.Handler

/** Coalesces bursts of Android address changes into one recovery operation. */
internal class RecoveryManager(private val handler: Handler) {
    private var pending: Runnable? = null

    fun schedule(action: () -> Unit) {
        cancel()
        pending = Runnable {
            pending = null
            action()
        }.also { handler.postDelayed(it, 800) }
    }

    fun cancel() {
        pending?.let(handler::removeCallbacks)
        pending = null
    }
}
