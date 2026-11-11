package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BalancerMemberResolverTest {

    @Test
    fun nodeListUsesConfiguredMemberOrderAndFiltersInvalidCandidates() {
        val bean = BalancerBean().apply {
            balancerType = BalancerBean.TYPE_LIST
            proxies = listOf(3L, 1L, 2L, 4L)
            frontProxy = 2L
        }
        val candidates = listOf(
            ProxyEntity(id = 1L, groupId = 10L, type = ProxyEntity.TYPE_SS),
            ProxyEntity(id = 2L, groupId = 10L, type = ProxyEntity.TYPE_SS),
            ProxyEntity(id = 3L, groupId = 10L, type = ProxyEntity.TYPE_SS),
            ProxyEntity(id = 4L, groupId = 20L, type = ProxyEntity.TYPE_BALANCER),
        )

        val members = BalancerMemberResolver.select(99L, bean, candidates) { false }

        assertEquals(listOf(3L, 1L), members.map { it.id })
    }

    @Test
    fun groupMemberResolverAppliesGroupAndDisabledGroupFilters() {
        val bean = BalancerBean().apply {
            balancerType = BalancerBean.TYPE_GROUP
            targetGroupIds = listOf(10L, 20L)
        }
        val candidates = listOf(
            ProxyEntity(id = 1L, groupId = 10L, type = ProxyEntity.TYPE_SS),
            ProxyEntity(id = 2L, groupId = 20L, type = ProxyEntity.TYPE_SS),
            ProxyEntity(id = 3L, groupId = 30L, type = ProxyEntity.TYPE_SS),
        )

        val members = BalancerMemberResolver.select(99L, bean, candidates) { it == 20L }

        assertEquals(listOf(1L), members.map { it.id })
    }

    @Test
    fun autoHideCanKeepBalancerWhenAnyMemberIsUntestedOrHealthy() {
        val group = ProxyEntity(id = 100L, type = ProxyEntity.TYPE_BALANCER, status = 3)
        val failed = ProxyEntity(id = 1L, status = 3)
        val unknown = ProxyEntity(id = 2L, status = 0)
        val healthy = ProxyEntity(id = 3L, status = 1)

        assertTrue(BalancerMemberResolver.hasAvailableMember(listOf(failed, unknown)))
        assertTrue(BalancerMemberResolver.hasAvailableMember(listOf(failed, healthy)))
        assertFalse(BalancerMemberResolver.hasAvailableMember(listOf(failed)))
        assertEquals(3, group.status)
    }

    @Test
    fun groupProbeUsesFastestSuccessfulMemberAndFailsOnlyWhenNoneSucceeded() {
        val success = BalancerMemberResolver.summarize(
            listOf(
                BalancerMemberResolver.ProbeOutcome(status = 3, error = "unreachable"),
                BalancerMemberResolver.ProbeOutcome(status = 1, ping = 83),
                BalancerMemberResolver.ProbeOutcome(status = 1, ping = 41),
            )
        )
        assertEquals(1, success.status)
        assertEquals(41, success.ping)
        assertEquals(null, success.error)

        val failed = BalancerMemberResolver.summarize(
            listOf(BalancerMemberResolver.ProbeOutcome(status = 3, error = "timeout"))
        )
        assertEquals(3, failed.status)
        assertEquals(null, failed.ping)
        assertEquals("timeout", failed.error)
    }

    @Test
    fun appStickyIsSupportedForBothBalancerTypesAndRemainsOptIn() {
        val bean = BalancerBean().apply { stickyByApp = true }
        bean.balancerType = BalancerBean.TYPE_LIST
        assertTrue(shouldEnableStickyByApp(bean))
        assertEquals(
            true,
            buildLeastPingBalancerOutbound(
                memberTags = listOf("n1", "n2"),
                testUrl = "https://www.gstatic.com/generate_204",
                intervalSec = 60,
                toleranceMs = 50,
                customTag = "list-balancer",
                stickyByApp = shouldEnableStickyByApp(bean),
            )
                .asMap()["sticky_by_app"]
        )

        bean.balancerType = BalancerBean.TYPE_GROUP
        assertTrue(shouldEnableStickyByApp(bean))
        bean.stickyByApp = false
        assertFalse(shouldEnableStickyByApp(bean))
    }
}
