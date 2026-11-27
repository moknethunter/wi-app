package com.mokasas.wikall

import org.junit.Assert.*
import org.junit.Test

class OverlayHealthTest {
    private val ip = "10.1.2.3"

    @Test fun absentAndStoppedSnapshotsNeverLookReady() {
        assertEquals(OverlayHealthState.UNKNOWN, assessOverlayHealth(null, ip, 24).state)
        assertEquals(OverlayHealthState.UNKNOWN, assessOverlayHealth(EasyTierSnapshot(null, ip, 24, 1, 1), ip, 24).state)
        assertEquals(OverlayHealthState.STOPPED, assessOverlayHealth(EasyTierSnapshot(false, ip, 24, 1, 1), ip, 24).state)
    }

    @Test fun addressAndPrefixMustMatch() {
        val sample = EasyTierSnapshot(true, ip, 24, 2, 3)
        assertEquals(OverlayHealthState.TUN_MISMATCH, assessOverlayHealth(sample, ip, 22).state)
        assertEquals(OverlayHealthState.TUN_MISMATCH, assessOverlayHealth(sample, "10.1.2.4", 24).state)
        assertEquals(OverlayHealthState.UNKNOWN, assessOverlayHealth(sample.copy(address = null), ip, 24).state)
        assertEquals(OverlayHealth(OverlayHealthState.PEERS_REPORTED, 2, 3), assessOverlayHealth(sample, ip, 24))
        assertEquals(OverlayHealthState.NO_PEERS, assessOverlayHealth(sample.copy(peers = 0), ip, 24).state)
        assertEquals(OverlayHealthState.RUNNING, assessOverlayHealth(sample.copy(peers = null), ip, 24).state)
    }

    @Test fun retryBackoffIsBoundedAndFasterDuringCalls() {
        assertEquals(listOf(2000L, 4000L, 8000L, 60000L, 60000L), (0..4).map { discoveryRetryDelay(it, false) })
        assertEquals(8000L, discoveryRetryDelay(Int.MAX_VALUE, true))
        assertEquals(60000L, discoveryRetryDelay(Int.MAX_VALUE, false))
    }
}
