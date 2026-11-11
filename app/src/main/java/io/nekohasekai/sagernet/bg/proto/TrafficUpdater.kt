package io.nekohasekai.sagernet.bg.proto

class TrafficUpdater internal constructor(
    private val queryStats: (String, String) -> Long,
    val items: List<TrafficLooperData>, // includes direct and bypass
) {

    constructor(box: libcore.BoxInstance, items: List<TrafficLooperData>) : this(
        queryStats = { tag, direction -> box.queryStats(tag, direction) },
        items = items,
    )

    class TrafficLooperData(
        // Don't associate proxyEntity
        var tag: String,
        var tx: Long = 0,
        var rx: Long = 0,
        var txBase: Long = 0,
        var rxBase: Long = 0,
        var txRate: Long = 0,
        var rxRate: Long = 0,
        var lastUpdate: Long = 0,
        var ignore: Boolean = false,
        var hasTrafficDelta: Boolean = false,
    )

    private fun updateOne(item: TrafficLooperData): TrafficLooperData {
        // last update
        val now = System.currentTimeMillis()
        val interval = now - item.lastUpdate
        item.lastUpdate = now
        if (interval <= 0) {
            item.rxRate = 0
            item.txRate = 0
            return TrafficLooperData(tag = item.tag)
        }

        // query
        val tx = queryStats(item.tag, "uplink")
        val rx = queryStats(item.tag, "downlink")

        // add diff
        item.rx += rx
        item.tx += tx
        item.rxRate = rx * 1000 / interval
        item.txRate = tx * 1000 / interval

        // return diff
        return TrafficLooperData(
            tag = item.tag,
            rx = rx,
            tx = tx,
            rxRate = item.rxRate,
            txRate = item.txRate,
        )
    }

    fun updateAll() {
        val updated = mutableMapOf<String, TrafficLooperData>() // diffs
        items.forEach { item ->
            item.hasTrafficDelta = false
            if (item.ignore) return@forEach
            val diff = updated[item.tag]
            // query a tag only once
            if (diff == null) {
                val newDiff = updateOne(item)
                updated[item.tag] = newDiff
                item.hasTrafficDelta = newDiff.rx != 0L || newDiff.tx != 0L
            } else {
                item.rx += diff.rx
                item.tx += diff.tx
                item.rxRate = diff.rxRate
                item.txRate = diff.txRate
                item.hasTrafficDelta = diff.rx != 0L || diff.tx != 0L
            }
        }
//        Logs.d(JavaUtil.gson.toJson(items))
//        Logs.d(JavaUtil.gson.toJson(updated))
    }
}
