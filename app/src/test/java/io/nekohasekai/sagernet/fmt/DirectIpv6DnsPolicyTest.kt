package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.DNSRule_DefaultOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import io.nekohasekai.sagernet.IPv6Mode

class DirectIpv6DnsPolicyTest {

    @Test
    fun rejectsDirectAaaaOnlyForDualStackModesWithoutUnderlyingIpv6DefaultRoute() {
        assertTrue(DirectIpv6DnsPolicy.shouldRejectDirectAaaa(IPv6Mode.ENABLE, false))
        assertTrue(DirectIpv6DnsPolicy.shouldRejectDirectAaaa(IPv6Mode.PREFER, false))
        assertFalse(DirectIpv6DnsPolicy.shouldRejectDirectAaaa(IPv6Mode.ENABLE, true))
        assertFalse(DirectIpv6DnsPolicy.shouldRejectDirectAaaa(IPv6Mode.PREFER, true))
        assertFalse(DirectIpv6DnsPolicy.shouldRejectDirectAaaa(IPv6Mode.DISABLE, false))
        assertFalse(DirectIpv6DnsPolicy.shouldRejectDirectAaaa(IPv6Mode.ONLY, false))
    }

    @Test
    fun onlyIpv6ZeroPrefixRouteCountsAsAnIpv6DefaultRoute() {
        assertTrue(DirectIpv6DnsPolicy.isIpv6DefaultRoute(isIpv6Destination = true, prefixLength = 0))
        assertFalse(DirectIpv6DnsPolicy.isIpv6DefaultRoute(isIpv6Destination = false, prefixLength = 0))
        assertFalse(DirectIpv6DnsPolicy.isIpv6DefaultRoute(isIpv6Destination = true, prefixLength = 64))
    }

    @Test
    fun createsAnAaaaRejectRuleForDirectDnsWithoutChangingItsSource() {
        val direct = DNSRule_DefaultOptions().apply {
            server = "dns-direct"
            domain_suffix = listOf("bilibili.com")
            package_name = listOf("tv.danmaku.bili")
            rule_set = listOf("geosite:cn")
        }

        val reject = DirectIpv6DnsPolicy.rejectAaaaRuleForDirectDnsRule(direct)!!

        assertNull(reject.server)
        assertEquals(listOf("AAAA"), reject.query_type)
        assertEquals("reject", reject.action)
        assertEquals(true, reject.disable_cache)
        assertEquals(listOf("bilibili.com"), reject.domain_suffix)
        assertEquals(listOf("tv.danmaku.bili"), reject.package_name)
        assertEquals(listOf("geosite:cn"), reject.rule_set)
        assertEquals("dns-direct", direct.server)
        assertNull(direct.query_type)
        assertNull(direct.action)
    }

    @Test
    fun proxyFakeIpRulesAreNotConvertedAndDirectGatePrecedesTheGenericFakeRule() {
        val proxyFakeIp = DNSRule_DefaultOptions().apply {
            server = "dns-fake"
            inbound = listOf("tun-in")
            query_type = listOf("A", "AAAA")
        }
        assertNull(DirectIpv6DnsPolicy.rejectAaaaRuleForDirectDnsRule(proxyFakeIp))

        val direct = DNSRule_DefaultOptions().apply {
            server = "dns-direct"
            domain_suffix = listOf("bilibili.com")
        }
        val rules = mutableListOf(direct, proxyFakeIp)
        DirectIpv6DnsPolicy.addRejectBeforeDirectDnsRule(rules, direct)

        assertEquals(3, rules.size)
        assertEquals("reject", rules[0].action)
        assertEquals(listOf("AAAA"), rules[0].query_type)
        assertSame(direct, rules[1])
        assertSame(proxyFakeIp, rules[2])
        assertEquals(listOf("A", "AAAA"), proxyFakeIp.query_type)
    }
}
