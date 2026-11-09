package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.RouteRuleEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RouteRuleSemanticsTest {
    private fun sourceFile(relative: String): File =
        sequenceOf(File(relative), File("app", relative), File("../app", relative))
            .firstOrNull { it.exists() } ?: File(relative)

    @Test
    fun clearingConditionsRemovesThemWithoutChangingOtherRuleState() {
        val rule = RuleEntity(
            name = "User custom name",
            domains = "domain:example.test",
            ip = "192.0.2.0/24",
            ruleset = "geosite:example",
            packages = setOf("com.example.app"),
            outbound = 42L,
        )
        val edited = rule.copy(domains = "", ip = "", ruleset = "", packages = emptySet())

        assertEquals("", edited.domains)
        assertEquals("", edited.ip)
        assertEquals("", edited.ruleset)
        assertTrue(edited.packages.isEmpty())
        assertEquals("User custom name", edited.name)
        assertEquals(42L, edited.outbound)
        assertFalse(RouteRuleEditor.hasConditions(edited))
    }

    @Test
    fun emptyJsonValuesDoNotCountAsMatchConditions() {
        for (config in listOf(
            """{"rule_set":[],"rules":[],"domain_suffix":[""],"network_type":[]}""",
            """{"rule_set":"","domain_keyword":[],"inbound":[""]}""",
        )) {
            assertFalse(config, RouteRuleEditor.hasConditions(RuleEntity(config = config)))
        }
        assertTrue(RouteRuleEditor.hasConditions(RuleEntity(config = """{"rule_set":["geoip:private"]}""")))
        assertTrue(RouteRuleEditor.hasConditions(RuleEntity(config = """{"rules":[{"domain":["example.test"]}]}""")))
    }

    @Test
    fun clearingAdvancedFieldsDoesNotRestoreThemOrTouchOtherConfig() {
        val original = """{"domain_suffix":["example.test"],"invert":true,"future":{"keep":1}}"""
        val cleared = RouteRuleEditor.change(original, "domain_suffix", "")
        val parsed = RouteRuleEditor.json(cleared)

        assertFalse(parsed.has("domain_suffix"))
        assertTrue(parsed.getBoolean("invert"))
        assertEquals(1, parsed.getJSONObject("future").getInt("keep"))
        assertEquals("", RouteRuleEditor.text(cleared, "domain_suffix"))
    }

    @Test
    fun ruleSetsAndApplicationsProduceIndependentMatchGroups() {
        // A domain rule-set and a selected app compile to separate OR branches.
        assertEquals(
            listOf(RouteMatchKind.DOMAIN, RouteMatchKind.APP),
            routeMatchKinds(hasDomain = true, hasIp = false, hasApp = true),
        )
        // IP rule-sets are likewise independent of the selected app.
        assertEquals(
            listOf(RouteMatchKind.IP, RouteMatchKind.APP),
            routeMatchKinds(hasDomain = false, hasIp = true, hasApp = true),
        )
        assertEquals(
            listOf(RouteMatchKind.DOMAIN, RouteMatchKind.IP, RouteMatchKind.APP),
            routeMatchKinds(hasDomain = true, hasIp = true, hasApp = true),
        )
        assertEquals(
            listOf(RouteMatchKind.FALLBACK),
            routeMatchKinds(hasDomain = false, hasIp = false, hasApp = false),
        )
    }

    @Test
    fun selectedOutboundIdAloneChoosesTheRouteActionTarget() {
        val tags = mapOf(42L to "user-selected-outbound", 84L to "another-outbound")

        assertEquals("main-proxy", resolveRouteRuleOutbound(0L, 10L, "main-proxy", tags))
        assertEquals(TAG_BYPASS, resolveRouteRuleOutbound(-1L, 10L, "main-proxy", tags))
        assertEquals(TAG_BLOCK, resolveRouteRuleOutbound(-2L, 10L, "main-proxy", tags))
        assertEquals("user-selected-outbound", resolveRouteRuleOutbound(42L, 10L, "main-proxy", tags))
        assertEquals("main-proxy", resolveRouteRuleOutbound(10L, 10L, "main-proxy", tags))
    }

    @Test
    fun ruleReadAndEditorSaveDoNotInferConditionsFromNamesOrSpecialPackages() {
        val profileManager = sourceFile("src/main/java/io/nekohasekai/sagernet/database/ProfileManager.kt").readText()
        val readMethod = profileManager.substringAfter("suspend fun getRules(): List<RuleEntity>")
            .substringBefore("\n\n}")
        assertTrue(readMethod.contains("SagerDatabase.rulesDao.allRules()"))
        assertFalse(readMethod.contains("createRule("))
        assertFalse(readMethod.contains("updateRule("))
        assertFalse(profileManager.contains("shouldAutoEnrichPlayStoreRule"))

        val editor = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/RouteSettingsActivity.kt").readText()
        for (assignment in listOf(
            "DataStore.routeDomain = domains",
            "DataStore.routeIP = ip",
            "DataStore.routePort = port",
            "DataStore.routeSourcePort = sourcePort",
            "DataStore.routeNetwork = network",
            "DataStore.routeSource = source",
            "DataStore.routeProtocol = protocol",
            "DataStore.routeRuleset = ruleset",
            "DataStore.routePackages = packages.joinToString",
            "domains = DataStore.routeDomain",
            "ip = DataStore.routeIP",
            "port = DataStore.routePort",
            "sourcePort = DataStore.routeSourcePort",
            "network = DataStore.routeNetwork",
            "source = DataStore.routeSource",
            "protocol = DataStore.routeProtocol",
            "ruleset = DataStore.routeRuleset",
            "packages = DataStore.routePackages.split",
        )) assertTrue("Missing exact editor round-trip: $assignment", editor.contains(assignment))
        assertTrue(editor.contains("SagerDatabase.rulesDao.getById(editingId)?.copy()"))
        assertTrue(editor.contains("ProfileManager.updateRule(candidate)"))

        val builder = sourceFile("src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt").readText()
        assertTrue(builder.contains("val selectedPackages = rule.packages.toMutableSet()"))
        assertFalse(builder.contains("effectiveGooglePlayRoutePackages"))
        assertTrue(builder.contains("routeMatchKinds("))
        assertTrue(builder.contains("if (RouteMatchKind.DOMAIN in matchKinds)"))
        assertTrue(builder.contains("if (RouteMatchKind.IP in matchKinds)"))
        assertTrue(builder.contains("if (RouteMatchKind.APP in matchKinds)"))
        assertTrue(builder.contains("if (RouteMatchKind.FALLBACK in matchKinds)"))
        assertTrue(builder.contains("rulesetTags.filter { !it.second }.map { it.first }"))
        assertTrue(builder.contains("rulesetTags.filter { it.second }.map { it.first }"))
        assertTrue(builder.contains("package_name = selectedPackages.toList()"))
        assertTrue(builder.contains("ruleObj.network = rule.network.listByLineOrComma()"))
        assertTrue(builder.contains("resolveRouteRuleOutbound(rule.outbound, proxy.id, mainProxyTag, tagMap)"))

        val vpnService = sourceFile("src/main/java/io/nekohasekai/sagernet/bg/VpnService.kt").readText()
        assertFalse(vpnService.contains("individual.contains(\"com.android.vending\")"))
        assertFalse(vpnService.contains("individual.add(\"com.android.providers.downloads\")"))
    }
}
