package com.mokasas.wikall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayLinkPlanTest {
    @Test
    fun unavailablePhysicalLinksPreventStartingAnOverlay() {
        val plan = OverlayLinkPlan.select(emptyList(), "192.168.1.2", emptyList()) {
            error("Peer reachability requires a physical link")
        }

        assertNull(plan)
    }

    @Test
    fun keepsReachablePeersWithoutDuplicatingThePreferredEndpoint() {
        val plan = requireNotNull(OverlayLinkPlan.select(
            listeners = listOf("192.168.1.3", "192.168.1.3", "172.16.0.1"),
            preferredPeer = "192.168.1.2",
            discoveredPeers = listOf("192.168.2.4", "192.168.1.2", "172.16.0.3", "172.16.0.3"),
            reachable = { it != "192.168.2.4" },
        ))

        assertEquals(listOf("172.16.0.1", "192.168.1.3"), plan.listeners)
        assertEquals("192.168.1.2", plan.preferredPeer)
        assertEquals(listOf("172.16.0.3"), plan.otherPeers)
    }
}
