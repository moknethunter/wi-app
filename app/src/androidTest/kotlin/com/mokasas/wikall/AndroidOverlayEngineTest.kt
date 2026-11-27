package com.mokasas.wikall

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.easytier.jni.EasyTierJNI
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

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

    @Test fun nativeConnectorCannotUseAnUnselectedPhysicalSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val physical = NetworkStateMonitor(context).physicalAddresses().first()
        val loopback = InetAddress.getByName("127.0.0.1")
        val request = OverlayRequest("D".repeat(64), Wire.token(), HOST_NUMBER, null)
        val engine = EasyTierEngine()
        val listenerPort = DatagramSocket(0, loopback).use { it.localPort }
        DatagramSocket(0, InetAddress.getByName(physical)).use { receiver ->
            val configuration = request.configuration()
                .replace("udp://0.0.0.0:$OVERLAY_PORT", "udp://127.0.0.1:$listenerPort")
                .replace("[flags]", "[flags]\nno_tun = true\nbind_device = false") +
                "\n[[peer]]\nuri = \"udp://$physical:${receiver.localPort}\"\n"
            try {
                engine.start(configuration)
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (engine.snapshot(OVERLAY_INSTANCE)?.running != true &&
                    SystemClock.elapsedRealtime() < deadline
                ) {
                    SystemClock.sleep(100)
                }
                assertEquals(true, engine.snapshot(OVERLAY_INSTANCE)?.running)
                receiver.soTimeout = 5_000
                assertThrows(SocketTimeoutException::class.java) {
                    receiver.receive(DatagramPacket(ByteArray(256), 256))
                }
            } finally {
                engine.stopAll()
            }

            val source = InetAddress.getByName(physical)
            val port = DatagramSocket(0, source).use { it.localPort }
            val permitted = configuration.replace(
                "udp://127.0.0.1:$listenerPort", "udp://$physical:$port",
            )
            try {
                engine.start(permitted)
                receiver.soTimeout = 10_000
                val packet = DatagramPacket(ByteArray(256), 256)
                receiver.receive(packet)
                assertEquals(source, packet.address)
            } finally {
                engine.stopAll()
            }
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
            val evidence = listOf("wikall-dhcp-test-master", "wikall-dhcp-test-node")
                .joinToString(" | ") { nativeTestState(engine, it) }
            android.util.Log.i("WiKallNativeTest", evidence)
            assertNotNull("Native DHCP did not report a valid address in the seeded realm: $evidence", plan)
            assertNotEquals(OVERLAY_HOST, plan!!.address)
            assertEquals(OVERLAY_PREFIX, plan.prefix)
        } finally {
            engine.stopAll()
        }
    }
}
