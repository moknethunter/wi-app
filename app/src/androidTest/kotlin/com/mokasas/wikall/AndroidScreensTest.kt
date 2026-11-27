package com.mokasas.wikall

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidScreensTest {
    @get:Rule
    val app = createAndroidComposeRule<MainActivity>()

    @Test fun displaysTheServerAndClientActivationFlows() {
        app.onNodeWithText("إدارة الخادم · 99000").performClick()
        app.onNodeWithText("تشغيل الخادم").assertIsDisplayed()
        app.onNodeWithText("العودة لاختيار الوضع").performClick()
        app.onNodeWithText("الانضمام إلى خادم").performClick()
        app.onNodeWithText("استيراد ملف التفعيل").assertExists()
        val exports = File(app.activity.cacheDir, "exports/requests")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Intercept the system activity so the test keeps control of its own window.
        val chooser = instrumentation.addMonitor(
            IntentFilter(Intent.ACTION_CHOOSER), Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true,
        )
        try {
            app.onNodeWithText("طلب التفعيل").performScrollTo().performClick()
            app.waitUntil(15_000) { chooser.hits == 1 }
            val archive = checkNotNull(exports.listFiles()).first { it.extension == "zip" }
            DeviceRequest.read(unpackTransfer(Transfer.REQUEST, archive.readBytes()))
        } finally {
            instrumentation.removeMonitor(chooser)
        }
    }
    @Test fun theDialPadSubmitsTheFiveDigitNumber() {
        val activity = app.activity
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(activity.packageName, Manifest.permission.RECORD_AUDIO)
        val address = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }.first(::privateIpv4)

        app.onNodeWithText("إدارة الخادم · 99000").performClick()
        app.runOnIdle { activity.controller.start(address) }
        try {
            app.waitUntil(10_000) {
                app.onAllNodes(androidx.compose.ui.test.hasText("شاشة الاتصال")).fetchSemanticsNodes().isNotEmpty()
            }
            app.onNodeWithText("شاشة الاتصال").performClick()
            "99001".forEach { digit -> app.onNodeWithTag("dial-digit-$digit").performClick() }
            app.onNodeWithText("99001").assertIsDisplayed()
            app.onNodeWithText("اتصل").performClick()
            app.waitUntil(10_000) {
                app.onAllNodes(androidx.compose.ui.test.hasText("الرقم 99001 غير متصل")).fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            app.runOnIdle { activity.controller.stop() }
        }
    }
}
