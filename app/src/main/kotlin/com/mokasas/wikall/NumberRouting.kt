package com.mokasas.wikall

import java.util.UUID

internal fun validNumber(number: String): Boolean = number == HOST_NUMBER || number in userNumbers

internal data class RoutedSignal(val from: String, val to: String, val message: Signal)

internal class NumberRouting {
    private data class Route(val caller: String, val callee: String, var accepted: Boolean = false) {
        fun other(number: String): String? = when (number) {
            caller -> callee
            callee -> caller
            else -> null
        }
    }

    private val calls = mutableMapOf<UUID, Route>()
    private val completed = linkedSetOf<UUID>()

    fun receive(from: String, message: Signal, reachable: (String) -> Boolean): RoutedSignal? {
        if (!validNumber(from) || message.id == probeId) return null
        if (message.type == SignalType.INVITE) {
            val to = message.number ?: return null
            if (!validNumber(to) || to == from || message.id in calls || message.id in completed) return null
            if (!reachable(to)) {
                finish(message.id)
                return RoutedSignal(to, from, Signal(SignalType.UNAVAILABLE, message.id))
            }
            if (calls.values.any { it.other(from) != null || it.other(to) != null }) {
                finish(message.id)
                return RoutedSignal(to, from, Signal(SignalType.BUSY, message.id))
            }
            calls[message.id] = Route(from, to)
            return RoutedSignal(from, to, message.copy(number = from))
        }

        val route = calls[message.id] ?: return null
        val to = route.other(from) ?: return null
        when (message.type) {
            SignalType.ACCEPT -> {
                if (from != route.callee || route.accepted) return null
                route.accepted = true
            }
            SignalType.REJECT, SignalType.TIMEOUT, SignalType.BUSY -> {
                if (from != route.callee || route.accepted) return null
                finish(message.id)
            }
            SignalType.END -> finish(message.id)
            SignalType.OFFER, SignalType.RESTART -> if (!route.accepted || from != route.caller) return null
            SignalType.ANSWER, SignalType.REPAIR -> if (!route.accepted || from != route.callee) return null
            SignalType.ICE -> if (!route.accepted) return null
            else -> return null
        }
        return RoutedSignal(from, to, message)
    }

    fun hasAcceptedCall(number: String): Boolean = calls.values.any { it.accepted && it.other(number) != null }

    fun disconnected(number: String, missedRinging: ((UUID, String) -> Unit)? = null): RoutedSignal? {
        val call = calls.entries.firstOrNull { it.value.other(number) != null } ?: return null
        val other = checkNotNull(call.value.other(number))
        val id = call.key
        val reportMissed = call.value.callee == number && !call.value.accepted
        finish(id)
        if (reportMissed) missedRinging?.invoke(id, other)
        return RoutedSignal(number, other, Signal(SignalType.END, id))
    }

    fun clear() {
        calls.clear()
        completed.clear()
    }

    private fun finish(id: UUID) {
        calls.remove(id)
        completed.add(id)
        if (completed.size > 256) completed.remove(completed.first())
    }
}
