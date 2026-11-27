package io.nekohasekai.sagernet.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppRoutePolicyTest {
    @Test
    fun missingNewSettingLeavesLegacySelectionSemanticsUnchanged() {
        val packageName = "com.example.app"
        val policies = PerAppRoutePolicies.decode("")
        val legacySelected = setOf(packageName)

        assertTrue(policies.isEmpty())
        assertEquals(
            PerAppRoutePolicy.FORCE_PROXY,
            PerAppRoutePolicies.effective(packageName, policies, legacySelected, true, false),
        )
        assertEquals(
            PerAppRoutePolicy.FORCE_DIRECT,
            PerAppRoutePolicies.effective(packageName, policies, legacySelected, true, true),
        )
        assertNull(PerAppRoutePolicies.effective(packageName, policies, legacySelected, false, false))
        assertNull(PerAppRoutePolicies.effective("com.example.other", policies, legacySelected, true, false))
    }

    @Test
    fun explicitPolicyOverridesLegacySelectionWithoutRewritingIt() {
        val packageName = "com.example.browser"
        val policies = mapOf(packageName to PerAppRoutePolicy.FOLLOW_SMART)

        assertEquals(
            PerAppRoutePolicy.FOLLOW_SMART,
            PerAppRoutePolicies.effective(packageName, policies, setOf(packageName), true, false),
        )
        // The new setting serializes only the explicit override; legacy individual/bypass keys are not inputs.
        assertEquals("com.example.browser=follow_smart", PerAppRoutePolicies.encode(policies))
    }

    @Test
    fun explicitForcePoliciesOverrideLegacyProxyOrBypassMode() {
        val packageName = "com.example.app"
        assertEquals(
            PerAppRoutePolicy.FORCE_DIRECT,
            PerAppRoutePolicies.effective(
                packageName,
                mapOf(packageName to PerAppRoutePolicy.FORCE_DIRECT),
                setOf(packageName),
                proxyAppsEnabled = true,
                bypassMode = false,
            ),
        )
        assertEquals(
            PerAppRoutePolicy.FORCE_PROXY,
            PerAppRoutePolicies.effective(
                packageName,
                mapOf(packageName to PerAppRoutePolicy.FORCE_PROXY),
                setOf(packageName),
                proxyAppsEnabled = true,
                bypassMode = true,
            ),
        )
    }

    @Test
    fun policyStorageRoundTripsAndIgnoresUnknownEntries() {
        val policies = linkedMapOf(
            "com.example.browser" to PerAppRoutePolicy.FOLLOW_SMART,
            "com.example.proxy" to PerAppRoutePolicy.FORCE_PROXY,
            "com.example.direct" to PerAppRoutePolicy.FORCE_DIRECT,
        )
        val serialized = PerAppRoutePolicies.encode(policies)
        val decoded = PerAppRoutePolicies.decode("$serialized\ninvalid=line\ncom.example.unknown=future")

        assertEquals(policies, decoded)
    }

    @Test
    fun allExplicitPoliciesEnterVpnSoTheirRouteAndDnsActionsCanApply() {
        val policies = mapOf(
            "com.example.follow" to PerAppRoutePolicy.FOLLOW_SMART,
            "com.example.proxy" to PerAppRoutePolicy.FORCE_PROXY,
            "com.example.direct" to PerAppRoutePolicy.FORCE_DIRECT,
        )

        val bypassList = PerAppRoutePolicies.adjustVpnSelection(
            baseSelection = setOf("com.example.direct", "com.example.legacy"),
            overrides = policies,
            bypassMode = true,
        )
        assertFalse("follow-smart app must be captured", "com.example.follow" in bypassList)
        assertFalse("force-proxy app must be captured", "com.example.proxy" in bypassList)
        assertFalse("force-direct app must be captured for its app route", "com.example.direct" in bypassList)
        assertTrue("legacy bypass selection remains", "com.example.legacy" in bypassList)

        val allowList = PerAppRoutePolicies.adjustVpnSelection(
            baseSelection = setOf("com.example.legacy", "com.example.direct"),
            overrides = policies,
            bypassMode = false,
        )
        assertTrue("follow-smart app must be allowed into VPN", "com.example.follow" in allowList)
        assertTrue("force-proxy app must be allowed into VPN", "com.example.proxy" in allowList)
        assertTrue("force-direct app must be allowed into VPN for its app route", "com.example.direct" in allowList)
        assertTrue("legacy allow selection remains", "com.example.legacy" in allowList)
    }

    @Test
    fun updatingOnePackageUpdatesItsWholeSharedUidGroup() {
        val initial = mapOf("com.example.old" to PerAppRoutePolicy.FORCE_DIRECT)
        val updated = PerAppRoutePolicies.updateUidGroup(
            existing = initial,
            packageNames = setOf("com.example.app", "com.example.shared"),
            policy = PerAppRoutePolicy.FOLLOW_SMART,
        )

        assertEquals(PerAppRoutePolicy.FOLLOW_SMART, updated["com.example.app"])
        assertEquals(PerAppRoutePolicy.FOLLOW_SMART, updated["com.example.shared"])
        assertEquals(PerAppRoutePolicy.FORCE_DIRECT, updated["com.example.old"])

        val reset = PerAppRoutePolicies.updateUidGroup(updated, setOf("com.example.app", "com.example.shared"), null)
        assertFalse(reset.containsKey("com.example.app"))
        assertFalse(reset.containsKey("com.example.shared"))
    }
}
