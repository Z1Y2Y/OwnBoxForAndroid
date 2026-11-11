package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficRateAggregatorTest {

    @Test
    fun proxyRateUsesBalancerWrapperAndDoesNotSumMemberCounters() {
        val wrapper = TrafficUpdater.TrafficLooperData(
            tag = "proxy",
            txRate = 500L,
            rxRate = 700L,
        )
        val memberA = TrafficUpdater.TrafficLooperData(
            tag = "member-a",
            txRate = 4000L,
            rxRate = 6000L,
        )
        val memberB = TrafficUpdater.TrafficLooperData(
            tag = "member-b",
            txRate = 3000L,
            rxRate = 2000L,
        )

        val rates = TrafficRateAggregator.proxyRates(
            itemsById = mapOf(10L to wrapper, 11L to memberA, 12L to memberB),
            balancerMemberIds = setOf(10L, 11L, 12L),
            mainEntityId = 10L,
        )

        assertEquals(500L to 700L, rates)
    }

    @Test
    fun directSpeedCombinesDirectAndBypassAndCanBeDisabled() {
        val direct = TrafficUpdater.TrafficLooperData(tag = "direct", txRate = 11L, rxRate = 23L)
        val bypass = TrafficUpdater.TrafficLooperData(tag = "bypass", txRate = 5L, rxRate = 7L)

        assertEquals(16L to 30L, TrafficRateAggregator.directRates(direct, bypass, enabled = true))
        assertEquals(0L to 0L, TrafficRateAggregator.directRates(direct, bypass, enabled = false))
    }
}
