package com.mokasas.wikall

import com.easytier.jni.EasyTierJNI
import org.json.JSONObject

/** Native control boundary. A running engine is NOT proof of peer connectivity. */
internal class EasyTierEngine {
    @Synchronized
    fun start(configuration: String) {
        check(EasyTierJNI.parseConfig(configuration) == 0) { "EasyTier config rejected" }
        check(EasyTierJNI.runNetworkInstance(configuration) == 0) { "EasyTier start failed" }
    }

    @Synchronized
    fun attachTun(instance: String, fd: Int) {
        require(fd >= 0)
        check(EasyTierJNI.setTunFd(instance, fd) == 0) { "EasyTier TUN attach failed" }
    }

    @Synchronized
    fun stopAll() {
        check(EasyTierJNI.retainNetworkInstance(null) == 0) { "EasyTier stop failed" }
    }

    /** Call from a worker thread. Missing data is unknown, never fake success. */
    @Synchronized
    fun snapshot(instance: String): EasyTierSnapshot? =
        EasyTierJNI.collectNetworkInfos(10)?.let { decodeEasyTierSnapshot(it, instance) }
}

internal data class EasyTierSnapshot(
    val running: Boolean?,
    val address: String?,
    val prefix: Int?,
    val peers: Int?,
    val routes: Int?,
)

internal fun decodeEasyTierSnapshot(json: String, instance: String): EasyTierSnapshot? {
    val info = JSONObject(json).optJSONObject("map")?.optJSONObject(instance) ?: return null
    val node = info.optJSONObject("my_node_info") ?: info.optJSONObject("myNodeInfo")
    val inet = node?.optJSONObject("virtual_ipv4") ?: node?.optJSONObject("virtualIpv4")
    val raw = inet?.optJSONObject("address")?.optString("addr")?.toLongOrNull()
    val bits = raw?.takeIf { it in Int.MIN_VALUE.toLong()..0xffffffffL }?.and(0xffffffffL)
    val ip = bits?.let { value -> listOf(24, 16, 8, 0).joinToString(".") { shift -> ((value shr shift) and 255).toString() } }
    val prefix = (inet?.optString("network_length")?.toIntOrNull()
        ?: inet?.optString("networkLength")?.toIntOrNull())?.takeIf { it in 1..32 }
    return EasyTierSnapshot(
        info.opt("running") as? Boolean,
        ip, prefix, info.optJSONArray("peers")?.length(), info.optJSONArray("routes")?.length(),
    )
}
