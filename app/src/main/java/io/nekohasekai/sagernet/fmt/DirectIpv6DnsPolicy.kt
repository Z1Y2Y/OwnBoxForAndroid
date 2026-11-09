package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.IPv6Mode
import moe.matsuri.nb4a.SingBoxOptions.DNSRule_DefaultOptions
import moe.matsuri.nb4a.utils.JavaUtil.gson

/**
 * Narrow DNS policy for destinations that are explicitly routed direct. This is intentionally
 * separate from the user's global IPv6 mode and from proxy FakeIP address-family settings.
 */
internal object DirectIpv6DnsPolicy {
    fun shouldRejectDirectAaaa(ipv6Mode: Int, hasUnderlyingIpv6DefaultRoute: Boolean): Boolean =
        (ipv6Mode == IPv6Mode.ENABLE || ipv6Mode == IPv6Mode.PREFER) &&
            !hasUnderlyingIpv6DefaultRoute

    fun isIpv6DefaultRoute(isIpv6Destination: Boolean, prefixLength: Int): Boolean =
        isIpv6Destination && prefixLength == 0

    fun rejectAaaaRuleForDirectDnsRule(
        directRule: DNSRule_DefaultOptions,
    ): DNSRule_DefaultOptions? {
        if (directRule.server != "dns-direct") return null
        val result = gson.fromJson(gson.toJson(directRule), DNSRule_DefaultOptions::class.java)
        return result.apply {
            server = null
            query_type = listOf("AAAA")
            action = "reject"
            disable_cache = true
        }
    }

    /** Insert immediately before the source rule so its direct DNS server cannot answer AAAA. */
    fun addRejectBeforeDirectDnsRule(
        rules: MutableList<in DNSRule_DefaultOptions>,
        directRule: DNSRule_DefaultOptions,
    ) {
        val rejectRule = rejectAaaaRuleForDirectDnsRule(directRule) ?: return
        val sourceIndex = rules.indexOfFirst { it === directRule }
        if (sourceIndex < 0) rules.add(rejectRule) else rules.add(sourceIndex, rejectRule)
    }
}
