package io.nekohasekai.sagernet.fmt

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GoogleRouteRuleTest {

    private fun sourceFile(relative: String): File {
        return sequenceOf(File(relative), File("app", relative), File("../app", relative))
            .firstOrNull { it.exists() } ?: File(relative)
    }

    @Test
    fun testConfigBuilderDecomposesDomainIpAndAppWithOrSemantics() {
        val configBuilderSource = sourceFile("src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt").readText()

        // 1. Verify domain, IP, and app matches are independent OR branches
        assertTrue("ConfigBuilder must define hasApp flag", configBuilderSource.contains("hasApp = uidList.isNotEmpty() || selectedPackages.isNotEmpty()"))
        assertTrue("ConfigBuilder must compute independent route match kinds", configBuilderSource.contains("val matchKinds = routeMatchKinds("))
        assertTrue("ConfigBuilder must have a domain match branch", configBuilderSource.contains("if (RouteMatchKind.DOMAIN in matchKinds)"))
        assertTrue("ConfigBuilder must have an IP match branch", configBuilderSource.contains("if (RouteMatchKind.IP in matchKinds)"))
        assertTrue("ConfigBuilder must have an app match branch", configBuilderSource.contains("if (RouteMatchKind.APP in matchKinds)"))

        // 2. Verify appSubRule does not force domain restrictions
        assertTrue("ConfigBuilder must generate appSubRule for app criteria", configBuilderSource.contains("val appSubRule = Rule_DefaultOptions()"))
        assertTrue("ConfigBuilder must add appSubRule when non-empty", configBuilderSource.contains("if (!appSubRule.checkEmpty()) generatedSubRules.add(appSubRule)"))

        // 3. Verify RouteRuleEditor action and invertedGroup contracts are preserved
        assertTrue("RouteRuleEditor.applyAction must be retained", configBuilderSource.contains("RouteRuleEditor.applyAction(ruleObj, rule.config)"))
        assertTrue("RouteRuleEditor.invertedGroup must be retained", configBuilderSource.contains("RouteRuleEditor.invertedGroup(generatedSubRules)"))
    }

    @Test
    fun testProfileManagerGooglePlayRuleEnrichment() {
        val profileManagerSource = sourceFile("src/main/java/io/nekohasekai/sagernet/database/ProfileManager.kt").readText()

        // Verify Play Store rule keeps Play/GMS/GSF packages without hijacking system DownloadManager
        assertFalse(
            "Default Play Store routing rule must not force-add com.android.providers.downloads",
            profileManagerSource.contains("rule.packages = rule.packages + setOf(\n                            \"com.android.vending\",\n                            \"com.google.android.gms\",\n                            \"com.google.android.gsf\",\n                            \"com.android.providers.downloads\"")
        )
        assertTrue(profileManagerSource.contains("\"com.google.android.gsf\""))
        assertTrue(profileManagerSource.contains("\"com.android.vending\""))
        assertTrue(profileManagerSource.contains("\"com.google.android.gms\""))

        // Verify Play Store CDN and gateway domains are present
        assertTrue(profileManagerSource.contains("playstoregatewayadapter-pa.googleapis.com"))
        assertTrue(profileManagerSource.contains("firebaselogging-pa.googleapis.com"))
        assertTrue(profileManagerSource.contains("ggpht.com"))
        assertTrue(profileManagerSource.contains("gvt1.com"))
    }

    @Test
    fun testVpnServiceWhitelistIncludesDownloadManager() {
        val vpnServiceSource = sourceFile("src/main/java/io/nekohasekai/sagernet/bg/VpnService.kt").readText()

        // Verify VpnService auto-includes DownloadManager when Google Play Store is whitelisted
        assertTrue(vpnServiceSource.contains("if (proxyApps && !bypass && individual.contains(\"com.android.vending\"))"))
        assertTrue(vpnServiceSource.contains("individual.add(\"com.android.providers.downloads\")"))
    }
}
