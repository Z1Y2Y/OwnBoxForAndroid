package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RouteProbeWarningTest {
    @Test
    fun onlyEnabledExplicitRouteRulesInCustomModeTriggerWarnings() {
        val rules = listOf(
            RuleEntity(id = 1L, enabled = true, outbound = 42L),
            RuleEntity(id = 2L, enabled = false, outbound = 66L),
            RuleEntity(id = 3L, enabled = true, outbound = 84L),
            RuleEntity(id = 4L, enabled = true, outbound = -1L),
            RuleEntity(id = 5L, enabled = true, outbound = 99L, config = """{"action":"reject"}"""),
        )

        assertEquals(setOf(42L, 84L), RouteProbeWarning.targetProfileIds(rules, smartMode = false, globalMode = false))
        assertTrue(RouteProbeWarning.targetProfileIds(rules, smartMode = true, globalMode = false).isEmpty())
        assertTrue(RouteProbeWarning.targetProfileIds(rules, smartMode = false, globalMode = true).isEmpty())
    }

    @Test
    fun urlAndTcpFailureUiUsesTheReadonlyNoFallbackWarning() {
        val fragment = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()
        val urlMethod = fragment.substringAfter("fun urlTestSingle(profile: ProxyEntity)")
            .substringBefore("\n    fun tcpPingSingle(profile: ProxyEntity)")

        assertTrue(fragment.contains("profile.status in 2..3 && profile.id in routeFailureWarningTargets"))
        assertTrue(fragment.contains("safeSnackbar(R.string.route_probe_failed_no_fallback)"))
        assertTrue(urlMethod.contains("showRouteProbeFailureWarning(profile)"))
        assertEquals(2, fragment.split("test.routeFailureWarningTargets = routeProbeWarningTargets()").size - 1)
    }

    @Test
    fun singleNodeTcpPingMenuAndFailureShareTheSpecifiedOutboundWarning() {
        val fragment = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()
        val menu = sourceFile("src/main/res/menu/profile_share_menu.xml").readText()
        val tcpMethod = fragment.substringAfter("fun tcpPingSingle(profile: ProxyEntity)")
            .substringBefore("\n    @OptIn(DelicateCoroutinesApi::class)")

        assertTrue(menu.contains("@+id/action_tcp_ping"))
        assertTrue(menu.contains("@string/connection_test_tcp_ping_single"))
        assertTrue(fragment.contains("R.id.action_tcp_ping ->"))
        assertTrue(fragment.contains("?.tcpPingSingle(entity)"))
        assertTrue(tcpMethod.contains("TcpPing().doTest(profile)"))
        assertTrue(tcpMethod.contains("profile.status = 3"))
        assertTrue(tcpMethod.contains("showRouteProbeFailureWarning(profile)"))
        assertTrue(fragment.contains("private suspend fun showRouteProbeFailureWarning(profile: ProxyEntity)"))
    }

    private fun sourceFile(relative: String): File =
        sequenceOf(File(relative), File("app", relative), File("../app", relative))
            .firstOrNull { it.exists() } ?: File(relative)
}
