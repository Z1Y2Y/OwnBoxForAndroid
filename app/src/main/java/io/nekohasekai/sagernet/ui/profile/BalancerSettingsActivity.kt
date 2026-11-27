package io.nekohasekai.sagernet.ui.profile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatSpinner
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.internal.BalancerMemberResolver
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.widget.OutboundPreference
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import java.util.Locale

class BalancerSettingsActivity : ProfileSettingsActivity<BalancerBean>(R.layout.layout_balancer_settings) {

    companion object {
        private const val STATE_ADVANCED_EXPANDED = "balancer_advanced_expanded"
        private const val STATE_LEGACY_TOUCHED = "balancer_legacy_touched"
        private const val ORDER_TIER_BASE = 6001
    }

    private data class PreviewOverrides(
        val country: String? = null,
        val exclude: String? = null,
        val include: String? = null,
        val selectionMode: String? = null,
    )

    private lateinit var frontProxyPreference: OutboundPreference
    private lateinit var landingProxyPreference: OutboundPreference
    private val groupNameMap = HashMap<Long, String>()
    private val tiersList = ArrayList<BalancerBean.PriorityTier>()
    private val tierPrefs = ArrayList<Preference>()
    private var lastTierCounts: List<Int> = emptyList()
    private var refreshMatchSummaryFn: ((PreviewOverrides) -> Unit)? = null
    private var refreshExplicitSummaryFn: (() -> Unit)? = null
    private var refreshNodeListFn: (() -> Unit)? = null
    private var rebuildTierPrefsFn: (() -> Unit)? = null
    private var advancedExpanded = false
    // test5 (N1): true once the user picks from the legacy strategy dropdown
    // this session. init() seeds DataStore.balancerLegacyStrategy with a
    // derived value for display, which must not be mistaken for a user pick
    // at save time — see serialize().
    private var legacyDropdownTouched = false

