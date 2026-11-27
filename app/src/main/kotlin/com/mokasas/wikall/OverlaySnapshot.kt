package com.mokasas.wikall

/** Observed engine state; null fields mean unavailable evidence, not zero or success. */
internal data class OverlaySnapshot(
    val running: Boolean?,
    val address: String?,
    val prefix: Int?,
    val peers: Int?,
    val routes: Int?,
    val routeDetails: List<NativeRoute>? = null,
    val failureCategory: String? = null,
)

// Transitional source compatibility for existing policies and native tests.
internal typealias EasyTierSnapshot = OverlaySnapshot
