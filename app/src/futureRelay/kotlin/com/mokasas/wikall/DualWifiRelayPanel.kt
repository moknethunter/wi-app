package com.mokasas.wikall

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.net.Inet4Address
import java.net.InetAddress

@Composable
internal fun DualWifiRelayPanel() {
    val context = LocalContext.current
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    val main = remember { Handler(Looper.getMainLooper()) }
    val supportsConcurrent = Build.VERSION.SDK_INT >= 31 &&
        wifi.isStaConcurrencyForLocalOnlyConnectionsSupported
    var request by remember { mutableStateOf<ConnectivityManager.NetworkCallback?>(null) }
    var links by remember { mutableStateOf(wifiRelayLinks(context)) }
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var firstPeer by remember { mutableStateOf("") }
    var secondPeer by remember { mutableStateOf("") }
    var bridge by remember { mutableStateOf<EasyTierDatagramRelay?>(null) }
    var activeLinks by remember { mutableStateOf(setOf<WifiRelayLink>()) }
    var status by remember { mutableStateOf("") }

    val wifiPermissions = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    val requestSecondWifi: () -> Unit = {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { status = "وصلت شبكة Wi-Fi الثانية." }
            override fun onLost(network: Network) { status = "انقطع الاتصال بشبكة Wi-Fi الثانية." }
            override fun onUnavailable() {
                request = null
                status = "تعذّر الاتصال بشبكة Wi-Fi الثانية."
            }
        }
        val connected = runCatching {
            val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
                if (password.isNotEmpty()) setWpa2Passphrase(password)
            }.build()
            val wanted = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier).build()
            connectivity.requestNetwork(wanted, callback, main, 30_000)
        }.isSuccess
        if (connected) {
            request = callback
            password = ""
            status = "بانتظار الموافقة على الاتصال بشبكة Wi-Fi الثانية."
        } else status = "تعذّر طلب شبكة Wi-Fi ثانية."
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (context.checkSelfPermission(wifiPermissions.first()) == PackageManager.PERMISSION_GRANTED) requestSecondWifi()
        else status = "لم يُمنح إذن الاتصال بشبكة Wi-Fi ثانية."
    }

    DisposableEffect(connectivity, context) {
        val refresh = Runnable {
            links = wifiRelayLinks(context)
            if (bridge != null && !links.containsAll(activeLinks)) {
                bridge?.close()
                bridge = null
                status = "توقف الترحيل بعد فقدان إحدى وصلتي Wi-Fi."
            }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun refreshLinks() {
                main.removeCallbacks(refresh)
                main.postDelayed(refresh, 150L)
            }

            override fun onAvailable(network: Network) = refreshLinks()
            override fun onLost(network: Network) = refreshLinks()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = refreshLinks()
        }
        val monitoring = runCatching {
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(), callback, main)
        }.isSuccess
        refresh.run()
        if (!monitoring) status = "تعذّر مراقبة تغيّرات Wi-Fi."
        onDispose {
            if (monitoring) runCatching { connectivity.unregisterNetworkCallback(callback) }
            main.removeCallbacks(refresh)
            bridge?.close()
            request?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("واجهات Wi-Fi النشطة: ${links.distinctBy { it.network }.size}")
        if (supportsConcurrent && links.distinctBy { it.network }.size < 2) {
            OutlinedTextField(ssid, { ssid = it }, label = { Text("اسم شبكة Wi-Fi الثانية") }, singleLine = true)
            OutlinedTextField(password, { password = it }, label = { Text("كلمة مرور WPA2، أو اتركها فارغة لشبكة مفتوحة") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Button(enabled = request == null && ssid.isNotBlank() && (password.isEmpty() || password.length in 8..63),
                onClick = {
                    if (wifiPermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
                        requestSecondWifi()
                    } else {
                        permissions.launch(wifiPermissions)
                    }
                }) { Text("اتصل بشبكة ثانية") }
        } else if (!supportsConcurrent && links.distinctBy { it.network }.size < 2) {
            Text("النظام أو الهاتف لا يتيح طلب رابط Wi-Fi ثانٍ بالتزامن.")
        }

        if (links.distinctBy { it.network }.size >= 2 && bridge == null) {
            Text("أدخل عنوان IPv4 الفعلي للعقدة البادئة وللعقدة المستمعة على UDP 11010.")
            OutlinedTextField(firstPeer, { firstPeer = it }, label = { Text("عقدة EasyTier البادئة") }, singleLine = true)
            OutlinedTextField(secondPeer, { secondPeer = it }, label = { Text("عقدة EasyTier المستمعة") }, singleLine = true)
            Button(enabled = privateIpv4(firstPeer.trim()) && privateIpv4(secondPeer.trim()), onClick = {
                val result = runCatching {
                    val a = InetAddress.getByName(firstPeer.trim()) as Inet4Address
                    val b = InetAddress.getByName(secondPeer.trim()) as Inet4Address
                    require(a != b)
                    val left = links.singleOrNull { it.contains(a) && it.ip != a }
                    val right = links.singleOrNull { it.contains(b) && it.ip != b }
                    require(left != null && right != null && left.network != right.network)
                    val first = left.socket(a)
                    val second = try {
                        right.socket(b)
                    } catch (failure: Exception) {
                        first.close()
                        throw failure
                    }
                    activeLinks = setOf(left, right)
                    EasyTierDatagramRelay(
                        EasyTierDatagramRelay.Leg(first, a, EASYTIER_RELAY_PORT, initiator = true),
                        EasyTierDatagramRelay.Leg(second, b, EASYTIER_RELAY_PORT),
                    ) {
                        main.post {
                            bridge = null
                            status = "توقف ترحيل EasyTier بعد خطأ في الوصلة."
                        }
                    }
                }
                result.onSuccess {
                    bridge = it
                    it.start()
                    status = "الترحيل يعمل عبر ${activeLinks.joinToString(" و ") { link -> link.ip.hostAddress.orEmpty() }}."
                }.onFailure {
                    status = "تعذّر بدء الترحيل. تحقق من أن كل عنوان ينتمي إلى شبكة مختلفة غير متداخلة."
                }
            }) { Text("ابدأ ترحيل EasyTier") }
        }
        if (request != null && bridge == null) Button(onClick = {
            request?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
            request = null
            status = "أوقف المستخدم طلب شبكة Wi-Fi الثانية."
        }) { Text("حرّر شبكة Wi-Fi الثانية") }
        if (bridge != null) Button(onClick = {
            bridge?.close()
            bridge = null
            status = "أوقف المستخدم الترحيل."
        }) { Text("أوقف الترحيل") }
        if (status.isNotEmpty()) Text(status)
    }
}
