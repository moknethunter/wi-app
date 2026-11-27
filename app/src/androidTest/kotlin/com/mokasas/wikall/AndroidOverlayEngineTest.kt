package com.mokasas.wikall

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.easytier.jni.EasyTierJNI
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidOverlayEngineTest {
    @Test fun nativeEasyTierLoadsAndAcceptsTheGeneratedLocalProfile() {
        val request = OverlayRequest("A".repeat(64), "X".repeat(32), HOST_NUMBER, null)
        try {
            assertEquals(0, EasyTierJNI.runNetworkInstance(request.configuration()))
        } finally {
            assertEquals(0, EasyTierJNI.retainNetworkInstance(null))
        }
    }
}
