package io.nekohasekai.sagernet.fmt

/** Match dimensions are emitted as independent sing-box rules (OR), never inferred from names. */
internal enum class RouteMatchKind {
    DOMAIN,
    IP,
    APP,
    FALLBACK,
}

internal fun routeMatchKinds(
    hasDomain: Boolean,
    hasIp: Boolean,
    hasApp: Boolean,
): List<RouteMatchKind> {
    val kinds = mutableListOf<RouteMatchKind>()
    if (hasDomain) kinds += RouteMatchKind.DOMAIN
    if (hasIp) kinds += RouteMatchKind.IP
    if (hasApp) kinds += RouteMatchKind.APP
    if (kinds.isEmpty()) kinds += RouteMatchKind.FALLBACK
    return kinds
}

/** Resolve only the outbound explicitly selected on a rule. */
internal fun resolveRouteRuleOutbound(
    outboundId: Long,
    currentProxyId: Long,
    mainProxyTag: String,
    tagMap: Map<Long, String>,
): String = when (outboundId) {
    0L -> mainProxyTag
    -1L -> TAG_BYPASS
    -2L -> TAG_BLOCK
    else -> if (outboundId == currentProxyId) mainProxyTag else tagMap[outboundId].orEmpty()
}
