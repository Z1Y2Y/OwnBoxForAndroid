package io.nekohasekai.sagernet.route

/**
 * Additive per-app overrides used only while Smart Routing is active.
 * A missing entry intentionally preserves the legacy app-list behavior.
 */
enum class PerAppRoutePolicy(val storageValue: String) {
    FOLLOW_SMART("follow_smart"),
    FORCE_PROXY("force_proxy"),
    FORCE_DIRECT("force_direct");

    companion object {
        fun fromStorage(value: String): PerAppRoutePolicy? =
            entries.firstOrNull { it.storageValue == value }
    }
}

object PerAppRoutePolicies {
    /** Parse `package.name=policy` lines without touching legacy app-list preferences. */
    fun decode(serialized: String): LinkedHashMap<String, PerAppRoutePolicy> {
        val result = LinkedHashMap<String, PerAppRoutePolicy>()
        serialized.lineSequence().forEach { line ->
            val separator = line.indexOf('=')
            if (separator <= 0 || separator == line.lastIndex) return@forEach
            val packageName = line.substring(0, separator).trim()
            val policy = PerAppRoutePolicy.fromStorage(line.substring(separator + 1).trim())
            if (packageName.isNotEmpty() && policy != null) result[packageName] = policy
        }
        return result
    }

    fun encode(policies: Map<String, PerAppRoutePolicy>): String =
        policies.toSortedMap().entries.joinToString("\n") { (packageName, policy) ->
            "$packageName=${policy.storageValue}"
        }

    /** Keep packages sharing an Android UID on one policy because VPN capture is UID-scoped. */
    fun updateUidGroup(
        existing: Map<String, PerAppRoutePolicy>,
        packageNames: Set<String>,
        policy: PerAppRoutePolicy?,
    ): LinkedHashMap<String, PerAppRoutePolicy> = LinkedHashMap(existing).apply {
        for (packageName in packageNames) {
            if (policy == null) remove(packageName) else put(packageName, policy)
        }
    }

    /**
     * Resolve an explicit override first. With no override, translate the old selected-app list
     * exactly as before: selected apps are forced direct in bypass mode and forced proxy otherwise.
     */
    fun effective(
        packageName: String,
        overrides: Map<String, PerAppRoutePolicy>,
        legacySelectedPackages: Set<String>,
        proxyAppsEnabled: Boolean,
        bypassMode: Boolean,
    ): PerAppRoutePolicy? = overrides[packageName] ?:
        if (proxyAppsEnabled && packageName in legacySelectedPackages) {
            if (bypassMode) PerAppRoutePolicy.FORCE_DIRECT else PerAppRoutePolicy.FORCE_PROXY
        } else {
            null
        }

    /**
     * Ensure explicitly managed apps enter the VPN. Their route action (including FORCE_DIRECT)
     * must be applied by the higher-priority app route rather than bypassing route/DNS processing.
     */
    fun adjustVpnSelection(
        baseSelection: Set<String>,
        overrides: Map<String, PerAppRoutePolicy>,
        bypassMode: Boolean,
    ): Set<String> = baseSelection.toMutableSet().apply {
        for (packageName in overrides.keys) {
            if (bypassMode) remove(packageName) else add(packageName)
        }
    }
}
