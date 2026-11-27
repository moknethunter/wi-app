package com.mokasas.wikall

import com.easytier.jni.EasyTierJNI
import org.json.JSONObject

/** Native control boundary. A running engine is NOT proof of peer connectivity. */
internal class EasyTierEngine : OverlayEngine {
    @Synchronized
    override fun start(configuration: String) {
        check(EasyTierJNI.parseConfig(configuration) == 0) { "EasyTier config rejected" }
        check(EasyTierJNI.runNetworkInstance(configuration) == 0) { "EasyTier start failed" }
    }

    @Synchronized
    override fun attachTun(instance: String, fd: Int) {
        require(fd >= 0)
        check(EasyTierJNI.setTunFd(instance, fd) == 0) { "EasyTier TUN attach failed" }
    }

    @Synchronized
    override fun stopAll() {
        check(EasyTierJNI.retainNetworkInstance(null) == 0) { "EasyTier stop failed" }
    }

    /** Call from a worker thread. Missing data is unknown, never fake success. */
    @Synchronized
    override fun snapshot(instance: String): EasyTierSnapshot? =
        EasyTierJNI.collectNetworkInfos()?.let { decodeEasyTierSnapshot(it, instance) }
}

internal data class EasyTierSnapshot(
    val running: Boolean?,
    val address: String?,
    val prefix: Int?,
    val peers: Int?,
    val routes: Int?,
    val routeDetails: List<NativeRoute>? = null,
    val failureCategory: String? = null,
)

internal fun decodeEasyTierSnapshot(json: String, instance: String): EasyTierSnapshot? {
    val info = JSONObject(json).optJSONObject("map")?.optJSONObject(instance) ?: return null
    val rawError = info.opt("error_msg") as? String ?: info.opt("errorMsg") as? String
    val category = nativeFailureCategory(rawError)
    // Incomplete diagnostics must not turn protobuf defaults into observed empty networks.
    if (rawError?.startsWith("wikall_snapshot_") == true) {
        return EasyTierSnapshot(null, null, null, null, null, failureCategory = category)
    }
    if (rawError?.startsWith("wikall_stopped_") == true) {
        return EasyTierSnapshot(false, null, null, null, null, failureCategory = category)
    }
    val node = info.optJSONObject("my_node_info") ?: info.optJSONObject("myNodeInfo")
    val inet = node?.optJSONObject("virtual_ipv4") ?: node?.optJSONObject("virtualIpv4")
    val ip = decodeNativeIpv4(inet)
    val prefix = (inet?.optString("network_length")?.toIntOrNull()
        ?: inet?.optString("networkLength")?.toIntOrNull())?.takeIf { it in 1..32 }
    return EasyTierSnapshot(
        info.opt("running") as? Boolean,
        ip, prefix, info.optJSONArray("peers")?.length(), info.optJSONArray("routes")?.length(),
        decodeNativeRoutes(info),
        category,
    )
}

private fun decodeNativeIpv4(inet: JSONObject?): String? {
    val raw = inet?.optJSONObject("address")?.optString("addr")?.toLongOrNull()
    val bits = raw?.takeIf { it in Int.MIN_VALUE.toLong()..0xffffffffL }?.and(0xffffffffL)
    return bits?.let { value ->
        listOf(24, 16, 8, 0).joinToString(".") { shift -> ((value shr shift) and 255).toString() }
    }
}

private fun nativePeerId(item: JSONObject, snake: String, camel: String): Long? =
    (item.optString(snake).toLongOrNull() ?: item.optString(camel).toLongOrNull())
        ?.takeIf { it in 1L..0xffffffffL }

private fun decodeNativeRoutes(info: JSONObject): List<NativeRoute>? {
    val array = info.optJSONArray("routes") ?: return null
    if (array.length() > 1024) return null
    val result = ArrayList<NativeRoute>(array.length())
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: return null
        val destination = nativePeerId(item, "peer_id", "peerId") ?: return null
        val nextHop = nativePeerId(item, "next_hop_peer_id", "nextHopPeerId") ?: return null
        val inet = item.optJSONObject("ipv4_addr") ?: item.optJSONObject("ipv4Addr")
        val address = decodeNativeIpv4(inet)?.takeIf(::privateIpv4)
        val cost = item.optString("cost").toIntOrNull()?.takeIf { it > 0 }
        result.add(NativeRoute(destination, nextHop, address, cost))
    }
    return result
}

/** Native error strings can contain configuration and credentials. Never log them verbatim. */
internal fun nativeFailureCategory(message: String?): String? {
    val error = message?.takeIf { it.isNotBlank() }?.lowercase() ?: return null
    return when {
        error in setOf("wikall_snapshot_api_pending", "wikall_snapshot_peer_query",
            "wikall_snapshot_node_query", "wikall_snapshot_route_query",
            "wikall_snapshot_portal_query", "wikall_snapshot_foreign_query",
            "wikall_snapshot_unavailable", "wikall_stopped_permission_denied",
            "wikall_stopped_address_in_use", "wikall_stopped_network_unreachable",
            "wikall_stopped_other", "wikall_stopped_no_detail") -> error
        "permission denied" in error || "operation not permitted" in error -> "permission_denied"
        "address already in use" in error -> "address_in_use"
        "network is unreachable" in error || "no route to host" in error -> "network_unreachable"
        "instance already exists" in error -> "duplicate_instance"
        "timed out" in error || "timeout" in error -> "timeout"
        else -> "native_error"
    }
}