    val selectProfileForAddFront = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profileId = it.data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0) ?: 0L
            val profile = ProfileManager.getProfile(profileId) ?: return@runOnDefaultDispatcher
            DataStore.balancerFrontProxy = profile.id
            DataStore.dirty = true
            onMainDispatcher {
                if (::frontProxyPreference.isInitialized) {
                    frontProxyPreference.value = OutboundPreference.VALUE_SELECT_PROFILE
                }
                refreshMatchSummaryFn?.invoke(PreviewOverrides())
            }
        }
    }

    val selectProfileForAddLanding = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
            val profileId = it.data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0) ?: 0L
            val profile = ProfileManager.getProfile(profileId) ?: return@runOnDefaultDispatcher
            DataStore.balancerLandingProxy = profile.id
            DataStore.dirty = true
            onMainDispatcher {
                if (::landingProxyPreference.isInitialized) {
                    landingProxyPreference.value = OutboundPreference.VALUE_SELECT_PROFILE
                }
                refreshMatchSummaryFn?.invoke(PreviewOverrides())
            }
        }
    }

    override fun createEntity(): BalancerBean {
        val count = runCatching { SagerDatabase.proxyDao.getByType(ProxyEntity.TYPE_BALANCER).size }.getOrDefault(0) + 1
        return BalancerBean().apply {
            name = String.format(Locale.ROOT, "Auto-Select %02d", count)
            balancerType = BalancerBean.TYPE_MIXED
        }
    }

    val proxyList = ArrayList<ProxyEntity>()

    override fun BalancerBean.init() {
        DataStore.profileName = name
        DataStore.balancerType = balancerType
        val gids = if (targetGroupIds != null && targetGroupIds.isNotEmpty()) {
            targetGroupIds
        } else if (targetGroupId > 0L) {
            listOf(targetGroupId)
        } else {
            emptyList()
        }
        DataStore.balancerTargetGroups = gids.joinToString(",")
        DataStore.balancerTargetGroup = gids.firstOrNull() ?: 0L

        val normalizedSelectionMode = when {
            isFallbackMode() -> BalancerBean.SELECTION_FALLBACK
            isPriorityMode() -> BalancerBean.SELECTION_PRIORITY
            else -> BalancerBean.SELECTION_LATENCY
        }
        DataStore.balancerSelectionMode = normalizedSelectionMode
        DataStore.balancerStrategy = normalizedSelectionMode

        // test3 legacy dropdown seed: stored choice, else derive from the .24
        // fields, else "" (no legacy choice; the .24 fields govern).
        // test5 (N1): this seed is DISPLAY ONLY. It is written to
        // DataStore (profileCacheStore) so the dropdown shows the effective
        // strategy, but serialize() must not read it back as a user pick —
        // only a real dropdown pick (legacyDropdownTouched) counts. A stored
        // blank legacy stays blank at save time.
        DataStore.balancerLegacyStrategy = legacyStrategy?.takeIf { it.isNotBlank() }
            ?: deriveLegacyStrategy() ?: ""
        DataStore.balancerStickyByApp = stickyByApp
        DataStore.balancerPersistStickyByApp = stickyByApp && persistStickyByApp
        DataStore.balancerUseFrontProxy = useFrontProxy
        DataStore.balancerUseLandingProxy = useLandingProxy

        DataStore.balancerBalanceEnabled = balanceEnabled
        DataStore.balancerBalanceMode = if (balanceMode == BalancerBean.BALANCE_PER_CONNECTION) {
            BalancerBean.BALANCE_PER_CONNECTION
        } else {
            BalancerBean.BALANCE_TIMED_ROTATION
        }
        DataStore.balancerBalanceIntervalSec = balanceIntervalSec.takeIf { it > 0 } ?: 30

        DataStore.balancerCountryFilter = countryFilter ?: ""
        DataStore.balancerInterval = interval

        val normalizedTolerance = if (toleranceUnit == "s") {
            tolerance.coerceAtLeast(0) * 1000
        } else {
            tolerance
        }
        DataStore.balancerTolerance = normalizedTolerance
        DataStore.balancerToleranceUnit = "ms"
        DataStore.balancerInterruptOnSwitch = interruptOnSwitch
        DataStore.balancerLearnFromConnections = learnFromConnections
        DataStore.balancerFallbackAutoRevert = fallbackAutoRevert
        DataStore.balancerFallbackMaxFails = fallbackMaxFails.coerceIn(1, 20)

        DataStore.balancerTestUrl = testUrl ?: ""
        DataStore.balancerFrontProxy = frontProxy
        DataStore.balancerFrontProxyTmp = if (frontProxy > 0L) OutboundPreference.VALUE_SELECT_PROFILE.toInt() else 0
        DataStore.balancerLandingProxy = landingProxy
        DataStore.balancerLandingProxyTmp = if (landingProxy > 0L) OutboundPreference.VALUE_SELECT_PROFILE.toInt() else 0
        DataStore.balancerNameExclude = nameExclude ?: ""
        DataStore.balancerNameInclude = nameInclude ?: ""
        DataStore.balancerTiers = BalancerBean.tiersToJson(tiers)
        DataStore.serverProtocol = proxies.joinToString(",")
    }

    override fun BalancerBean.serialize() {
        name = DataStore.profileName
        balancerType = DataStore.balancerType
        val gids = parseGroupIds(DataStore.balancerTargetGroups)
        targetGroupIds = ArrayList(gids)
        targetGroupId = gids.firstOrNull() ?: 0L

        selectionMode = when (DataStore.balancerSelectionMode) {
            BalancerBean.SELECTION_FALLBACK -> BalancerBean.SELECTION_FALLBACK
            BalancerBean.SELECTION_PRIORITY -> BalancerBean.SELECTION_PRIORITY
            else -> BalancerBean.SELECTION_LATENCY
        }
        strategy = selectionMode

        // test5 (N1): the legacy value saved here is the bean's own stored
        // legacyStrategy (captured before any write below), NOT the
        // DataStore value — init() seeds DataStore with a derived choice for
        // display, and that seed is persisted through profileCacheStore.
        // Reading it back would silently adopt the derived choice on a
        // no-touch save: e.g. a blank-legacy bean with latency + timed
        // balance derives consistentHash, which would force latency + the
        // mapping (learnFromConnections on) via applyLegacyChoiceForSave().
        // Only an actual dropdown pick this session (legacyDropdownTouched,
        // set by the dropdown listener) makes the DataStore value count.
        // A fresh bean (new profile) defaults legacyStrategy to leastPing,
        // which is what the seed holds too, so the new-profile flow is
        // unaffected.
        val storedLegacyStrategy = legacyStrategy
        val pickedLegacyStrategy = if (legacyDropdownTouched) {
            when (DataStore.balancerLegacyStrategy) {
                BalancerBean.STRATEGY_LEAST_PING, BalancerBean.STRATEGY_LEAST_LOAD,
                BalancerBean.STRATEGY_RANDOM, BalancerBean.STRATEGY_ROUND_ROBIN,
                BalancerBean.STRATEGY_CONSISTENT_HASH_CAMEL -> DataStore.balancerLegacyStrategy
                BalancerBean.STRATEGY_ROUND_ROBIN_LEGACY -> BalancerBean.STRATEGY_ROUND_ROBIN
                BalancerBean.STRATEGY_CONSISTENT_HASH -> BalancerBean.STRATEGY_CONSISTENT_HASH_CAMEL
                else -> "" // no legacy choice; the .24 fields govern (see BalancerBean)
            }
        } else {
            ""
        }
        // test4: the legacy dropdown is authoritative whenever the user picked
        // a choice: saving with a legacy strategy forces latency (the .24
        // selection-mode row is hidden, so a stored priority/fallback value
        // must not block the dropdown). Blank legacy keeps the stored .24
        // fields untouched.
        legacyStrategy = if (legacyDropdownTouched) {
            pickedLegacyStrategy
        } else {
            storedLegacyStrategy?.takeIf { it.isNotBlank() } ?: ""
        }
        if (legacyStrategy.isNotBlank()) {
            applyLegacyChoiceForSave()
        } else {
            balanceEnabled = DataStore.balancerBalanceEnabled
            balanceMode = if (DataStore.balancerBalanceMode == BalancerBean.BALANCE_PER_CONNECTION) {
                BalancerBean.BALANCE_PER_CONNECTION
            } else {
                BalancerBean.BALANCE_TIMED_ROTATION
            }
        }
        // (With a legacy choice, balanceEnabled/balanceMode keep the
        // dropdown-mapped values from applyLegacyChoiceForSave(); otherwise they
        // are re-read from DataStore in the else branch above.)
        stickyByApp = DataStore.balancerStickyByApp
        persistStickyByApp = stickyByApp && DataStore.balancerPersistStickyByApp
        useFrontProxy = DataStore.balancerUseFrontProxy
        useLandingProxy = DataStore.balancerUseLandingProxy

        balanceIntervalSec = DataStore.balancerBalanceIntervalSec

        countryFilter = DataStore.balancerCountryFilter.trim()
        interval = DataStore.balancerInterval
        tolerance = DataStore.balancerTolerance
        toleranceUnit = "ms"
        interruptOnSwitch = DataStore.balancerInterruptOnSwitch
        learnFromConnections = DataStore.balancerLearnFromConnections
        fallbackAutoRevert = DataStore.balancerFallbackAutoRevert
        fallbackMaxFails = DataStore.balancerFallbackMaxFails.coerceIn(1, 20)

        testUrl = DataStore.balancerTestUrl.trim()
        frontProxy = if (DataStore.balancerFrontProxyTmp == OutboundPreference.VALUE_SELECT_PROFILE.toInt()) {
            DataStore.balancerFrontProxy
        } else {
            -1L
        }
        landingProxy = if (DataStore.balancerLandingProxyTmp == OutboundPreference.VALUE_SELECT_PROFILE.toInt()) {
            DataStore.balancerLandingProxy
        } else {
            -1L
        }
        nameExclude = DataStore.balancerNameExclude
        nameInclude = DataStore.balancerNameInclude
        tiers = BalancerBean.parseTiersJson(DataStore.balancerTiers)
        proxies = proxyList.map { it.id }
        initializeDefaultValues()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ADVANCED_EXPANDED, advancedExpanded)
        outState.putBoolean(STATE_LEGACY_TOUCHED, legacyDropdownTouched)
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.balancer_preferences)

        val frontPref = findPreference<OutboundPreference>("balancerFrontProxy")
        frontPref?.apply {
            frontProxyPreference = this
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            value = DataStore.balancerFrontProxyTmp.toString()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == OutboundPreference.VALUE_SELECT_PROFILE) {
                    selectProfileForAddFront.launch(
                        Intent(this@BalancerSettingsActivity, ProfileSelectActivity::class.java).apply {
                            ProfileManager.getProfile(DataStore.balancerFrontProxy)?.let {
                                putExtra(ProfileSelectActivity.EXTRA_SELECTED, it)
                            }
                        }
                    )
                    false
                } else {
                    runOnMainDispatcher { refreshMatchSummaryFn?.invoke(PreviewOverrides()) }
                    true
                }
            }
        }

        val landingPref = findPreference<OutboundPreference>("balancerLandingProxy")
        landingPref?.apply {
            landingProxyPreference = this
            setEntries(R.array.front_proxy_entry)
            setEntryValues(R.array.front_proxy_value)
            value = DataStore.balancerLandingProxyTmp.toString()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue.toString() == OutboundPreference.VALUE_SELECT_PROFILE) {
                    selectProfileForAddLanding.launch(
                        Intent(this@BalancerSettingsActivity, ProfileSelectActivity::class.java).apply {
                            ProfileManager.getProfile(DataStore.balancerLandingProxy)?.let {
                                putExtra(ProfileSelectActivity.EXTRA_SELECTED, it)
                            }
                        }
                    )
                    false
                } else {
                    runOnMainDispatcher { refreshMatchSummaryFn?.invoke(PreviewOverrides()) }
                    true
                }
            }
        }

        val groupPref = findPreference<Preference>("balancerTargetGroup")
        val explicitNodesPref = findPreference<Preference>("balancerExplicitNodes")
        val localNodeListPref = findPreference<Preference>("balancerNodeList")
        val useFrontProxyPref = findPreference<SwitchPreferenceCompat>("balancerUseFrontProxy")
        val useLandingProxyPref = findPreference<SwitchPreferenceCompat>("balancerUseLandingProxy")
        val allGroups = runCatching { SagerDatabase.groupDao.allGroups() }.getOrDefault(emptyList())
        groupNameMap.clear()
        allGroups.filter { !it.ungrouped }.forEach { groupNameMap[it.id] = it.displayName() }

        fun updateGroupSummary() {
            val selectedGids = parseGroupIds(DataStore.balancerTargetGroups).toSet()
            val matchedGroups = allGroups.filter { selectedGids.contains(it.id) }
            groupPref?.summary = when {
                matchedGroups.isEmpty() -> getString(R.string.balancer_no_group_selected)
                matchedGroups.size <= 2 -> matchedGroups.joinToString(", ") { it.displayName() }
                else -> "${matchedGroups.size}: " + matchedGroups.take(2).joinToString(", ") { it.displayName() } + "..."
            }
        }
        updateGroupSummary()

        fun updateExplicitNodesSummary() {
            explicitNodesPref?.summary = if (proxyList.isEmpty()) {
                getString(R.string.balancer_explicit_nodes_empty)
            } else {
                val previewNames = proxyList.take(3).joinToString(", ") { it.displayName() }
                val suffix = if (proxyList.size > 3) "..." else ""
                getString(R.string.balancer_explicit_nodes_summary, proxyList.size, previewNames + suffix)
            }
        }
        refreshExplicitSummaryFn = { updateExplicitNodesSummary() }
        updateExplicitNodesSummary()

        groupPref?.setOnPreferenceClickListener {
            val currentSelected = parseGroupIds(DataStore.balancerTargetGroups).toMutableSet()
            if (currentSelected.isEmpty() && DataStore.balancerTargetGroup > 0L) {
                currentSelected.add(DataStore.balancerTargetGroup)
            }
            val groupNames = allGroups.map { g ->
                val count = runCatching { SagerDatabase.proxyDao.getByGroup(g.id).size }.getOrDefault(0)
                "${g.displayName()} ($count)"
            }.toTypedArray()
            val checkedStates = allGroups.map { currentSelected.contains(it.id) }.toBooleanArray()

            MaterialAlertDialogBuilder(this@BalancerSettingsActivity)
                .setTitle(R.string.balancer_select_group)
                .setMultiChoiceItems(groupNames, checkedStates) { _, which, isChecked ->
                    checkedStates[which] = isChecked
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val newGids = allGroups.filterIndexed { index, _ -> checkedStates[index] }.map { it.id }
                    DataStore.balancerTargetGroups = newGids.joinToString(",")
                    DataStore.balancerTargetGroup = newGids.firstOrNull() ?: 0L
                    DataStore.dirty = true
                    updateGroupSummary()
                    refreshMatchSummaryFn?.invoke(PreviewOverrides())
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        explicitNodesPref?.setOnPreferenceClickListener {
            launchMultiSelectNodes()
            true
        }

        val nameIncludePref = findPreference<EditTextPreference>("balancerNameInclude")
        val nameExcludePref = findPreference<EditTextPreference>("balancerNameExclude")
        val countryPref = findPreference<EditTextPreference>("balancerCountryFilter")
        val matchSummaryPref = findPreference<Preference>("balancerMatchSummary")
        val addTierPref = findPreference<Preference>("balancerAddTier")

        tiersList.clear()
        tiersList.addAll(BalancerBean.parseTiersJson(DataStore.balancerTiers))

        fun syncTiersDataStore() {
            DataStore.balancerTiers = BalancerBean.tiersToJson(tiersList)
        }

        fun formatTierSummary(tier: BalancerBean.PriorityTier, matchedCount: Int): String {
            val groupLabel = if (tier.groupId > 0L) {
                groupNameMap[tier.groupId] ?: getString(R.string.balancer_tier_missing_group, tier.groupId)
            } else {
                ""
            }
            val filterLabel = tier.nameFilter.orEmpty().trim()
            return when {
                groupLabel.isNotEmpty() && filterLabel.isNotEmpty() ->
                    getString(R.string.balancer_tier_sum_both, groupLabel, filterLabel, matchedCount)
                groupLabel.isNotEmpty() ->
                    getString(R.string.balancer_tier_sum_group_only, groupLabel, matchedCount)
                filterLabel.isNotEmpty() ->
                    getString(R.string.balancer_tier_sum_regex_only, filterLabel, matchedCount)
                else -> getString(R.string.balancer_tier_sum_all, matchedCount)
            }
        }

        fun updateAddTierSummary() {
            addTierPref?.summary = when {
                tiersList.isEmpty() -> getString(R.string.balancer_add_tier_empty_hint)
                else -> {
                    val base = getString(R.string.balancer_add_tier_sum)
                    val fallbackCount = lastTierCounts.getOrNull(tiersList.size) ?: 0
                    if (fallbackCount > 0) {
                        base + "\n" + getString(R.string.balancer_tier_fallback_note, fallbackCount)
                    } else {
                        base
                    }
                }
            }
        }

        var showTierDialog: ((Int?) -> Unit)? = null

        fun rebuildTierPreferences() {
            val screen = preferenceScreen ?: return
            screen.isOrderingAsAdded = false
            tierPrefs.forEach { screen.removePreference(it) }
            tierPrefs.clear()
            val isPriority = DataStore.balancerSelectionMode == BalancerBean.SELECTION_PRIORITY
            addTierPref?.isVisible = isPriority
            if (!isPriority) return

            tiersList.forEachIndexed { index, tier ->
                val pref = Preference(this@BalancerSettingsActivity).apply {
                    key = "balancerTier_$index"
                    order = ORDER_TIER_BASE + index
                    title = getString(R.string.balancer_tier_title, index + 1)
                    setIcon(R.drawable.ic_baseline_layers_24)
                    summary = formatTierSummary(tier, lastTierCounts.getOrNull(index) ?: 0)
                    isVisible = true
                    setOnPreferenceClickListener {
                        showTierDialog?.invoke(index)
                        true
                    }
                }
                screen.addPreference(pref)
                tierPrefs.add(pref)
            }
            updateAddTierSummary()
        }
        rebuildTierPrefsFn = { rebuildTierPreferences() }

        showTierDialog = { editIndex ->
            val existing = editIndex?.let { tiersList.getOrNull(it) }
            val dialogView = LayoutInflater.from(this@BalancerSettingsActivity)
                .inflate(R.layout.layout_balancer_tier_dialog, null)
            val groupLabel = dialogView.findViewById<TextView>(R.id.tier_group_label)
            val groupSpinner = dialogView.findViewById<AppCompatSpinner>(R.id.tier_group_spinner)
            val filterLayout = dialogView.findViewById<TextInputLayout>(R.id.tier_filter_layout)
            val filterEdit = dialogView.findViewById<TextInputEditText>(R.id.tier_filter_edit)
            val moveActions = dialogView.findViewById<View>(R.id.tier_move_actions)
            val moveUpBtn = dialogView.findViewById<MaterialButton>(R.id.tier_move_up)
            val moveDownBtn = dialogView.findViewById<MaterialButton>(R.id.tier_move_down)

            val selectedGids = parseGroupIds(DataStore.balancerTargetGroups).ifEmpty {
                if (DataStore.balancerTargetGroup > 0L) listOf(DataStore.balancerTargetGroup) else emptyList()
            }.toSet()
            val selectedGroups = allGroups.filter { selectedGids.contains(it.id) }
            val spinnerItems = ArrayList<Pair<Long, String>>().apply {
                add(-1L to getString(R.string.balancer_tier_any_group))
                selectedGroups.forEach { add(it.id to it.displayName()) }
                val existingGid = existing?.groupId ?: -1L
                if (existingGid > 0L && none { it.first == existingGid }) {
                    add(existingGid to (groupNameMap[existingGid] ?: getString(R.string.balancer_tier_missing_group, existingGid)))
                }
            }
            val showGroupSelector = spinnerItems.size > 1
            groupLabel.visibility = if (showGroupSelector) View.VISIBLE else View.GONE
            groupSpinner.visibility = if (showGroupSelector) View.VISIBLE else View.GONE
            if (showGroupSelector) {
                val adapter = ArrayAdapter(
                    this@BalancerSettingsActivity,
                    android.R.layout.simple_spinner_item,
                    spinnerItems.map { it.second }
                ).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                groupSpinner.adapter = adapter
                val initialPos = spinnerItems.indexOfFirst { it.first == (existing?.groupId ?: -1L) }
                groupSpinner.setSelection(if (initialPos >= 0) initialPos else 0)
            }

            filterEdit.setText(existing?.nameFilter.orEmpty())
            filterEdit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    filterLayout.error = null
                }
            })

            fun buildValidatedTier(): BalancerBean.PriorityTier? {
                val pattern = filterEdit.text?.toString()?.trim().orEmpty()
                if (pattern.isNotEmpty() && runCatching { Regex(pattern) }.isFailure) {
                    filterLayout.error = getString(R.string.balancer_tier_regex_invalid)
                    return null
                }
                val chosenGid = if (showGroupSelector) {
                    spinnerItems.getOrNull(groupSpinner.selectedItemPosition)?.first ?: -1L
                } else {
                    -1L
                }
                return BalancerBean.PriorityTier(chosenGid, pattern)
            }

            val builder = MaterialAlertDialogBuilder(this@BalancerSettingsActivity)
                .setTitle(
                    if (editIndex == null) getString(R.string.balancer_tier_dialog_add)
                    else getString(R.string.balancer_tier_dialog_edit, editIndex + 1)
                )
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)

            if (editIndex != null) {
                builder.setNeutralButton(R.string.delete) { _, _ ->
                    if (editIndex in tiersList.indices) {
                        tiersList.removeAt(editIndex)
                        syncTiersDataStore()
                        DataStore.dirty = true
                        rebuildTierPreferences()
                        refreshMatchSummaryFn?.invoke(PreviewOverrides())
                    }
                }
            }

            val dialog = builder.create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                    val updated = buildValidatedTier() ?: return@setOnClickListener
                    if (editIndex == null) {
                        tiersList.add(updated)
                    } else if (editIndex in tiersList.indices) {
                        tiersList[editIndex] = updated
                    }
                    syncTiersDataStore()
                    DataStore.dirty = true
                    rebuildTierPreferences()
                    refreshMatchSummaryFn?.invoke(PreviewOverrides())
                    dialog.dismiss()
                }
            }

            if (editIndex != null && tiersList.size > 1) {
                moveActions.visibility = View.VISIBLE
                moveUpBtn.isEnabled = editIndex > 0
                moveDownBtn.isEnabled = editIndex < tiersList.lastIndex
                moveUpBtn.setOnClickListener {
                    val updated = buildValidatedTier() ?: return@setOnClickListener
                    if (editIndex in 1 until tiersList.size) {
                        tiersList[editIndex] = updated
                        val swapped = tiersList[editIndex - 1]
                        tiersList[editIndex - 1] = tiersList[editIndex]
                        tiersList[editIndex] = swapped
                        syncTiersDataStore()
                        DataStore.dirty = true
                        rebuildTierPreferences()
                        refreshMatchSummaryFn?.invoke(PreviewOverrides())
                        dialog.dismiss()
                    }
                }
                moveDownBtn.setOnClickListener {
                    val updated = buildValidatedTier() ?: return@setOnClickListener
                    if (editIndex in 0 until tiersList.lastIndex) {
                        tiersList[editIndex] = updated
                        val swapped = tiersList[editIndex + 1]
                        tiersList[editIndex + 1] = tiersList[editIndex]
                        tiersList[editIndex] = swapped
                        syncTiersDataStore()
                        DataStore.dirty = true
                        rebuildTierPreferences()
                        refreshMatchSummaryFn?.invoke(PreviewOverrides())
                        dialog.dismiss()
                    }
                }
            } else {
                moveActions.visibility = View.GONE
            }

            dialog.show()
        }

        addTierPref?.setOnPreferenceClickListener {
            showTierDialog?.invoke(null)
            true
        }

        fun refreshMatchSummary(overrides: PreviewOverrides = PreviewOverrides()) {
            val type = DataStore.balancerType
            val gids = parseGroupIds(DataStore.balancerTargetGroups).ifEmpty {
                if (DataStore.balancerTargetGroup > 0L) listOf(DataStore.balancerTargetGroup) else emptyList()
            }
            val extraIds = proxyList.map { it.id }
            val countryPattern = overrides.country ?: DataStore.balancerCountryFilter
            val excludePattern = overrides.exclude ?: DataStore.balancerNameExclude
            val includePattern = overrides.include ?: DataStore.balancerNameInclude
            val selectionMode = overrides.selectionMode ?: DataStore.balancerSelectionMode
            val tiersSnapshot = tiersList.map { it.copy() }
            val selfId = DataStore.editingId
            val frontId = if (DataStore.balancerFrontProxyTmp == OutboundPreference.VALUE_SELECT_PROFILE.toInt()) {
                DataStore.balancerFrontProxy
            } else {
                0L
            }
            val landingId = if (DataStore.balancerLandingProxyTmp == OutboundPreference.VALUE_SELECT_PROFILE.toInt()) {
                DataStore.balancerLandingProxy
            } else {
                0L
            }

            runOnDefaultDispatcher {
                val res = BalancerMemberResolver.resolve(
                    BalancerMemberResolver.FilterSpec(
                        balancerType = type,
                        targetGroupIds = gids,
                        explicitProxyIds = extraIds,
                        nameExclude = excludePattern,
                        nameInclude = includePattern,
                        selfId = selfId,
                        frontProxyId = frontId,
                        landingProxyId = landingId,
                        countryFilter = countryPattern,
                        selectionMode = selectionMode,
                        tiers = tiersSnapshot,
                    )
                )
                val summaryText = formatPreviewSummary(res, type)
                onMainDispatcher {
                    lastTierCounts = res.tierCounts
                    tierPrefs.forEachIndexed { idx, pref ->
                        tiersList.getOrNull(idx)?.let { tier ->
                            pref.summary = formatTierSummary(tier, lastTierCounts.getOrNull(idx) ?: 0)
                        }
                    }
                    updateAddTierSummary()
                    matchSummaryPref?.summary = summaryText
                }
            }
        }
        refreshMatchSummaryFn = { ov -> refreshMatchSummary(ov) }

        fun formatNodeOrderSummary(): String {
            if (proxyList.isEmpty()) return getString(R.string.balancer_node_order_summary_empty)
            val previewNames = proxyList.take(3).joinToString(", ") { it.displayName() }
            val suffix = if (proxyList.size > 3) "..." else ""
            return getString(R.string.balancer_node_order_summary, proxyList.size, previewNames + suffix)
        }

        fun syncNodeListUi(
            type: Int = DataStore.balancerType,
            selectionMode: String = DataStore.balancerSelectionMode,
        ) {
            val isFallback = selectionMode == BalancerBean.SELECTION_FALLBACK
            localNodeListPref?.isVisible = shouldShowNodeList(type, selectionMode)
            if (isFallback) {
                localNodeListPref?.setTitle(R.string.balancer_fallback_order_title)
            } else {
                localNodeListPref?.setTitle(R.string.balancer_node_order_title)
            }
            localNodeListPref?.summary = formatNodeOrderSummary()
        }
        refreshNodeListFn = { syncNodeListUi() }

        localNodeListPref?.setOnPreferenceClickListener {
            val excludeIds = buildExcludeIds()
            editNodeOrderLauncher.launch(
                Intent(this@BalancerSettingsActivity, BalancerNodeOrderActivity::class.java).apply {
                    putExtra(BalancerNodeOrderActivity.EXTRA_NODE_IDS, proxyList.map { it.id }.toLongArray())
                    putExtra(BalancerNodeOrderActivity.EXTRA_TIERS_JSON, BalancerBean.tiersToJson(tiersList))
                    putExtra(BalancerNodeOrderActivity.EXTRA_EDITING_ID, DataStore.editingId)
                    if (excludeIds.isNotEmpty()) {
                        putExtra(BalancerNodeOrderActivity.EXTRA_EXCLUDE_IDS, excludeIds)
                    }
                },
            )
            true
        }

        fun updateTypeVisibility(type: Int) {
            val hasGroup = type == BalancerBean.TYPE_GROUP || type == BalancerBean.TYPE_MIXED
            val hasList = hasExplicitListType(type)
            groupPref?.isVisible = hasGroup
            nameIncludePref?.isVisible = hasGroup
            nameExcludePref?.isVisible = hasGroup
            explicitNodesPref?.isVisible = hasList
            // test3: front/landing switches only make sense for group-sourced
            // members; the .24 node list / explicit nodes UI is untouched.
            useFrontProxyPref?.isVisible = type == BalancerBean.TYPE_GROUP
            useLandingProxyPref?.isVisible = type == BalancerBean.TYPE_GROUP
            syncNodeListUi(type, DataStore.balancerSelectionMode)
            refreshMatchSummary()
        }

        val typePref = findPreference<SimpleMenuPreference>("balancerType")
        typePref?.value = DataStore.balancerType.toString()
        typePref?.setOnPreferenceChangeListener { _, newValue ->
            val type = (newValue as? String)?.toIntOrNull() ?: BalancerBean.TYPE_MIXED
            DataStore.balancerType = type
            updateTypeVisibility(type)
            true
        }

        nameIncludePref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            if (pref.text.isNullOrBlank()) getString(R.string.balancer_name_include_all) else pref.text
        }
        nameExcludePref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            if (pref.text.isNullOrBlank()) getString(androidx.preference.R.string.not_set) else pref.text
        }
        countryPref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            if (pref.text.isNullOrBlank()) getString(R.string.balancer_country_filter_any) else pref.text
        }

        nameIncludePref?.setOnPreferenceChangeListener { _, newValue ->
            refreshMatchSummary(PreviewOverrides(include = newValue as? String))
            true
        }
        nameExcludePref?.setOnPreferenceChangeListener { _, newValue ->
            refreshMatchSummary(PreviewOverrides(exclude = newValue as? String))
            true
        }
        countryPref?.setOnPreferenceChangeListener { _, newValue ->
            refreshMatchSummary(PreviewOverrides(country = newValue as? String))
            true
        }

        // Advanced collapsible section
        val catUseWhich = findPreference<PreferenceCategory>("balancerCatUseWhich")
        val catHealth = findPreference<PreferenceCategory>("balancerCatHealth")
        val catLoadBalance = findPreference<PreferenceCategory>("balancerCatLoadBalance")
        val catChain = findPreference<PreferenceCategory>("balancerCatChain")
        val advancedTogglePref = findPreference<Preference>("balancerAdvancedToggle")

        val intervalPref = bindIntPref("balancerInterval", 300, 5, R.string.balancer_unit_seconds)
        val tolerancePref = bindIntPref("balancerTolerance", 30, 0, R.string.balancer_unit_ms)
        val testUrlPref = findPreference<EditTextPreference>("balancerTestUrl")
        val toleranceSummaryProvider = Preference.SummaryProvider<EditTextPreference> { current ->
            val parsed = current.text?.trim()?.toIntOrNull()
            val effective = if (parsed != null && parsed >= 0) parsed else 30
            getString(R.string.balancer_unit_ms, effective)
        }
        tolerancePref?.summaryProvider = toleranceSummaryProvider

        val balanceSwitchPref = findPreference<SwitchPreferenceCompat>("balancerBalanceEnabled")
        val interruptPref = findPreference<SwitchPreferenceCompat>("balancerInterruptOnSwitch")
        val balanceModePref = findPreference<SimpleMenuPreference>("balancerBalanceMode")
        val balanceIntervalPref = bindIntPref("balancerBalanceIntervalSec", 30, 1, R.string.balancer_unit_seconds)
        val selectionModePref = findPreference<SimpleMenuPreference>("balancerSelectionMode")
        val fallbackAutoRevertPref = findPreference<SwitchPreferenceCompat>("balancerFallbackAutoRevert")
        val fallbackMaxFailsPref = bindIntPref("balancerFallbackMaxFails", 2, 1, null)
        fallbackMaxFailsPref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { current ->
            val parsed = current.text?.trim()?.toIntOrNull()
            val effective = (parsed ?: 2).coerceIn(1, 20)
            "$effective · ${getString(R.string.balancer_fallback_max_fails_summary)}"
        }

        // test4: the .24 traffic-distribution cluster (the "distribute traffic
        // among best configs" switch + load-balance mode/interval) only exists
        // while no .85 strategy is chosen. With a legacy choice the dropdown
        // owns traffic distribution (leastLoad/random/roundRobin/consistentHash
        // ARE the distribution strategies) and the switch listener would
        // re-derive the dropdown from the .24 fields; without a choice
        // (blank legacy, e.g. test2-era beans) the autoselect path still
        // reads these fields, so the controls stay.
        fun noLegacyChoice(): Boolean = DataStore.balancerLegacyStrategy.isBlank()

        fun applyCategoryVisibility(balanceOn: Boolean) {
            catUseWhich?.isVisible = advancedExpanded
            catHealth?.isVisible = advancedExpanded
            catChain?.isVisible = advancedExpanded
            catLoadBalance?.isVisible = advancedExpanded && balanceOn && noLegacyChoice()
        }

        fun applyAdvancedExpanded(expanded: Boolean, balanceEnabledOverride: Boolean? = null) {
            advancedExpanded = expanded
            applyCategoryVisibility(balanceEnabledOverride ?: DataStore.balancerBalanceEnabled)
            advancedTogglePref?.setSummary(if (expanded) R.string.balancer_advanced_hide else R.string.balancer_advanced_show)
            advancedTogglePref?.setIcon(
                if (expanded) R.drawable.ic_baseline_keyboard_arrow_up_24 else R.drawable.ic_baseline_keyboard_arrow_down_24
            )
        }
        applyAdvancedExpanded(advancedExpanded)
        advancedTogglePref?.setOnPreferenceClickListener {
            applyAdvancedExpanded(!advancedExpanded)
            true
        }

        // test3: .85 strategy visibility — only leastPing uses the probe settings
        // (tolerance / test URL / interval). Blank legacy ("no legacy choice",
        // e.g. test2-era beans) keeps them visible: the .24 path still probes.
        fun probeSettingsVisible(legacy: String = DataStore.balancerLegacyStrategy): Boolean =
            legacy.isBlank() || legacy == BalancerBean.STRATEGY_LEAST_PING

        fun updateStrategyVisibility(legacy: String = DataStore.balancerLegacyStrategy) {
            val visible = probeSettingsVisible(legacy)
            val notFallback = DataStore.balancerSelectionMode != BalancerBean.SELECTION_FALLBACK
            tolerancePref?.isVisible = visible && notFallback
            testUrlPref?.isVisible = visible
            intervalPref?.isVisible = visible
            // test5 (N5): "interrupt connections on switch" is forced false
            // under leastPing (buildUrlTestOutbound / the loadbalance branches
            // hard-code interrupt_exist_connections=false), so the switch
            // would be a dead toggle there — hide it for leastPing. Blank
            // legacy keeps it: the autoselect path reads the bean value, and
            // the value itself is never rewritten, so behaviour is unchanged.
            interruptPref?.isVisible = legacy != BalancerBean.STRATEGY_LEAST_PING
        }

        fun updateSelectionAndBalanceUi(
            selectionOverride: String? = null,
            balanceEnabledOverride: Boolean? = null,
            balanceModeOverride: String? = null,
        ) {
            val mode = selectionOverride ?: DataStore.balancerSelectionMode
            val isFallback = mode == BalancerBean.SELECTION_FALLBACK
            val isPriority = mode == BalancerBean.SELECTION_PRIORITY
            selectionModePref?.value = mode
            selectionModePref?.setSummary(
                when {
                    isFallback -> R.string.balancer_selection_fallback_sum
                    isPriority -> R.string.balancer_selection_priority_sum
                    else -> R.string.balancer_selection_latency_sum
                }
            )

            fallbackAutoRevertPref?.isVisible = isFallback
            fallbackMaxFailsPref?.isVisible = isFallback
            balanceSwitchPref?.isVisible = !isFallback && noLegacyChoice()

            tolerancePref?.isVisible = !isFallback && probeSettingsVisible()
            tolerancePref?.setTitle(R.string.balancer_tolerance_title)
            tolerancePref?.summaryProvider = toleranceSummaryProvider

            rebuildTierPreferences()
            syncNodeListUi(DataStore.balancerType, mode)

            val balanceOn = if (isFallback) false else (balanceEnabledOverride ?: DataStore.balancerBalanceEnabled)
            val balMode = balanceModeOverride ?: DataStore.balancerBalanceMode
            balanceModePref?.value = balMode
            val balModeLabel = getString(
                if (balMode == BalancerBean.BALANCE_PER_CONNECTION) {
                    R.string.balancer_balance_mode_per_conn
                } else {
                    R.string.balancer_balance_mode_timed
                }
            )
            balanceSwitchPref?.summary = if (balanceOn) {
                getString(R.string.balancer_balance_enabled_on_sum, balModeLabel)
            } else {
                getString(R.string.balancer_balance_enabled_off_sum)
            }

            applyCategoryVisibility(balanceOn && !isFallback)
            balanceModePref?.isEnabled = balanceOn && !isFallback && noLegacyChoice()
            balanceIntervalPref?.isVisible = balanceOn && !isFallback && balMode != BalancerBean.BALANCE_PER_CONNECTION && noLegacyChoice()
        }

        // test3: legacy dropdown listener (restored from 4a70a4b5) — a new legacy
        // choice maps onto the .24 fields and refreshes the UI.
        val legacyPref = findPreference<SimpleMenuPreference>("balancerLegacyStrategy")

        // test4: map a legacy dropdown choice onto the .24 fields. Picking a
        // legacy strategy always means latency mode now: the .24 selection-mode
        // row is hidden, so a stored priority/fallback value must not swallow
        // the pick (the old early return did exactly that). applyLegacyStrategy
        // normalizes every choice to latency; the explicit assignment below
        // keeps DataStore.balancerStrategy in step.
        fun applyLegacyMappingToDataStore(rawStrategy: String) {
            DataStore.balancerLegacyStrategy = rawStrategy
            val legacyBean = BalancerBean()
            legacyBean.applyLegacyStrategy(rawStrategy)
            DataStore.balancerSelectionMode = legacyBean.selectionMode
            DataStore.balancerStrategy = legacyBean.selectionMode
            DataStore.balancerBalanceEnabled = legacyBean.balanceEnabled
            DataStore.balancerBalanceMode = legacyBean.balanceMode
        }

        // test3 (from 4a70a4b5): when the .24 fields change, re-derive the dropdown
        // value so it never shows a stale choice. Ambiguous combos (leastLoad /
        // random / roundRobin all map to latency + per-connection) derive to "".
        // test5 (N2): two guards. (1) Blank legacy stays blank: these listeners
        // are only reachable while no legacy choice exists, and deriving a
        // choice from the .24 fields here would invent one — a blank-legacy
        // latency bean with the traffic switch OFF->ON used to derive leastPing
        // from the still-unpersisted OFF value, hide the switch and swallow
        // the toggle. (2) When derivation does run, use the pending newValue
        // passed by the caller: preference change listeners fire BEFORE the
        // new value is persisted to DataStore.
        fun syncLegacyFromDataStore(
            balanceEnabledOverride: Boolean? = null,
            balanceModeOverride: String? = null,
        ) {
            if (DataStore.balancerLegacyStrategy.isBlank()) return
            if (DataStore.balancerSelectionMode != BalancerBean.SELECTION_LATENCY) {
                DataStore.balancerLegacyStrategy = ""
                legacyPref?.value = ""
                return
            }
            val bean = BalancerBean()
            bean.selectionMode = DataStore.balancerSelectionMode
            bean.balanceEnabled = balanceEnabledOverride ?: DataStore.balancerBalanceEnabled
            bean.balanceMode = balanceModeOverride ?: DataStore.balancerBalanceMode
            val derived = bean.deriveLegacyStrategy()
            DataStore.balancerLegacyStrategy = derived ?: ""
            legacyPref?.value = derived ?: ""
        }

        legacyPref?.setOnPreferenceChangeListener { _, newValue ->
            val raw = newValue?.toString() ?: ""
            // test5 (N1): mark the real user pick — serialize() only trusts
            // the DataStore legacy value when this fired (the init() seed
            // must not count as a pick).
            legacyDropdownTouched = true
            applyLegacyMappingToDataStore(raw)
            updateStrategyVisibility(DataStore.balancerLegacyStrategy)
            updateSelectionAndBalanceUi()
            refreshMatchSummary()
            true
        }

        // test4: the .24 selection-mode row is hidden (balancer_preferences.xml)
        // and has no change listener any more — the row can never fire. The
        // stored selectionMode is only changed by picking a legacy strategy
        // (applyLegacyMappingToDataStore, always latency) or by loading a bean.

        balanceSwitchPref?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: false
            syncLegacyFromDataStore(balanceEnabledOverride = enabled)
            updateSelectionAndBalanceUi(balanceEnabledOverride = enabled)
            true
        }

        balanceModePref?.setOnPreferenceChangeListener { _, newValue ->
            val mode = (newValue as? String) ?: BalancerBean.BALANCE_TIMED_ROTATION
            syncLegacyFromDataStore(balanceModeOverride = mode)
            updateSelectionAndBalanceUi(balanceModeOverride = mode)
            true
        }

        // test3: sticky + persist linkage (2bbd9787): turning sticky off disables
        // persist; persist only takes effect while sticky is on.
        val stickyPref = findPreference<SwitchPreferenceCompat>("balancerStickyByApp")
        val persistStickyPref = findPreference<SwitchPreferenceCompat>("balancerPersistStickyByApp")
        stickyPref?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: false
            if (!enabled) {
                persistStickyPref?.isChecked = false
                DataStore.balancerPersistStickyByApp = false
            }
            persistStickyPref?.isEnabled = enabled
            true
        }
        persistStickyPref?.isEnabled = DataStore.balancerStickyByApp

        testUrlPref?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val custom = pref.text?.trim().orEmpty()
            if (custom.isBlank()) {
                getString(R.string.balancer_test_url_default, DataStore.connectionTestURL)
            } else {
                custom
            }
        }

        updateSelectionAndBalanceUi()
        updateStrategyVisibility()
        updateTypeVisibility(DataStore.balancerType)
    }

    private fun PreferenceFragmentCompat.bindIntPref(
        key: String,
        defaultValue: Int,
        minValue: Int,
        unitResId: Int?,
        zeroAsUnlimited: Boolean = false,
        onValidChange: ((Int) -> Unit)? = null,
    ): EditTextPreference? {
        val pref = findPreference<EditTextPreference>(key) ?: return null
        pref.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> { current ->
            val parsed = current.text?.trim()?.toIntOrNull()
            val effective = if (parsed != null && parsed >= minValue) parsed else defaultValue
            when {
                zeroAsUnlimited && effective <= 0 -> getString(R.string.balancer_unlimited)
                unitResId != null -> getString(unitResId, effective)
                else -> effective.toString()
            }
        }
        pref.setOnPreferenceChangeListener { _, newValue ->
            val parsed = (newValue as? String)?.trim()?.toIntOrNull()
            if (parsed == null || parsed < minValue) {
                false
            } else {
                onValidChange?.invoke(parsed)
                true
            }
        }
        return pref
    }

    private fun parseGroupIds(raw: String): List<Long> {
        return raw.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it > 0L }
    }

    private fun shouldShowNodeList(
        type: Int = DataStore.balancerType,
        selectionMode: String = DataStore.balancerSelectionMode,
    ): Boolean =
        (selectionMode == BalancerBean.SELECTION_PRIORITY || selectionMode == BalancerBean.SELECTION_FALLBACK) &&
            hasExplicitListType(type) &&
            proxyList.isNotEmpty()

    private fun hasExplicitListType(type: Int): Boolean =
        type == BalancerBean.TYPE_LIST || type == BalancerBean.TYPE_MIXED

    private fun syncServerProtocol() {
        DataStore.serverProtocol = proxyList.joinToString(",") { it.id.toString() }
    }

    private fun buildExcludeIds(): LongArray {
        val excludeList = mutableListOf<Long>()
        if (DataStore.editingId > 0L) excludeList.add(DataStore.editingId)
        if (DataStore.balancerFrontProxy > 0L) excludeList.add(DataStore.balancerFrontProxy)
        if (DataStore.balancerLandingProxy > 0L) excludeList.add(DataStore.balancerLandingProxy)
        return excludeList.toLongArray()
    }

    private fun formatPreviewSummary(
        res: BalancerMemberResolver.ResolutionResult,
        type: Int = DataStore.balancerType,
    ): String {
        val resId = when (type) {
            BalancerBean.TYPE_LIST -> R.string.balancer_preview_summary_list
            BalancerBean.TYPE_MIXED -> R.string.balancer_preview_summary_mixed
            else -> R.string.balancer_preview_summary
        }
        val base = if (res.matchedCandidateCount <= 0) {
            getString(R.string.balancer_match_summary_empty)
        } else {
            val text = getString(
                resId,
                res.matchedCandidateCount,
                res.availableCount,
                res.totalCount,
            )
            if (type == BalancerBean.TYPE_MIXED) {
                text + getString(
                    R.string.balancer_preview_summary_mixed_extra,
                    res.groupMatchedCount,
                    res.extraAddedCount,
                )
            } else {
                text
            }
        }
        return if (res.hasRegexError) {
            getString(R.string.balancer_match_summary_regex_error, base)
        } else {
            base
        }
    }

    private fun launchMultiSelectNodes() {
        val excludeIds = buildExcludeIds()

        selectProfileForAdd.launch(
            Intent(this@BalancerSettingsActivity, ProfileSelectActivity::class.java).apply {
                putExtra(ProfileSelectActivity.EXTRA_MULTI_SELECT, true)
                putExtra(
                    ProfileSelectActivity.EXTRA_SELECTED_IDS,
                    proxyList.map { it.id }.toLongArray(),
                )
                if (excludeIds.isNotEmpty()) {
                    putExtra(ProfileSelectActivity.EXTRA_EXCLUDE_IDS, excludeIds)
                }
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        advancedExpanded = savedInstanceState?.getBoolean(STATE_ADVANCED_EXPANDED, false) ?: false
        // test5 (N1): init() is only run on a fresh open (see
        // ProfileSettingsActivity.onCreate), so a restored flag survives
        // rotation; a fresh Activity instance starts untouched.
        legacyDropdownTouched = savedInstanceState?.getBoolean(STATE_LEGACY_TOUCHED, false) ?: false
        super.onCreate(savedInstanceState)
        // test5: title "策略组设置" — same as .85 (BalancerSettingsActivity
        // :372 uses R.string.balancer_settings). The .24 "自动选择"
        // (balancer_settings_autoselect) string is left defined but unused.
        supportActionBar?.setTitle(R.string.balancer_settings)
    }

    override fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        runOnDefaultDispatcher {
            reloadExplicitNodes()
        }
    }

    private suspend fun reloadExplicitNodes() {
        val idList = DataStore.serverProtocol.split(",")
            .mapNotNull { it.takeIf { s -> s.isNotBlank() }?.toLongOrNull() }
        val loaded = ArrayList<ProxyEntity>()
        if (idList.isNotEmpty()) {
            val profiles = ProfileManager.getProfiles(idList).associateBy { it.id }
            for (id in idList) {
                profiles[id]?.let { loaded.add(it) }
            }
        }
        onMainDispatcher {
            proxyList.clear()
            proxyList.addAll(loaded)
            refreshNodeListFn?.invoke()
            refreshExplicitSummaryFn?.invoke()
            refreshMatchSummaryFn?.invoke(PreviewOverrides())
        }
    }

    fun testProfileAllowed(profile: ProxyEntity): Boolean {
        if (profile.id == DataStore.editingId) return false
        if (profile.id == DataStore.balancerFrontProxy || profile.id == DataStore.balancerLandingProxy) return false
        if (profile.type == ProxyEntity.TYPE_BALANCER) {
            val bean = profile.balancerBean ?: return true
            if (bean.proxies.contains(DataStore.editingId)) return false
            if (bean.frontProxy == DataStore.editingId || bean.landingProxy == DataStore.editingId) return false
        }
        return true
    }

    val editNodeOrderLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
                val data = result.data ?: return@runOnDefaultDispatcher
                val resultIds = data.getLongArrayExtra(BalancerNodeOrderActivity.EXTRA_RESULT_NODE_IDS)
                    ?: return@runOnDefaultDispatcher
                val profileMap = if (resultIds.isNotEmpty()) {
                    ProfileManager.getProfiles(resultIds.toList()).associateBy { it.id }
                } else {
                    emptyMap()
                }
                val reordered = ArrayList<ProxyEntity>()
                val seen = HashSet<Long>()
                for (id in resultIds) {
                    if (seen.add(id)) {
                        profileMap[id]?.let { reordered.add(it) }
                    }
                }
                onMainDispatcher {
                    proxyList.clear()
                    proxyList.addAll(reordered)
                    syncServerProtocol()
                    DataStore.dirty = true
                    refreshNodeListFn?.invoke()
                    refreshExplicitSummaryFn?.invoke()
                    refreshMatchSummaryFn?.invoke(PreviewOverrides())
                }
            }
        }

    val selectProfileForAdd =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
                DataStore.dirty = true
                val data = result.data ?: return@runOnDefaultDispatcher
                val multiIds = data.getLongArrayExtra(ProfileSelectActivity.EXTRA_PROFILE_IDS)
                if (multiIds != null) {
                    val profiles = ProfileManager.getProfiles(multiIds.toList()).associateBy { it.id }
                    var circularFound = false
                    val newProxyList = ArrayList<ProxyEntity>()
                    val seen = HashSet<Long>()
                    for (id in multiIds) {
                        if (seen.add(id)) {
                            val p = profiles[id] ?: continue
                            if (!testProfileAllowed(p)) {
                                circularFound = true
                                continue
                            }
                            newProxyList.add(p)
                        }
                    }
                    onMainDispatcher {
                        if (circularFound) {
                            MaterialAlertDialogBuilder(this@BalancerSettingsActivity)
                                .setTitle(R.string.circular_reference)
                                .setMessage(R.string.circular_reference_sum)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                        proxyList.clear()
                        proxyList.addAll(newProxyList)
                        syncServerProtocol()
                        refreshNodeListFn?.invoke()
                        refreshExplicitSummaryFn?.invoke()
                        refreshMatchSummaryFn?.invoke(PreviewOverrides())
                    }
                } else {
                    val singleId = data.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L)
                    if (singleId > 0L) {
                        val profile = ProfileManager.getProfile(singleId)
                        if (profile != null) {
                            if (!testProfileAllowed(profile)) {
                                onMainDispatcher {
                                    MaterialAlertDialogBuilder(this@BalancerSettingsActivity)
                                        .setTitle(R.string.circular_reference)
                                        .setMessage(R.string.circular_reference_sum)
                                        .setPositiveButton(android.R.string.ok, null)
                                        .show()
                                }
                            } else {
                                onMainDispatcher {
                                    if (proxyList.none { it.id == profile.id }) {
                                        proxyList.add(profile)
                                        refreshNodeListFn?.invoke()
                                    }
                                    syncServerProtocol()
                                    refreshExplicitSummaryFn?.invoke()
                                    refreshMatchSummaryFn?.invoke(PreviewOverrides())
                                }
                            }
                        }
                    }
                }
            }
        }
}
