package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression source-contract tests for Issue #88 fixes while keeping Smart Routing
 * (`SmartRouteRules` / `isSmartRouting`) and default speed test parameters intact.
 */
class ConfigBuilderRouteIssuesTest {

    private fun resolveSourceFile(relativeFromApp: String): File {
        val direct = File(relativeFromApp)
        if (direct.isFile) return direct
        val fromRoot = File("app", relativeFromApp)
        if (fromRoot.isFile) return fromRoot
        error("Unable to locate source file: $relativeFromApp (cwd=${File(".").absolutePath})")
    }

    @Test
    fun multicastRejectRuleOmitsSourceIpCidrAndDnsFallbackRulesRouteToMainProxy() {
        val text = resolveSourceFile("src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt").readText()

        val mcastIndex = text.indexOf("listOf(\"224.0.0.0/3\", \"ff00::/8\")")
        assertTrue("Expected multicast block rule in ConfigBuilder.kt", mcastIndex >= 0)
        val mcastWindow = text.substring(maxOf(0, mcastIndex - 200), minOf(text.length, mcastIndex + 200))
        assertFalse("Multicast reject rule must not include source_ip_cidr", mcastWindow.contains("source_ip_cidr"))

        // .85 behaviour: un-intercepted remote DNS traffic falls back to the main proxy
        // (port 53 / dns protocol -> mainProxyTag), placed right after the remote-DNS
        // hard-isolation rules; hijack-dns still handles inbound port 53 / dns first.
        val remoteIsolationIdx = text.indexOf("// 6. 远程 DNS 硬隔离规则")
        val port53Idx = text.indexOf("port = listOf(53)\n                outbound = mainProxyTag")
        val dnsProtoIdx = text.indexOf("protocol = listOf(\"dns\")\n                outbound = mainProxyTag")
        assertTrue("Expected remote-DNS hard-isolation block", remoteIsolationIdx >= 0)
        assertTrue("Expected port 53 -> mainProxyTag DNS fallback rule (.85 rule 7)", port53Idx > remoteIsolationIdx)
        assertTrue("Expected protocol dns -> mainProxyTag DNS fallback rule (.85 rule 7)", dnsProtoIdx > port53Idx)
    }

    @Test
    fun topRouteRulesWithoutPlayInterceptionAndDomesticPriorityPreserveCustomRules() {
        val text = resolveSourceFile("src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt").readText()
        val topRouteStart = text.indexOf("val topRouteRules =")
        assertTrue("Expected topRouteRules builder block", topRouteStart >= 0)

        val sniffIf = text.indexOf("if (needSniff)", topRouteStart)
        val smartDomesticGuard = text.indexOf("if (isSmartRouting)", topRouteStart)
        val domesticAdd = text.indexOf("domain_suffix = domesticCoreDomainSuffixes", smartDomesticGuard)

        // .85 behaviour: no blockQuic toggle, no early-reject bucket — user reject rules
        // stay in route.rules in their original order.
        assertFalse("DataStore.blockQuic must be gone (.85 has no blockQuic)", text.contains("DataStore.blockQuic"))
        assertFalse("earlyRejectUserRules bucket must be gone (.85 has none)", text.contains("earlyRejectUserRules"))
        assertFalse("targetRuleBucket must be gone (.85 has none)", text.contains("targetRuleBucket"))
        assertTrue("Expected needSniff guard in topRouteRules", sniffIf >= 0)
        assertTrue("Expected domesticCoreDomainSuffixes scoped to isSmartRouting", smartDomesticGuard >= 0 && domesticAdd > smartDomesticGuard)

        // .85 behaviour: no built-in Google Play route rule may intercept user rules.
        // GOOGLE_PLAY_DOMAIN_SUFFIXES is only used by the DNS rule (dns-remote), which
        // lives before the route-rules section; nothing after topRouteRules may reference it.
        assertFalse(
            "Built-in Google Play route rule must be gone (no firstUserDirectIdx lookup)",
            text.contains("val firstUserDirectIdx ="),
        )
        assertTrue(
            "Google Play DNS rule (dns-remote) must still exist",
            text.contains("domain_suffix = GOOGLE_PLAY_DOMAIN_SUFFIXES"),
        )
        assertTrue(
            "Google Play suffix list must only be used by the DNS rule, before the route-rules section",
            text.indexOf("domain_suffix = GOOGLE_PLAY_DOMAIN_SUFFIXES") < topRouteStart &&
                text.indexOf("domain_suffix = GOOGLE_PLAY_DOMAIN_SUFFIXES", topRouteStart) < 0,
        )
        assertFalse(
            "User reject rules must stay in route.rules in order (.85 has no early-reject bucket)",
            text.contains("earlyRejectUserRules") || text.contains("targetRuleBucket"),
        )
        assertTrue(
            "ConfigBuilder must sync rule_set download_detour tags against built outbounds",
            text.contains("syncRuleSetDownloadDetours(route.rule_set, availableOutboundTags, mainProxyTag)"),
        )
        assertTrue(
            "ConfigBuilder must answer HTTPS/SVCB DNS queries via predefined NOERROR action",
            text.contains("query_type = listOf(\"HTTPS\", \"SVCB\")") && text.contains("action = \"predefined\""),
        )
        assertFalse(
            "ConfigBuilder must not force tcp_fast_open=true globally",
            text.contains("_hack_config_map[\"tcp_fast_open\"] = true"),
        )
    }

