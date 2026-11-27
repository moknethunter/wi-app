package com.mokasas.wikall

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EasyTierSnapshotTest {
    @Test fun nativeFailureDiagnosticsNeverReturnRawErrorText() {
        assertEquals("permission_denied", nativeFailureCategory("Permission denied: synthetic-secret"))
        assertEquals("native_error", nativeFailureCategory("unexpected synthetic-secret"))
        assertNull(nativeFailureCategory(""))
        assertEquals("address_in_use", decodeEasyTierSnapshot(
            """{"map":{"wikall":{"running":false,"error_msg":"Address already in use: synthetic-secret"}}}""",
            "wikall")!!.failureCategory)
    }

    @Test fun decodesNativeInfoAndDoesNotInventMissingData() {
        val info = decodeEasyTierSnapshot("""{"map":{"wikall":{"running":true,"my_node_info":{"virtual_ipv4":{"address":{"addr":16909060},"network_length":24}},"peers":[{}],"routes":[]}}}""", "wikall")!!
        assertEquals(true, info.running)
        assertEquals("1.2.3.4", info.address)
        assertEquals(24, info.prefix)
        assertEquals(1, info.peers)
        assertEquals(0, info.routes)
        assertNull(decodeEasyTierSnapshot("{}", "wikall"))
        assertNull(decodeEasyTierSnapshot("""{"map":{"wikall":{}}}""", "wikall")!!.running)
    }
}
