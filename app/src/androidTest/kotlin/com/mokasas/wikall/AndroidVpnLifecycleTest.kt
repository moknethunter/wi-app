package com.mokasas.wikall

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidVpnLifecycleTest {
    @Suppress("DEPRECATION")
    @Test fun masterCreatesStopsAndRecreatesTheLocalVpn() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val changedAppOp = VpnService.prepare(context) != null
        fun setVpnAppOp(mode: String) {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                "cmd appops set ${context.packageName} ACTIVATE_VPN $mode",
            )).use { it.readBytes() }
        }

        fun hasWiKallRoute(): Boolean = connectivity.allNetworks.any { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@any false
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@any false
            val properties = connectivity.getLinkProperties(network) ?: return@any false
            properties.linkAddresses.any { it.address.hostAddress == OVERLAY_HOST } &&
                properties.routes.any {
                    it.destination.prefixLength == OVERLAY_PREFIX && it.destination.contains(InetAddress.getByName(OVERLAY_HOST))
                } &&
                properties.dnsServers.isEmpty()
        }

        fun waitFor(active: Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < deadline && hasWiKallRoute() != active) {
                SystemClock.sleep(100)
            }
            assertTrue("Expected WiKall TUN active=$active", hasWiKallRoute() == active)
        }

        if (changedAppOp) setVpnAppOp("allow")
        try {
            assertNull("The test package must be authorized for VPN", VpnService.prepare(context))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    scenario.onActivity { activity ->
                        activity.controller.stop()
                        activity.controller.start(activity.controller.listAddresses().first())
                    }
                    waitFor(true)
                    scenario.onActivity { it.controller.stop() }
                    waitFor(false)
                    scenario.onActivity { activity ->
                        activity.controller.start(activity.controller.listAddresses().first())
                    }
                    waitFor(true)
                } finally {
                    scenario.onActivity { it.controller.stop() }
                    waitFor(false)
                }
            }
        } finally {
            if (changedAppOp) setVpnAppOp("default")
        }
    }
}
