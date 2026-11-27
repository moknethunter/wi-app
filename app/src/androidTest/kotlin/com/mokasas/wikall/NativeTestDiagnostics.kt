package com.mokasas.wikall

/** Diagnostics for synthetic instrumentation fixtures only. No raw native JSON or errors. */
internal fun nativeTestState(engine: EasyTierEngine, name: String): String = try {
    val state = engine.snapshot(name)
    if (state == null) "$name: missing"
    else "$name: running=${state.running}, address=${state.address}, prefix=${state.prefix}, " +
        "peers=${state.peers}, routes=${state.routes}, failure=${state.failureCategory}, " +
        "routeDetails=${state.routeDetails?.take(8)}"
} catch (failure: Exception) {
    "$name: snapshot unavailable category=${nativeFailureCategory(failure.message) ?: "unknown"}"
}