    @Test
    fun downloadManagerNotInjectedIntoRoutingRulesAndNotAutoIncludedInTunWhitelist() {
        val configBuilder = resolveSourceFile("src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt").readText()
        val profileManager = resolveSourceFile("src/main/java/io/nekohasekai/sagernet/database/ProfileManager.kt").readText()
        val vpnService = resolveSourceFile("src/main/java/io/nekohasekai/sagernet/bg/VpnService.kt").readText()

        assertFalse(
            "ConfigBuilder must not force com.android.providers.downloads into user rule packages",
            configBuilder.contains("com.android.providers.downloads"),
        )
        // .85 behaviour: getRules() is a plain allRules() passthrough — nothing injects
        // DownloadManager into rule packages, so there is nothing to strip either.
        assertFalse(
            "ProfileManager must not mention com.android.providers.downloads at all (no injection, no strip)",
            profileManager.contains("com.android.providers.downloads"),
        )
        // .85 behaviour: VpnService does NOT auto-include DownloadManager when Play Store
        // is whitelisted — users select DownloadManager / GMS / GSF themselves.
        assertFalse(
            "VpnService must not auto-include com.android.providers.downloads (.85 has no such append)",
            vpnService.contains("com.android.providers.downloads"),
        )
    }

    @Test
    fun groupSettingsUsesRawUrlTestPreferenceToAvoidBakingGlobalDefault() {
        val groupSettings = resolveSourceFile("src/main/java/io/nekohasekai/sagernet/ui/GroupSettingsActivity.kt").readText()
        val dataStore = resolveSourceFile("src/main/java/io/nekohasekai/sagernet/database/DataStore.kt").readText()

        assertTrue(
            "GroupSettingsActivity must initialize groupUrlTestUrl from rawGroupUrlTestUrl(id)",
            groupSettings.contains("DataStore.groupUrlTestUrl = DataStore.rawGroupUrlTestUrl(id)"),
        )
        assertTrue(
            "DataStore.groupUrlTestUrl must fallback to DataStore.connectionTestURL when blank or default",
            dataStore.contains("stored.takeIf { it.isNotBlank() && it != defaultUrl } ?: connectionTestURL"),
        )
    }

