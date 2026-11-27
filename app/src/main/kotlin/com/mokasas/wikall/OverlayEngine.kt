package com.mokasas.wikall

/**
 * Application-owned native engine port, independent of Android UI and services.
 * Calls are synchronous; implementations serialize operations.
 * Start is a request, not proof of readiness. Null means unavailable telemetry.
 * The caller owns the TUN descriptor and keeps it alive while attached.
 */
internal interface OverlayEngine {
    fun start(configuration: String)
    fun attachTun(instance: String, fd: Int)
    fun stopAll()
    fun snapshot(instance: String): EasyTierSnapshot?
}
