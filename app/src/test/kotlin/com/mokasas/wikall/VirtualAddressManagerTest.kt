package com.mokasas.wikall

import org.junit.Assert.*
import org.junit.Test

class VirtualAddressManagerTest {
    private val client = OverlayRequest("A".repeat(64), "X".repeat(32), "99001", null)
    private val master = client.copy(number = HOST_NUMBER)
    private fun sample(address: String?, prefix: Int? = OVERLAY_PREFIX, running: Boolean = true) =
        EasyTierSnapshot(running, address, prefix, 1, 1)

    @Test fun plansAreBasedOnTheNativeAssignmentNotTheUserNumber() {
        val first = nativeAddressPlan(sample("10.126.125.7"), client)
        assertEquals(VirtualAddressPlan("10.126.125.7", 22, OVERLAY_NETWORK), first)
        assertEquals(first, nativeAddressPlan(sample("10.126.125.7"), client.copy(number = "99999")))
        assertNotEquals(first, nativeAddressPlan(sample("10.126.127.7"), client))
        assertEquals(OVERLAY_HOST, nativeAddressPlan(sample(OVERLAY_HOST), master)?.address)
    }

    @Test fun directoryDiscoveryUsesBoundedNativeRoutesAndKeepsLegacyCompatibility() {
        val local = "10.126.125.2"
        val routes = listOf(
            NativeRoute(1, 1, "10.126.125.8", 2),
            NativeRoute(2, 2, local, 1),
            NativeRoute(3, 3, "10.126.127.255", 1),
            NativeRoute(4, 4, "192.168.1.2", 1),
            NativeRoute(5, 5, "10.126.125.6", 1),
            NativeRoute(6, 6, "10.126.125.9", 3),
            NativeRoute(7, 7, "10.126.125.10", 4),
            NativeRoute(8, 8, "10.126.125.11", 1),
            NativeRoute(8, 8, "10.126.125.12", 1),
        )
        assertEquals(listOf(OVERLAY_HOST), overlayDirectoryCandidates(null, local, null))
        assertEquals(
            listOf(OVERLAY_HOST, "10.126.125.6", "10.126.125.8", "10.126.125.9"),
            overlayDirectoryCandidates(routes, local, null),
        )
        assertEquals(
            listOf("10.126.125.6", "10.126.125.8", "10.126.125.9", "10.126.125.10"),
            overlayDirectoryCandidates(routes, local, OVERLAY_HOST),
        )
    }

    @Test fun rejectsUnassignedUnsafeConflictingOrUnrelatedAddresses() {
        assertNull(nativeAddressPlan(null, client))
        assertNull(nativeAddressPlan(sample(null), client))
        assertNull(nativeAddressPlan(sample(OVERLAY_HOST), client))
        assertNull(nativeAddressPlan(sample("10.126.124.0"), client))
        assertNull(nativeAddressPlan(sample("10.126.127.255"), client))
        assertNull(nativeAddressPlan(sample("10.126.125.8", 24), client))
        assertNull(nativeAddressPlan(sample("192.168.0.5"), client))
        assertNull(nativeAddressPlan(sample("10.126.125.8", running = false), client))
        assertNull(nativeAddressPlan(sample("10.126.125.8"), master))
    }
}
