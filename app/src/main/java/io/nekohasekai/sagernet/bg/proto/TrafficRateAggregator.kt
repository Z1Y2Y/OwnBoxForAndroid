package io.nekohasekai.sagernet.bg.proto

internal object TrafficRateAggregator {

    fun proxyRates(
        itemsById: Map<Long, TrafficUpdater.TrafficLooperData>,
        balancerMemberIds: Set<Long>,
        mainEntityId: Long,
    ): Pair<Long, Long> {
        var txRate = 0L
        var rxRate = 0L
        itemsById.forEach { (id, item) ->
            if (id > 0L && (id == mainEntityId || id !in balancerMemberIds) && !item.ignore) {
                txRate += item.txRate
                rxRate += item.rxRate
            }
        }
        return txRate to rxRate
    }

    fun directRates(
        direct: TrafficUpdater.TrafficLooperData,
        bypass: TrafficUpdater.TrafficLooperData,
        enabled: Boolean,
    ): Pair<Long, Long> = if (enabled) {
        (direct.txRate + bypass.txRate) to (direct.rxRate + bypass.rxRate)
    } else {
        0L to 0L
    }
}
