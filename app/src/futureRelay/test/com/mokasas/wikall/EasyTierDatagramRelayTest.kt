package com.mokasas.wikall

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyTierDatagramRelayTest {
    @Test fun relaysInBothDirectionsAndPreservesDatagramLength() {
        val loopback = InetAddress.getByName("127.0.0.1")
        fun endpoint() = DatagramSocket(InetSocketAddress(loopback, 0)).apply { soTimeout = 2_000 }
        endpoint().use { left ->
            endpoint().use { right ->
                endpoint().use { leftLeg ->
                    endpoint().use { rightLeg ->
                        left.connect(leftLeg.localSocketAddress)
                        right.connect(rightLeg.localSocketAddress)
                        EasyTierDatagramRelay(
                            EasyTierDatagramRelay.Leg(leftLeg, loopback, 11010, initiator = true),
                            EasyTierDatagramRelay.Leg(rightLeg, loopback, right.localPort),
                        ) {}.use { relay ->
                            relay.start()
                            val short = byteArrayOf(1, 2)
                            val long = ByteArray(1300) { it.toByte() }
                            fun assertForwarded(sender: DatagramSocket, receiver: DatagramSocket, bytes: ByteArray) {
                                sender.send(DatagramPacket(bytes, bytes.size))
                                val incoming = DatagramPacket(ByteArray(2048), 2048)
                                receiver.receive(incoming)
                                assertArrayEquals(bytes, incoming.data.copyOf(incoming.length))
                            }
                            assertForwarded(left, right, short)
                            assertForwarded(left, right, long)
                            assertForwarded(right, left, long)
                        }
                        assertTrue(leftLeg.isClosed && rightLeg.isClosed)
                    }
                }
            }
        }
    }

    @Test fun matchesNonOctetPrefixes() {
        val local = InetAddress.getByName("192.168.5.132").address
        assertTrue(sameIpv4Subnet(local, InetAddress.getByName("192.168.5.190").address, 26))
        assertFalse(sameIpv4Subnet(local, InetAddress.getByName("192.168.5.192").address, 26))
    }
}
