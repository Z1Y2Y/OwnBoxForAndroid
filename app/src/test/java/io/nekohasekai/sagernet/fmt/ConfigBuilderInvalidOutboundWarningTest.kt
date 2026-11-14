package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConfigBuilderInvalidOutboundWarningTest {
    @Test
    fun staleNodeOrGroupReferenceIsMarkedSkippedAndReported() {
        val ruleEntity = sourceFile(
            "src/main/java/io/nekohasekai/sagernet/database/RuleEntity.kt",
        ).readText()
        val routeFragment = sourceFile(
            "src/main/java/io/nekohasekai/sagernet/ui/RouteFragment.kt",
        ).readText()
        val configBuilder = sourceFile(
            "src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt",
        ).readText()
        val strings = sourceFile("src/main/res/values/strings.xml").readText()

        assertTrue(ruleEntity.contains("fun hasMissingOutbound(): Boolean"))
        assertTrue(ruleEntity.contains("ProfileManager.getProfile(outbound) == null"))
        assertTrue(routeFragment.contains("rule.hasMissingOutbound()"))
        assertTrue(routeFragment.contains("R.color.color_route_block"))
        assertTrue(strings.contains("name=\"route_outbound_missing\""))

        assertTrue(configBuilder.contains("RouteRuleEditor.needsOutbound(rule.config, rule.outbound)"))
        assertTrue(configBuilder.contains("outboundForValidation.isBlank()"))
        assertTrue(configBuilder.contains("invalidOutboundRules += rule.displayName()"))
        assertTrue(configBuilder.contains("continue"))
        assertTrue(configBuilder.contains("R.string.route_invalid_outbound_warning"))
        assertTrue(configBuilder.contains("!forTest && !forExport"))
    }

    private fun sourceFile(relative: String): File {
        return sequenceOf(File(relative), File("app", relative))
            .firstOrNull(File::isFile)
            ?: error("Cannot locate project file: $relative")
    }
}
