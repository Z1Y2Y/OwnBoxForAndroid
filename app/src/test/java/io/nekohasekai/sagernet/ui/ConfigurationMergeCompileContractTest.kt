package io.nekohasekai.sagernet.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConfigurationMergeCompileContractTest {
    private fun sourceFile(path: String): File {
        val candidates = listOf(File(path), File("app", path), File("../app", path))
        return candidates.firstOrNull(File::isFile)
            ?: error("Source file not found: $path")
    }

    @Test
    fun appearanceSettingsOwnsItsIconTintAndCompatSwitchDependencies() {
        val source = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/AppearanceSettingsFragment.kt").readText()
        assertTrue(source.contains("import androidx.preference.SwitchPreferenceCompat"))
        assertTrue(source.contains("private fun tintPreferenceIcons(group: PreferenceGroup, color: Int)"))
        assertTrue(source.contains("DrawableCompat.setTint(tinted, color)"))
    }

    @Test
    fun toolbarHeaderIdsAndSubscriptionCardDefaultRemainWellFormed() {
        val menu = sourceFile("src/main/res/menu/add_profile_menu.xml").readText()
        val appearance = sourceFile("src/main/res/xml/settings_appearance.xml").readText()
        listOf(
            "action_header_order",
            "action_header_layout",
            "action_header_card_style",
            "action_header_card_view_mode",
        ).forEach { assertTrue("Missing menu header ID: $it", menu.contains("@+id/$it")) }
        val subscriptionCard = appearance.substringBefore("app:key=\"show_subscription_info_card\"")
            .substringAfterLast("<SwitchPreferenceCompat")
            .substringBefore("/>")
        assertTrue(subscriptionCard.contains("app:defaultValue=\"false\""))
        assertFalse(subscriptionCard.contains("app:defaultValue=\"true\""))
    }

    @Test
    fun configurationProfileLookupReorderAndSubscriptionCardBindingsAreDeclared() {
        val source = sourceFile("src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()
        assertTrue(source.contains("fun locateProfile(profileId: Long, highlight: Boolean = true)"))
        assertTrue(source.contains("private fun scrollToAndHighlight(position: Int, highlight: Boolean)"))
        assertTrue(source.contains("var snapshotIds: List<Long>"))
        assertTrue(source.contains("var snapshotOrders: Map<Long, Long>"))
        assertTrue(source.contains("fun saveMove()"))
        assertTrue(source.contains("fun cancelMove()"))
        assertTrue(source.contains("R.id.btn_card_update"))
        assertTrue(source.contains("snapshot.isUnlimited"))
        assertTrue(source.contains("DataStore.configurationStore.getBoolean(Key.SHOW_SUBSCRIPTION_INFO_CARD, false)"))
    }
}
