package com.mokasas.wikall

import org.junit.Assert.*
import org.junit.Test

/** JVM contract tests; fake driver is test-only, not an alternative runtime. */
class OverlayEngineContractTest {
    private class RecordingNative : EasyTierNativeControl {
        val events = mutableListOf<String>()
        var config: String? = null
        var parseResult = 0
        var startError: String? = null
        override fun parse(configuration: String): Int {
            events += "parse"
            config = configuration
            return parseResult
        }
        override fun start(configuration: String): Int {
            events += "start"
            startError?.let { throw RuntimeException(it) }
            assertEquals(config, configuration)
            return 0
        }
        override fun attach(instance: String, fd: Int): Int {
            events += "attach:$instance:$fd"
            return 0
        }
        override fun stopAll(): Int {
            events += "stop"
            return 0
        }
        override fun collect(): String? = null
    }

    private fun request() = OverlayRequest("A".repeat(64), "X".repeat(32), HOST_NUMBER, null)

    @Test fun typedStartUsesRealSerializerAndOrderedNativeOperations() {
        val driver = RecordingNative()
        val engine: OverlayEngine = EasyTierEngine(driver)
        engine.start(request(), "contract")
        assertEquals(request().configuration("contract"), driver.config)
        assertTrue(driver.config!!.startsWith("instance_name = \"contract\"\n"))
        engine.attachTun("contract", 12)
        assertNull(engine.snapshot("contract"))
        engine.stopAll()
        assertEquals(listOf("parse", "start", "attach:contract:12", "stop"), driver.events)
    }

    @Test fun rejectedConfigurationNeverStartsNativeEngine() {
        val driver = RecordingNative().apply { parseResult = -1 }
        val engine: OverlayEngine = EasyTierEngine(driver)
        val failure = runCatching { engine.start(request()) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(listOf("parse"), driver.events)
    }

    @Test fun nativeErrorsAndRequestTextDoNotExposeSecrets() {
        val request = request()
        val driver = RecordingNative().apply { startError = "Permission denied: ${request.secret}" }
        val engine: OverlayEngine = EasyTierEngine(driver)
        val failure = runCatching { engine.start(request) }.exceptionOrNull()!!
        assertEquals("permission_denied", failure.message)
        assertNull(failure.cause)
        assertFalse(request.toString().contains(request.secret))
    }

    @Test fun onlySameTransportSessionCanReuseItsTun() {
        val active = request().copy(listenerAddresses = listOf("192.168.1.3"), transportRevision = 7)
        val retry = active.copy(transportRevision = 8)
        val newLink = active.copy(listenerAddresses = listOf("172.16.0.2"))

        assertTrue(active.canReuseTunnelFor(active.copy(peer = "192.168.1.5")))
        assertFalse(active.canReuseTunnelFor(retry))
        assertFalse(active.canReuseTunnelFor(newLink))
        assertNotEquals(active, retry)
        assertEquals(active.configuration(), retry.configuration())
    }

    @Test fun invalidTunDescriptorNeverReachesNativeDriver() {
        val driver = RecordingNative()
        val engine: OverlayEngine = EasyTierEngine(driver)
        assertTrue(runCatching { engine.attachTun("contract", -1) }.isFailure)
        assertTrue(driver.events.isEmpty())
    }
}
