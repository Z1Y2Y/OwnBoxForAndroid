package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

/** IDs of explicit route-rule outbounds that should receive a no-fallback probe warning. */
internal object RouteProbeWarning {
    fun targetProfileIds(
        rules: List<RuleEntity>,
        smartMode: Boolean,
        globalMode: Boolean,
    ): Set<Long> {
        if (smartMode || globalMode) return emptySet()

        return rules.asSequence()
            .filter { rule ->
                rule.enabled && rule.outbound > 0 &&
                    runCatching { RouteRuleEditor.action(rule.config, rule.outbound) == "route" }
                        .getOrDefault(false)
            }
            .map { it.outbound }
            .toSet()
    }
}
