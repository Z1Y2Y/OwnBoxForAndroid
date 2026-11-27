package io.nekohasekai.sagernet.ui

import org.json.JSONObject

/** Formats only host/IP values explicitly supplied by the local connections API. */
internal object ConnectionAddressFormatter {
    private val hostLabel = Regex("^[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$")

    fun format(metadata: JSONObject): String {
        val candidates = listOf("host", "destinationHost", "destinationDomain", "domain")
            .mapNotNull { key -> metadata.optString(key).cleanAddressValue() }

        // sing-box's Clash API exposes Metadata.Domain (sniffed) or Destination.Fqdn
        // under metadata.host. Prefer it to the destination IP when it is a hostname.
        val domain = candidates.firstOrNull(::isDomainName)
        val destinationIp = metadata.optString("destinationIP").cleanAddressValue()
        val hostIp = candidates.firstOrNull(::isIpLiteral)
        val target = domain ?: destinationIp ?: hostIp ?: "—"
        val port = metadata.optString("destinationPort")
            .trim()
            .toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?.toString()

        if (port == null) return target
        return if (isIpv6Literal(target) && !target.startsWith('[')) {
            "[$target]:$port"
        } else {
            "$target:$port"
        }
    }

    private fun String?.cleanAddressValue(): String? = this
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "null" && it != "—" && it != "-" }

    private fun isDomainName(value: String): Boolean {
        val hostname = value.removeSuffix(".")
        if (hostname.isEmpty() || hostname.length > 253 || hostname.any(Char::isWhitespace)) return false
        if (isIpLiteral(hostname) || hostname.none(Char::isLetter)) return false
        return hostname.split('.').all { label ->
            label.length in 1..63 && hostLabel.matches(label)
        }
    }

    private fun isIpLiteral(value: String): Boolean = isIpv4Literal(value) || isIpv6Literal(value)

    private fun isIpv4Literal(value: String): Boolean {
        val octets = value.split('.')
        return octets.size == 4 && octets.all { octet ->
            octet.isNotEmpty() && octet.all(Char::isDigit) && octet.toIntOrNull() in 0..255
        }
    }

    /** Syntax-only IPv6 check; deliberately avoids InetAddress, which can resolve names. */
    private fun isIpv6Literal(value: String): Boolean {
        val unwrapped = value.removePrefix("[").removeSuffix("]")
        val address = unwrapped.substringBefore('%')
        return address.count { it == ':' } >= 2 && address.all {
            it == ':' || it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
        }
    }
}
