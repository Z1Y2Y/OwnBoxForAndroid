package io.nekohasekai.sagernet.ui

internal object ConnectionChainFormatter {
    fun format(chains: List<String>, activeOutbound: String): String {
        val displayChain = chains.filter(String::isNotBlank).toMutableList()
        val activeNode = activeOutbound.trim().takeIf { it.isNotEmpty() && it != "null" }
        if (activeNode != null && activeNode !in displayChain) {
            displayChain += activeNode
        }
        return displayChain.joinToString(" → ")
    }
}
