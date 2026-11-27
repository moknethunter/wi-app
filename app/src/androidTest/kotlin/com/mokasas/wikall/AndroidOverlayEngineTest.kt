package com.mokasas.wikall

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.easytier.jni.EasyTierJNI
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramSocket

@RunWith(AndroidJUnit4::class)
class AndroidOverlayEngineTest {
    @Test fun nativeEasyTierLoadsAndAcceptsTheGeneratedLocalProfile() {
        val request = OverlayRequest("A".repeat(64), "X".repeat(32), HOST_NUMBER, null)
        try {
            assertEquals(0, EasyTierJNI.runNetworkInstance(request.configuration()))
            val engine = EasyTierEngine()
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var snapshot: EasyTierSnapshot? = null
            while (snapshot?.running != true && SystemClock.elapsedRealtime() < deadline) {
                snapshot = engine.snapshot(OVERLAY_INSTANCE)
                if (snapshot?.running != true) SystemClock.sleep(100)
            }
            assertEquals("Native engine must report the requested instance name", true, snapshot?.running)
            assertNull("Unexpected fallback instance name", engine.snapshot("default"))
        } finally {
            assertEquals(0, EasyTierJNI.retainNetworkInstance(null))
        }
    }

    /** Real native peer negotiation on loopback, no Android TUN; not a multi-phone test. */
    @Test fun nativeClientReceivesAddressFromSeededRealm() {
        val master = OverlayRequest("B".repeat(64), Wire.token(), HOST_NUMBER, null)
        val node = master.copy(number = "99001")
        val (masterPort, nodePort) = DatagramSocket(0).use { first ->
            DatagramSocket(0).use { second -> first.localPort to second.localPort }
        }
        fun testConfig(request: OverlayRequest, name: String, port: Int, peer: Int? = null): String {
            val config = request.configuration(name)
                .replace("udp://0.0.0.0:$OVERLAY_PORT", "udp://127.0.0.1:$port")
                .replace("[flags]", "[flags]\nno_tun = true\nbind_device = false")
            return if (peer == null) config else config + "\n[[peer]]\nuri = \"udp://127.0.0.1:$peer\"\n"
        }
        val engine = EasyTierEngine()
        try {
            engine.start(testConfig(master, "wikall-dhcp-test-master", masterPort))
            engine.start(testConfig(node, "wikall-dhcp-test-node", nodePort, masterPort))
            val deadline = SystemClock.elapsedRealtime() + 30_000
            var plan: VirtualAddressPlan? = null
            while (SystemClock.elapsedRealtime() < deadline && plan == null) {
                plan = runCatching {
                    nativeAddressPlan(engine.snapshot("wikall-dhcp-test-node"), node)
                }.getOrNull()
                if (plan == null) SystemClock.sleep(250)
            }
            assertNotNull("Native DHCP did not report a valid address in the seeded realm", plan)
            assertNotEquals(OVERLAY_HOST, plan!!.address)
            assertEquals(OVERLAY_PREFIX, plan.prefix)
        } finally {
            engine.stopAll()
        }
    }
}
