package com.mokasas.wikall

import org.junit.Assert.*
import org.junit.Test

class LegacyRelayProofTest {
    @Test fun relayAnnouncementBindsBothNetworkAddressesAndTheOverlayIdentity() {
        val pin = "A".repeat(64)
        val secret = "X".repeat(32)
        val relay = "192.168.1.2"
        val target = "192.168.1.3"
        val proof = relayProof(secret, pin, relay, target)
        assertEquals(proof, relayProof(secret, pin, relay, target))
        assertNotEquals(proof, relayProof(secret, pin, target, relay))
        assertNotEquals(proof, relayProof(secret, "B".repeat(64), relay, target))
        assertNotEquals(proof, relayProof("Y".repeat(32), pin, relay, target))
    }

}
