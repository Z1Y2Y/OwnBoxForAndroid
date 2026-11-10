package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity

class ProfileSelectActivity : ThemedActivity(R.layout.layout_profile_select),
    ConfigurationFragment.SelectCallback {

    companion object {
        const val EXTRA_SELECTED = "selected"
        const val EXTRA_PROFILE_ID = "id"
        const val EXTRA_MULTI_SELECT = "multi_select"
        const val EXTRA_SELECTED_IDS = "selected_ids"
        const val EXTRA_EXCLUDE_IDS = "exclude_ids"
        const val EXTRA_PROFILE_IDS = "profile_ids"
    }

    private var isMultiSelect = false
    private val selectedIds = LinkedHashSet<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selected = intent.getParcelableExtra<ProxyEntity>(EXTRA_SELECTED)
        isMultiSelect = intent.getBooleanExtra(EXTRA_MULTI_SELECT, false)
        val initialSelected = intent.getLongArrayExtra(EXTRA_SELECTED_IDS)
        val excludeIds = intent.getLongArrayExtra(EXTRA_EXCLUDE_IDS)
        if (initialSelected != null) selectedIds.addAll(initialSelected.toList())

        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_holder,
                ConfigurationFragment(
                    select = true,
                    selectedItem = selected,
                    titleRes = if (isMultiSelect) R.string.select_multi_profile else R.string.select_profile,
                    multiSelect = isMultiSelect,
                    initialSelectedIds = initialSelected,
                    excludedIds = excludeIds,
                ),
            )
            .commitAllowingStateLoss()
    }

    fun confirmSelection() {
        if (!isMultiSelect) return
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_PROFILE_IDS, selectedIds.toLongArray())
        })
        finish()
    }

    override fun returnProfile(profileId: Long) {
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_PROFILE_ID, profileId)
        })
        finish()
    }

    override fun onProfileToggled(profileId: Long, isSelected: Boolean, totalSelected: Int) {
        if (isSelected) selectedIds.add(profileId) else selectedIds.remove(profileId)
    }
}
