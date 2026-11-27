package com.mokasas.wikall

import org.junit.Assert.*
import org.junit.Test

class MobileNodePolicyTest {
    private val client = OverlayRequest("A".repeat(64), "X".repeat(32), "99001", null)
    @Test fun runningClientMayWaitForDhcpWithoutAnAddress() {
        assertTrue(mayRemainBootstrapNode(client, EasyTierSnapshot(true, null, null, 0, 0)))
        assertFalse(mayRemainBootstrapNode(client, null))
        assertFalse(mayRemainBootstrapNode(client, EasyTierSnapshot(false, null, null, 0, 0)))
        assertFalse(mayRemainBootstrapNode(client, EasyTierSnapshot(null, null, null, null, null)))
        assertFalse(mayRemainBootstrapNode(client, EasyTierSnapshot(true, "192.168.1.9", 24, 0, 0)))
        assertFalse(mayRemainBootstrapNode(client.copy(number = HOST_NUMBER), EasyTierSnapshot(true, null, null, 0, 0)))
    }
}
