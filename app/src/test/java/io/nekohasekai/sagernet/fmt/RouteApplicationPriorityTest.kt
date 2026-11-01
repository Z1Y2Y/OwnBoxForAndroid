package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.Rule
import moe.matsuri.nb4a.SingBoxOptions.Rule_DefaultOptions
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteApplicationPriorityTest {
    @Test
    fun applicationRulesPrecedeTelegramFallbackWithoutReorderingOtherRules() {
        val dnsPortRule = Rule_DefaultOptions().apply {
            port = mutableListOf(53)
            action = "hijack-dns"
        }
        val dnsProtocolRule = Rule_DefaultOptions().apply {
            protocol = mutableListOf("dns")
            action = "hijack-dns"
        }
        val telegramFallback = Rule_DefaultOptions().apply {
            ip_cidr = mutableListOf("91.108.4.0/22")
            outbound = "proxy"
        }
        val sniffRule = Rule_DefaultOptions().apply {
            action = "sniff"
            timeout = "100ms"
        }
        val appUidRule = Rule_DefaultOptions().apply {
            user_id = mutableListOf(10306)
            outbound = "singapore"
        }
        val appPackageRule = Rule_DefaultOptions().apply {
            package_name = mutableListOf("uz.unnarsx.cherrygram")
            outbound = "singapore"
        }
        val domainRule = Rule_DefaultOptions().apply {
            domain_suffix = mutableListOf("example.com")
            outbound = "hong-kong"
        }
        val rules = mutableListOf<Rule>(
            dnsPortRule,
            dnsProtocolRule,
            telegramFallback,
            sniffRule,
            appUidRule,
            appPackageRule,
            domainRule,
        )

        prioritizeApplicationRoutesBefore(rules, telegramFallback)

        assertEquals(
            listOf(dnsPortRule, dnsProtocolRule, appUidRule, appPackageRule, telegramFallback, sniffRule, domainRule),
            rules,
        )
    }

    @Test
    fun noAppRulesLeavesFallbackOrderUnchanged() {
        val dnsRule = Rule_DefaultOptions().apply { action = "hijack-dns" }
        val fallback = Rule_DefaultOptions().apply {
            ip_cidr = mutableListOf("91.108.4.0/22")
            outbound = "proxy"
        }
        val otherRule = Rule_DefaultOptions().apply {
            domain_suffix = mutableListOf("example.com")
            outbound = "direct"
        }
        val rules = mutableListOf<Rule>(dnsRule, fallback, otherRule)

        prioritizeApplicationRoutesBefore(rules, fallback)

        assertEquals(listOf(dnsRule, fallback, otherRule), rules)
    }
}
