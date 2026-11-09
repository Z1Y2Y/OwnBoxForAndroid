package io.nekohasekai.sagernet.route

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SmartRouteRulesTest {

    private fun sourceFile(relative: String): File {
        return sequenceOf(File(relative), File("app", relative), File("../app", relative))
            .firstOrNull { it.exists() } ?: File(relative)
    }

    private val catalog by lazy {
        RuleSetCatalog.parseSrsList(sourceFile("src/main/assets/routeprofiles/srslist.h").readText())
    }

    @Test
    fun classifyAppsSeparatesDomesticAndForeignApps() {
        val sampleInstalled = setOf(
            "com.tencent.mm",
            "com.eg.android.AlipayGphone",
            "com.icbc.im",
            "cn.gov.tax.its",
            "org.telegram.messenger",
            "com.google.android.youtube",
            "com.openai.chatgpt",
            "com.zhiliaoapp.musically",
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.microsoft.emmx"
        )

        val (domesticCn, foreignCn) = SmartRouteRules.classifyApps(
            country = "cn",
            installedPackages = sampleInstalled,
            bundledForeign = setOf("com.android.chrome", "org.telegram.messenger", "android"),
            appPolicies = emptyMap(),
        )

        assertTrue(domesticCn.contains("com.tencent.mm"))
        assertTrue(domesticCn.contains("com.eg.android.AlipayGphone"))
        assertTrue(domesticCn.contains("com.icbc.im"))
        assertTrue(domesticCn.contains("cn.gov.tax.its"))

        assertTrue(foreignCn.contains("org.telegram.messenger"))
        assertTrue(foreignCn.contains("com.google.android.youtube"))
        assertTrue(foreignCn.contains("com.openai.chatgpt"))
        assertTrue(foreignCn.contains("com.zhiliaoapp.musically"))
        assertFalse(domesticCn.contains("com.zhiliaoapp.musically"))
        assertFalse(foreignCn.contains("android"))
        // Preserve the pre-existing browser target-routing behavior unless a user adds an explicit override.
        for (pkg in listOf("com.android.chrome", "org.mozilla.firefox", "com.microsoft.emmx")) {
            assertFalse(domesticCn.contains(pkg))
            assertFalse(foreignCn.contains(pkg))
        }
    }

    @Test
    fun buildSmartRulesAdaptsToSelectedCountry() {
        val cnRules = SmartRouteRules.buildSmartRules("cn", catalog, hasGeositeDb = false, hasGeoipDb = false)
        assertTrue(cnRules.any { it.outbound == -1L && it.domains.contains("geosite:cn") && it.domains.contains("domain:cn") })
        assertTrue(cnRules.any { it.outbound == -1L && it.ip.contains("geoip:cn") })

        val ruRules = SmartRouteRules.buildSmartRules("ru", catalog, hasGeositeDb = false, hasGeoipDb = false)
        assertTrue(ruRules.any { it.outbound == -1L && it.domains.contains("geosite:ru") && it.domains.contains("domain:ru") })
        assertTrue(ruRules.any { it.outbound == -1L && it.ip.contains("geoip:ru") })

        val irRules = SmartRouteRules.buildSmartRules("ir", catalog, hasGeositeDb = false, hasGeoipDb = false)
        assertTrue(irRules.any { it.outbound == -1L && it.domains.contains("geosite:ir") && it.domains.contains("domain:ir") })
        assertTrue(irRules.any { it.outbound == -1L && it.ip.contains("geoip:ir") })
    }

    @Test
    fun buildSmartRulesFallsBackToCoreDomainsWhenGeoDataIsUnavailable() {
        val rules = SmartRouteRules.buildSmartRules("cn", catalog = null, hasGeositeDb = false, hasGeoipDb = false)
        val domains = rules.single { it.name == "Smart-Domestic-Domains-cn" }.domains

        assertTrue(domains.contains("domain:cn"))
        assertFalse(domains.contains("geosite:cn"))
        assertFalse(rules.any { it.ip.contains("geoip:cn") })
    }

    @Test
    fun followSmartIsExplicitForAnyPackageAndForcePoliciesPrecedeDestinationRules() {
        val xBrowser = "com.mmbox.xbrowser"
        val ordinaryApp = "com.example.webview-host"
        val forceProxyApp = "tv.danmaku.bili"
        val forceDirectApp = "org.telegram.messenger"
        val legacyBrowser = "com.android.chrome"
        val installed = setOf(xBrowser, ordinaryApp, forceProxyApp, forceDirectApp, legacyBrowser)
        val policies = mapOf(
            xBrowser to PerAppRoutePolicy.FOLLOW_SMART,
            ordinaryApp to PerAppRoutePolicy.FOLLOW_SMART,
            forceProxyApp to PerAppRoutePolicy.FORCE_PROXY,
            forceDirectApp to PerAppRoutePolicy.FORCE_DIRECT,
            legacyBrowser to PerAppRoutePolicy.FORCE_PROXY,
        )

        val (domestic, foreign) = SmartRouteRules.classifyApps(
            country = "cn",
            installedPackages = installed,
            bundledForeign = emptySet(),
            proxyAppsEnabled = true,
            bypassMode = false,
            individualPackages = setOf(xBrowser, ordinaryApp, forceProxyApp, forceDirectApp),
            appPolicies = policies,
        )

        // New packages need no name-based browser detection; explicit policies determine the route.
        for (pkg in listOf(xBrowser, ordinaryApp)) {
            assertFalse("$pkg should follow destination rules, not an app rule", domestic.contains(pkg))
            assertFalse("$pkg should follow destination rules, not an app rule", foreign.contains(pkg))
        }
        assertTrue("explicit proxy wins over the domestic app classification", foreign.contains(forceProxyApp))
        assertTrue("explicit direct wins over the foreign app classification", domestic.contains(forceDirectApp))

        val rules = SmartRouteRules.buildSmartRules(
            rawCountry = "cn",
            catalog = null,
            hasGeositeDb = false,
            hasGeoipDb = false,
            appPolicies = policies,
        )
        val appRules = rules.filter { it.packages.isNotEmpty() }
        val allAppPackages = appRules.flatMap { it.packages }.toSet()
        val forceProxyIndex = rules.indexOfFirst { it.name == "Smart-Force-Proxy-Apps" }
        val forceDirectIndex = rules.indexOfFirst { it.name == "Smart-Force-Direct-Apps" }
        val googlePlayIndex = rules.indexOfFirst { it.name == "Smart-GooglePlay" }
        val domesticDomainIndex = rules.indexOfFirst { it.name == "Smart-Domestic-Domains-cn" }
        assertFalse(allAppPackages.contains(xBrowser))
        assertFalse(allAppPackages.contains(ordinaryApp))
        assertTrue(allAppPackages.contains(forceProxyApp))
        assertTrue(allAppPackages.contains(forceDirectApp))
        assertTrue(allAppPackages.contains(legacyBrowser))
        assertTrue(forceProxyIndex >= 0 && forceProxyIndex < googlePlayIndex)
        assertTrue(forceDirectIndex >= 0 && forceDirectIndex < googlePlayIndex)
        assertTrue(googlePlayIndex < domesticDomainIndex)
        assertTrue(appRules.all { rules.indexOf(it) < domesticDomainIndex })
    }

    @Test
    fun classifyAppsRespectsLegacyPerAppProxySelectionWhenNoOverrideExists() {
        val sampleInstalled = setOf(
            "tv.danmaku.bili",
            "com.tencent.mm",
            "org.telegram.messenger"
        )

        val (domesticProxyMode, foreignProxyMode) = SmartRouteRules.classifyApps(
            country = "cn",
            installedPackages = sampleInstalled,
            bundledForeign = emptySet(),
            proxyAppsEnabled = true,
            bypassMode = false,
            individualPackages = setOf("tv.danmaku.bili"),
            appPolicies = emptyMap(),
        )
        assertFalse(domesticProxyMode.contains("tv.danmaku.bili"))
        assertTrue(foreignProxyMode.contains("tv.danmaku.bili"))

        val (domesticBypassMode, foreignBypassMode) = SmartRouteRules.classifyApps(
            country = "cn",
            installedPackages = sampleInstalled,
            bundledForeign = emptySet(),
            proxyAppsEnabled = true,
            bypassMode = true,
            individualPackages = setOf("org.telegram.messenger"),
            appPolicies = emptyMap(),
        )
        assertFalse(foreignBypassMode.contains("org.telegram.messenger"))
        assertTrue(domesticBypassMode.contains("org.telegram.messenger"))
    }
}
