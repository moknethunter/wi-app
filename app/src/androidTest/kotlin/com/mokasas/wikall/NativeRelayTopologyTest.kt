package com.mokasas.wikall

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramSocket

@RunWith(AndroidJUnit4::class)
class NativeRelayTopologyTest {
    @Test fun decodesNativeRoutesWithoutTreatingMissingDataAsZero() {
        val json = """{"map":{"wikall":{"running":true,"routes":[
            {"peer_id":4294967295,"next_hop_peer_id":7,"cost":2},
            {"peerId":7,"nextHopPeerId":7,"cost":1}]}}}"""
        val snapshot = checkNotNull(decodeEasyTierSnapshot(json, "wikall"))
        assertEquals(2, snapshot.routes)
        assertEquals(RelayTopology(1, 1, 1), summarizeRelayTopology(snapshot.routeDetails))
        val malformed = """{"map":{"wikall":{"running":true,"routes":[{"peer_id":7}]}}}"""
        assertNull(checkNotNull(decodeEasyTierSnapshot(malformed, "wikall")).routeDetails)
    }

    /** Real EasyTier route convergence on three loopback instances, not audio or physical relay. */
    @Test fun nativeRouteTableReportsAPathThroughIntermediatePeer() {
        val realm = OverlayRequest("C".repeat(64), Wire.token(), HOST_NUMBER, null)
        val ports = DatagramSocket(0).use { first ->
            DatagramSocket(0).use { middle ->
                DatagramSocket(0).use { last -> listOf(first.localPort, middle.localPort, last.localPort) }
            }
        }
        fun config(index: Int, upstream: Int?): String {
            val name = "wikall-relay-test-$index"
            val ip = "10.126.124.${index + 1}"
            val local = realm.configuration(name)
                .replace("ipv4 = \"$OVERLAY_HOST/$OVERLAY_PREFIX\"", "ipv4 = \"$ip/$OVERLAY_PREFIX\"")
                .replace("udp://0.0.0.0:$OVERLAY_PORT", "udp://127.0.0.1:${ports[index]}")
                .replace("[flags]", "[flags]\nno_tun = true\nbind_device = false\ndisable_p2p = true")
            return if (upstream == null) local else
                local + "\n[[peer]]\nuri = \"udp://127.0.0.1:${ports[upstream]}\"\n"
        }
        val engine = EasyTierEngine()
        try {
            engine.start(config(0, null))
            engine.start(config(1, 0))
            engine.start(config(2, 1))
            val deadline = SystemClock.elapsedRealtime() + 45_000
            var verified = false
            while (SystemClock.elapsedRealtime() < deadline && !verified) {
                val routes = runCatching { engine.snapshot("wikall-relay-test-0")?.routeDetails }.getOrNull()
                val middle = routes?.singleOrNull { it.address == "10.126.124.2" }
                val last = routes?.singleOrNull { it.address == "10.126.124.3" }
                verified = middle != null && last != null &&
                    middle.nextHopPeer == middle.destinationPeer &&
                    last.nextHopPeer == middle.destinationPeer &&
                    last.destinationPeer != last.nextHopPeer
                if (!verified) SystemClock.sleep(500)
            }
            val evidence = (0..2).joinToString(" | ") { nativeTestState(engine, "wikall-relay-test-$it") }
            android.util.Log.i("WiKallNativeTest", evidence)
            assertTrue("Native route table must report the last node through the middle node: $evidence", verified)
        } finally {
            engine.stopAll()
        }
    }
}
