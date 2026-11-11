package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.internal.BalancerBean

internal object BalancerMemberResolver {

    data class ProbeOutcome(
        val status: Int,
        val ping: Int = 0,
        val error: String? = null,
    )

    data class ProbeSummary(
        val status: Int,
        val ping: Int? = null,
        val error: String? = null,
    )

    fun load(owner: ProxyEntity, bean: BalancerBean): List<ProxyEntity> {
        val candidates = if (bean.balancerType == BalancerBean.TYPE_GROUP) {
            val groupIds = when {
                bean.targetGroupIds.isNotEmpty() -> bean.targetGroupIds
                bean.targetGroupId > 0L -> listOf(bean.targetGroupId)
                else -> emptyList()
            }
            groupIds.flatMap { groupId -> SagerDatabase.proxyDao.getByGroup(groupId) }
                .distinctBy { it.id }
        } else {
            val requestedIds = bean.proxies.orEmpty()
            val byId = SagerDatabase.proxyDao.getEntities(requestedIds).associateBy { it.id }
            requestedIds.mapNotNull { byId[it] }
        }
        return select(
            ownerId = owner.id,
            bean = bean,
            candidates = candidates,
            isGroupDisabled = DataStore::isGroupDisabled,
        )
    }

    /** Pure member-selection path shared by config building, manual probes, and tests. */
    fun select(
        ownerId: Long,
        bean: BalancerBean,
        candidates: List<ProxyEntity>,
        isGroupDisabled: (Long) -> Boolean,
    ): List<ProxyEntity> {
        val base = if (bean.balancerType == BalancerBean.TYPE_GROUP) {
            val targetGroupIds = when {
                bean.targetGroupIds.isNotEmpty() -> bean.targetGroupIds.toSet()
                bean.targetGroupId > 0L -> setOf(bean.targetGroupId)
                else -> emptySet()
            }
            var selected = candidates.filter { it.groupId in targetGroupIds }
            if (!bean.nameInclude.isNullOrBlank()) {
                val regex = runCatching { Regex(bean.nameInclude) }.getOrNull()
                if (regex != null) selected = selected.filter { regex.containsMatchIn(it.displayName()) }
            }
            if (!bean.nameExclude.isNullOrBlank()) {
                val regex = runCatching { Regex(bean.nameExclude) }.getOrNull()
                if (regex != null) selected = selected.filterNot { regex.containsMatchIn(it.displayName()) }
            }
            selected
        } else {
            val byId = candidates.associateBy { it.id }
            bean.proxies.orEmpty().mapNotNull { byId[it] }
        }

        return base.distinctBy { it.id }.filter { member ->
            member.id != ownerId &&
                member.id != bean.frontProxy &&
                member.id != bean.landingProxy &&
                member.type != ProxyEntity.TYPE_BALANCER &&
                !isGroupDisabled(member.groupId)
        }
    }

    fun hasAvailableMember(members: List<ProxyEntity>): Boolean =
        members.any { it.status == 0 || it.status == 1 }

    fun summarize(outcomes: List<ProbeOutcome>): ProbeSummary {
        val successes = outcomes.filter { it.status == 1 && it.ping > 0 }
        if (successes.isNotEmpty()) {
            return ProbeSummary(status = 1, ping = successes.minOf { it.ping })
        }

        val status = if (outcomes.isNotEmpty() && outcomes.all { it.status == 2 }) 2 else 3
        val details = outcomes.mapNotNull { it.error?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .take(3)
            .joinToString("; ")
            .take(240)
        return ProbeSummary(
            status = status,
            error = details.ifBlank { "No eligible strategy-group members" },
        )
    }
}

internal fun shouldEnableStickyByApp(bean: BalancerBean): Boolean =
    bean.stickyByApp && bean.balancerType in setOf(BalancerBean.TYPE_LIST, BalancerBean.TYPE_GROUP)
