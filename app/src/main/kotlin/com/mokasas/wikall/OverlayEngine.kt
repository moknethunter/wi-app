package com.mokasas.wikall

internal fun interface OverlaySocketPolicy {
    /** Negative rejects, zero is an Android Network binding, positive is a local interface index. */
    fun prepare(fd: Int, source: String): Int
}

/**
 * Application-owned overlay port, independent of Android UI, JNI and TOML.
 * Implementations serialize synchronous operations. Start is a request, not
 * proof of readiness. Null means unavailable telemetry. The caller owns the
 * TUN descriptor while attached. Stop affects the entire engine registry.
 */
internal interface OverlayEngine {
    fun start(
        request: OverlayRequest,
        instance: String = OVERLAY_INSTANCE,
        socketPolicy: OverlaySocketPolicy? = null,
    )
    fun attachTun(instance: String, fd: Int)
    fun stopAll()
    fun snapshot(instance: String): OverlaySnapshot?
}
