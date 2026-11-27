package com.mokasas.wikall

internal fun internetIce(candidate: String): Boolean {
    if (candidate.length > 800 || '\r' in candidate || '\n' in candidate || !candidate.startsWith("candidate:")) return false
    val fields = candidate.split(' ')
    return fields.size >= 8 && fields[1] == "1" && fields[2].lowercase() in setOf("udp", "tcp") &&
        Regex("[a-zA-Z0-9:.%-]{1,128}").matches(fields[4]) && (fields[5].toIntOrNull() ?: 0) in 1..65535 &&
        fields[6] == "typ" && fields[7] in setOf("host", "srflx", "prflx", "relay")
}
