package com.mokasas.wikall

import org.junit.Assert.*
import org.junit.Test

class RelayTopologyTest {
    @Test fun nativeNextHopDistinguishesDirectAndRelayedRoutes() {
        val direct = NativeRoute(10L, 10L, null, 1)
        val first = NativeRoute(20L, 10L, null, 2)
        val second = NativeRoute(30L, 10L, null, 2)
        assertEquals(RelayTopology(1, 2, 1),
            summarizeRelayTopology(listOf(direct, first, second, first)))
    }

    @Test fun unknownAndConflictingDataAreNotReportedAsZero() {
        assertNull(summarizeRelayTopology(null))
        assertEquals(RelayTopology(0, 0, 0), summarizeRelayTopology(emptyList()))
        assertNull(summarizeRelayTopology(listOf(
            NativeRoute(20, 10, null, 2), NativeRoute(20, 11, null, 2))))
    }

    @Test fun productionProfilePermitsOnlyItsNamedRealmAndDisablesUpnp() {
        val config = OverlayRequest("A".repeat(64), "X".repeat(32), HOST_NUMBER, null).configuration()
        assertTrue("private_mode = true" in config)
        assertTrue("enable_encryption = true" in config)
        assertTrue("disable_upnp = true" in config)
        assertTrue("disable_relay_data = false" in config)
        assertTrue("p2p_only = false" in config)
        assertTrue("relay_network_whitelist = \"WiKall-${"A".repeat(24)}\"" in config)
    }
}