    @Test
    fun echQueryDomainsExemptFromPredefinedHttpsBlockAndRoutedToDirectDns() {
        val outboundMaps: List<Map<String, Any?>> = listOf(
            // 1. Explicit ech.query_server_name (mixed case + trailing dot -> normalized)
            mapOf(
                "tag" to "proxy-explicit",
                "server" to "8.216.61.208",
                "tls" to mapOf(
                    "enabled" to true,
                    "server_name" to "cf.worker.example",
                    "ech" to mapOf(
                        "enabled" to true,
                        "query_server_name" to "WWW.Britannica.COM.",
                    ),
                ),
            ),
            // 2. Fallback to tls.server_name when query_server_name omitted
            mapOf(
                "tag" to "proxy-sni-fallback",
                "server" to "8.216.61.209",
                "tls" to mapOf(
                    "enabled" to true,
                    "server_name" to "cdn.example.org",
                    "ech" to mapOf("enabled" to true),
                ),
            ),
            // 3. Fallback to server when both query_server_name and tls.server_name omitted
            mapOf(
                "tag" to "proxy-server-fallback",
                "server" to "edge.example.net",
                "tls" to mapOf(
                    "enabled" to true,
                    "ech" to mapOf("enabled" to true),
                ),
            ),
            // 4. Static ech.config as List<String> -> skipped
            mapOf(
                "tag" to "proxy-static-list",
                "server" to "static-list.example.com",
                "tls" to mapOf(
                    "enabled" to true,
                    "ech" to mapOf(
                        "enabled" to true,
                        "config" to listOf("-----BEGIN ECH CONFIGS-----", "AAA="),
                    ),
                ),
            ),
            // 5. Static ech.config as String -> skipped
            mapOf(
                "tag" to "proxy-static-string",
                "server" to "static-str.example.com",
                "tls" to mapOf(
                    "enabled" to true,
                    "ech" to mapOf(
                        "enabled" to true,
                        "config" to "-----BEGIN ECH CONFIGS-----\nAAA=",
                    ),
                ),
            ),
            // 6. Static ech.config_path -> skipped
            mapOf(
                "tag" to "proxy-static-path",
                "server" to "static-path.example.com",
                "tls" to mapOf(
                    "enabled" to true,
                    "ech" to mapOf(
                        "enabled" to true,
                        "config_path" to "/data/local/tmp/ech.pem",
                    ),
                ),
            ),
            // 7. ech.enabled = false -> skipped
            mapOf(
                "tag" to "proxy-ech-disabled",
                "server" to "no-ech.example.com",
                "tls" to mapOf(
                    "enabled" to true,
                    "ech" to mapOf("enabled" to false),
                ),
            ),
            // 8. tls.enabled = false -> skipped
            mapOf(
                "tag" to "proxy-tls-disabled",
                "server" to "no-tls.example.com",
                "tls" to mapOf(
                    "enabled" to false,
                    "ech" to mapOf("enabled" to true),
                ),
            ),
            // 9. IPv4 literal server without domain SNI -> skipped
            mapOf(
                "tag" to "proxy-ipv4-only",
                "server" to "8.216.61.208",
                "tls" to mapOf(
                    "enabled" to true,
                    "ech" to mapOf("enabled" to true),
                ),
            ),
            // 10. IPv6 literal server without domain SNI -> skipped
            mapOf(
                "tag" to "proxy-ipv6-only",
                "server" to "[2606:4700::1]",
                "tls" to mapOf(
                    "enabled" to true,
                    "ech" to mapOf("enabled" to true),
                ),
            ),
            // 11. Duplicate domain -> deduplicated
            mapOf(
                "tag" to "proxy-dup",
                "server" to "1.1.1.1",
                "tls" to mapOf(
                    "enabled" to true,
                    "ech" to mapOf(
                        "enabled" to true,
                        "query_server_name" to "www.britannica.com",
                    ),
                ),
            ),
        )

        val domains = extractEchQueryDomains(outboundMaps)
        assertEquals(listOf("www.britannica.com", "cdn.example.org", "edge.example.net"), domains)

        val text = resolveSourceFile("src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt").readText()
        val predefinedIdx = text.indexOf("action = \"predefined\"")
        val echExtractIdx = text.indexOf("val echQueryDomains = extractEchQueryDomains(outboundMaps)")
        val echRuleIdx = text.indexOf("domain = echQueryDomains", echExtractIdx)
        val echDirectIdx = text.indexOf("server = \"dns-direct\"", echRuleIdx)

        assertTrue("Expected predefined HTTPS/SVCB DNS rule", predefinedIdx >= 0)
        assertTrue("Expected ECH domain extraction after predefined rule so add(0, ...) prepends before it", echExtractIdx > predefinedIdx)
        assertTrue("Expected ECH exemption rule referencing domain = echQueryDomains", echRuleIdx > echExtractIdx)
        assertTrue("Expected ECH exemption rule routed explicitly via server = \"dns-direct\"", echDirectIdx > echRuleIdx)
    }
}
