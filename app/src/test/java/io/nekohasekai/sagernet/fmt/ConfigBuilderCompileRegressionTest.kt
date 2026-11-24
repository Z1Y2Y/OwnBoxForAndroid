package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import moe.matsuri.nb4a.SingBoxOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderCompileRegressionTest {
    @Test
    fun smartAndCustomModesSelectOnlyTheirOwnRules() {
        val userRules = listOf(RuleEntity(id = 1L, name = "user"))
        val smartRules = listOf(RuleEntity(id = 2L, name = "smart"))

        assertEquals(smartRules, selectRouteRules(RouteRuleMode.SMART, userRules, smartRules))
        assertEquals(userRules, selectRouteRules(RouteRuleMode.CUSTOM, userRules, smartRules))
        assertTrue(selectRouteRules(RouteRuleMode.DISABLED, userRules, smartRules).isEmpty())
    }

    @Test
    fun leastPingKeepsStickyOptInAndNonStickyUrlTestBehavior() {
        val healthy = listOf("measured")
        val unavailable = listOf("failed")
        val sticky = buildLeastPingBalancerOutbound(
            memberTags = listOf("measured", "failed"),
            testUrl = "https://probe.example/generate_204",
            intervalSec = 30,
            toleranceMs = 50,
            customTag = "sticky-group",
            stickyByApp = true,
            healthyTags = healthy,
            unavailableTags = unavailable,
        ).asMap()

        assertEquals("loadbalance", sticky["type"])
        assertEquals("leastPing", sticky["strategy"])
        assertEquals(true, sticky["sticky_by_app"])
        assertEquals(healthy, sticky["healthy_outbounds"])
        assertEquals(unavailable, sticky["unavailable_outbounds"])
        assertEquals(false, sticky["interrupt_exist_connections"])

        val nonSticky = buildLeastPingBalancerOutbound(
            memberTags = listOf("measured", "failed"),
            testUrl = "https://probe.example/generate_204",
            intervalSec = 30,
            toleranceMs = 50,
            customTag = "ordinary-group",
            stickyByApp = false,
            healthyTags = healthy,
            unavailableTags = unavailable,
        ).asMap()
        assertEquals("urltest", nonSticky["type"])
        assertFalse(nonSticky.containsKey("sticky_by_app"))
        assertEquals(healthy, nonSticky["healthy_outbounds"])
        assertEquals(unavailable, nonSticky["unavailable_outbounds"])
        assertEquals(false, nonSticky["interrupt_exist_connections"])
    }

    @Test
    fun healthTagsExcludeUnknownAndStalePingResults() {
        val members = listOf(
            ProxyEntity(id = 1L, status = 1, ping = 35) to "healthy",
            ProxyEntity(id = 2L, status = 1, ping = 0) to "unmeasured",
            ProxyEntity(id = 3L, status = 0, ping = 12) to "unknown",
            ProxyEntity(id = 4L, status = 2, ping = 18) to "timed-out",
            ProxyEntity(id = 5L, status = 3, ping = 0) to "failed",
        )

        val tags = configBuilderHealthTags(members)
        assertEquals(listOf("healthy"), tags.available)
        assertEquals(listOf("timed-out", "failed"), tags.unavailable)
    }

    @Test
    fun sanitizerOnlyRewritesUnsupportedLegacyTunStacks() {
        val config = """{"inbounds":[{"stack":"gvisor"},{"stack":"MIXED"}],"outbounds":[{"stack":"system"}]}"""

        assertEquals(
            """{"inbounds":[{"stack": "go"},{"stack": "go"}],"outbounds":[{"stack":"system"}]}""",
            sanitizeSingBoxConfig(config),
        )
        assertEquals("", sanitizeSingBoxConfig(""))
    }

    @Test
    fun ruleSetDownloadDetourUsesExtensionOptionAndOnlyRepairsMissingTargets() {
        val missing = SingBoxOptions.RuleSet().apply {
            type = "remote"
            _hack_config_map["download_detour"] = "removed-outbound"
        }
        val valid = SingBoxOptions.RuleSet().apply {
            type = "remote"
            _hack_config_map["download_detour"] = "proxy"
        }
        val local = SingBoxOptions.RuleSet().apply {
            type = "local"
            _hack_config_map["download_detour"] = "removed-outbound"
        }

        syncRuleSetDownloadDetours(listOf(missing, valid, local), setOf("proxy", "direct"), "proxy")

        assertEquals("proxy", missing.asMap()["download_detour"])
        assertEquals("proxy", valid.asMap()["download_detour"])
        assertEquals("removed-outbound", local.asMap()["download_detour"])
    }
}
