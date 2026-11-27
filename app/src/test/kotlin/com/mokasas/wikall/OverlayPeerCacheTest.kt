package com.mokasas.wikall

import org.junit.Assert.*
import org.junit.Test

class OverlayPeerCacheTest {
    @Test fun filtersOwnPublicOverlayAndUnreachableEndpoints() {
        val cache = OverlayPeerCache()
        val candidates = listOf("192.168.1.2", "192.168.1.3", "192.168.2.4",
            "8.8.8.8", OVERLAY_HOST, "not-an-address", "192.168.1.3")
        assertEquals(listOf("192.168.1.3"), cache.update(candidates, setOf("192.168.1.2"), 0L) {
            it.startsWith("192.168.1.")
        })
    }

    @Test fun missingSingleScanDoesNotEvictPeerButExpiryAndLinkLossDo() {
        val cache = OverlayPeerCache()
        val peer = "192.168.1.3"
        assertEquals(listOf(peer), cache.update(listOf(peer), emptySet(), 0L) { true })
        assertEquals(listOf(peer), cache.update(emptyList(), emptySet(), 30_000L) { true })
        assertEquals(listOf(peer), cache.update(listOf(peer), emptySet(), 90_000L) { true })
        assertEquals(listOf(peer), cache.update(emptyList(), emptySet(), 120_000L) { true })
        assertTrue(cache.update(emptyList(), emptySet(), 210_000L) { true }.isEmpty())
        cache.update(listOf(peer), emptySet(), 211_000L) { true }
        assertTrue(cache.update(emptyList(), emptySet(), 212_000L) { false }.isEmpty())
    }

    @Test fun cacheIsBoundedStableAndCanBeClearedOnRealmChange() {
        val cache = OverlayPeerCache()
        val first = cache.update((2..40).map { "192.168.1.$it" }, emptySet(), 0L) { true }
        assertEquals(12, first.size)
        assertEquals(first, cache.update(listOf("192.168.1.99"), emptySet(), 1L) { true })
        cache.clear()
        assertEquals(listOf("192.168.1.99"), cache.update(listOf("192.168.1.99"), emptySet(), 2L) { true })
    }
}
