package io.nekohasekai.sagernet.ui

internal object ConnectionChainFormatter {
    /**
     * test5 (F2): the display chain — raw chains plus the actually-hit member
     * (activeOutbound) when it is not already in the chain. Single source of
     * truth for hops: both parse sites store this list as chainsList, so the
     * hop chips/cards, the chain line and search all see the same N+1 hops.
     * (Splitting the display string back apart would be fragile — a hop name
     * can itself contain " → ".)
     */
    fun chainList(chains: List<String>, activeOutbound: String): List<String> {
        val displayChain = chains.filter(String::isNotBlank).toMutableList()
        val activeNode = activeOutbound.trim().takeIf { it.isNotEmpty() && it != "null" }
        if (activeNode != null && activeNode !in displayChain) {
            displayChain += activeNode
        }
        return displayChain
    }

    fun format(chains: List<String>, activeOutbound: String): String =
        chainList(chains, activeOutbound).joinToString(" → ")
}
