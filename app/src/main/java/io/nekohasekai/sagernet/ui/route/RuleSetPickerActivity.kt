package io.nekohasekai.sagernet.ui.route

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutRuleSetItemBinding
import io.nekohasekai.sagernet.databinding.LayoutRuleSetPickerBinding
import io.nekohasekai.sagernet.ktx.crossFadeFrom
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.route.RuleSetCatalog
import io.nekohasekai.sagernet.route.RuleSets
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Searchable multi-select rule-set picker over `srslist.h` (~2300+ remote rule-sets)
 * plus custom `.srs` URLs and remote auto-update interval settings.
 */
class RuleSetPickerActivity : ThemedActivity() {

    companion object {
        const val EXTRA_RULE_SETS = "ruleSets"
        private const val STATE_SELECTED = "selected"
        private const val STATE_TAB = "tab"
        private const val TAB_ALL = 0
        private const val TAB_CUSTOM = 1
        private const val TAB_SELECTED = 2
    }

    class Contract : ActivityResultContract<List<String>, List<String>?>() {
        override fun createIntent(context: Context, input: List<String>) =
            Intent(context, RuleSetPickerActivity::class.java).putStringArrayListExtra(EXTRA_RULE_SETS, ArrayList(input))

        override fun parseResult(resultCode: Int, intent: Intent?): List<String>? =
            if (resultCode == RESULT_OK) intent?.getStringArrayListExtra(EXTRA_RULE_SETS) else null
    }

    private enum class Kind { KNOWN, URL, UNKNOWN, LOCAL, CUSTOM_DB }

    private class Row(val entry: String, val source: String, val kind: Kind, val file: File? = null)

    private lateinit var binding: LayoutRuleSetPickerBinding
    private val selected = LinkedHashSet<String>()
    private var catalog = RuleSetCatalog(emptyList())
    private var rows = emptyList<Row>()
    private var currentTab = TAB_ALL
    private val adapter = RowAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutRuleSetPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setTitle(R.string.rule_set_picker_title)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }

        val initial = savedInstanceState?.getStringArrayList(STATE_SELECTED)
            ?: intent.getStringArrayListExtra(EXTRA_RULE_SETS).orEmpty()
        initial.map { it.trim() }.filterTo(selected) { it.isNotEmpty() }
        currentTab = savedInstanceState?.getInt(STATE_TAB, TAB_ALL)?.coerceIn(TAB_ALL, TAB_SELECTED) ?: TAB_ALL
        updateSubtitle()

        binding.list.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        binding.list.adapter = adapter
        ViewCompat.setOnApplyWindowInsetsListener(binding.list, ListListener)
        binding.search.addTextChangedListener { rebuild() }
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.rule_set_tab_all))
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.rule_set_tab_custom))
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.rule_set_tab_selected))
        binding.tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                currentTab = tab.position
                rebuild()
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) = Unit
        })
        binding.tabs.getTabAt(currentTab)?.select()
        onBackPressedDispatcher.addCallback(this) { finishWithResult() }

        lifecycleScope.launch {
            catalog = withContext(Dispatchers.IO) { RuleSetCatalog.current() }
            rebuild()
            binding.list.crossFadeFrom(binding.loading)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_SELECTED, ArrayList(selected))
        outState.putInt(STATE_TAB, currentTab)
    }

    private fun updateSubtitle() {
        val countText = getString(R.string.picker_selected, selected.size)
        val interval = RuleSets.normalizeUpdateInterval(DataStore.rulesUpdateInterval)
        supportActionBar?.subtitle = if (interval != null) {
            "$countText · ${RuleSets.formatUpdateInterval(this, interval)}"
        } else {
            countText
        }
    }

    private fun getCustomRows(): List<Row> {
        val result = mutableListOf<Row>()
        val seenEntries = mutableSetOf<String>()

        // 1. Local files in rulesets/ directory
        val rulesDir = RuleSetCatalog.localRuleSetDirs(this).firstOrNull() ?: File(filesDir, "rulesets")
        rulesDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                val entry = "local:${file.name}"
                seenEntries.add(entry)
                seenEntries.add(file.name)
                result.add(Row(entry, getString(R.string.rule_set_local_source), Kind.LOCAL, file))
            }
        }

        // 2. Custom db files in app storage (e.g. geoip-lite.db, geosite-lite.db)
        val extFilesDir = getExternalFilesDir(null) ?: filesDir
        extFilesDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".db") && file.name !in setOf("geoip.db", "geosite.db")) {
                val entry = "local:${file.name}"
                if (entry !in seenEntries) {
                    seenEntries.add(entry)
                    seenEntries.add(file.name)
                    result.add(Row(entry, getString(R.string.rule_set_custom_db_source), Kind.CUSTOM_DB, file))
                }
            }
        }

        // 3. URLs and custom rule sets from DataStore or selected
        val allCustom = (DataStore.customRuleSets + selected.filter { it.startsWith("local:") || RuleSets.isUrl(it) }).distinct()
        for (item in allCustom) {
            if (item !in seenEntries) {
                seenEntries.add(item)
                if (RuleSets.isUrl(item)) {
                    result.add(Row(item, RuleSets.source(item), Kind.URL))
                } else if (item.startsWith("local:")) {
                    val fn = item.removePrefix("local:")
                    val f = RuleSetCatalog.findLocalRuleSetFile(item, this)
                    val isDb = fn.endsWith(".db", ignoreCase = true)
                    val kind = if (isDb) Kind.CUSTOM_DB else Kind.LOCAL
                    val source = if (isDb) getString(R.string.rule_set_custom_db_source) else getString(R.string.rule_set_local_source)
                    result.add(Row(item, source, kind, f))
                }
            }
        }

        return result
    }

    @Suppress("NotifyDataSetChanged")
    private fun rebuild() {
        val query = binding.search.text?.toString()?.trim().orEmpty()
        val normalizedSelected = selected.map { if (catalog.urlOf(it) != null) it.replace(':', '-') else it }.toSet()

        when (currentTab) {
            TAB_CUSTOM -> {
                val customRows = getCustomRows()
                    .filter { query.isEmpty() || it.entry.contains(query, true) }
                val (picked, rest) = customRows.partition { isEntrySelected(it.entry) }
                rows = picked + rest
                binding.catalogEmpty.setText(R.string.rule_set_custom_empty)
                binding.catalogEmpty.isVisible = rows.isEmpty()
            }
            TAB_SELECTED -> {
                val selectedRows = selected.map { entry ->
                    when {
                        entry.startsWith("local:") -> {
                            val fn = entry.removePrefix("local:")
                            val finalFile = RuleSetCatalog.findLocalRuleSetFile(entry, this)
                            val isDb = fn.endsWith(".db", ignoreCase = true)
                            val kind = if (isDb) Kind.CUSTOM_DB else Kind.LOCAL
                            val source = if (isDb) getString(R.string.rule_set_custom_db_source) else getString(R.string.rule_set_local_source)
                            Row(entry, source, kind, finalFile)
                        }
                        catalog.urlOf(entry) != null -> {
                            val normalized = entry.replace(':', '-')
                            val url = catalog.urlOf(entry) ?: ""
                            Row(normalized, RuleSets.source(url), Kind.KNOWN)
                        }
                        RuleSets.isUrl(entry) -> Row(entry, RuleSets.source(entry), Kind.URL)
                        else -> Row(entry, "", Kind.UNKNOWN)
                    }
                }.filter { query.isEmpty() || it.entry.contains(query, true) }
                rows = selectedRows
                binding.catalogEmpty.setText(R.string.rule_set_selected_empty)
                binding.catalogEmpty.isVisible = rows.isEmpty()
            }
            else -> { // TAB_ALL
                val matches = catalog.search(query, Int.MAX_VALUE).map { (name, url) -> Row(name, RuleSets.source(url), Kind.KNOWN) }
                val (picked, rest) = matches.partition { it.entry in normalizedSelected }
                rows = picked + rest
                binding.catalogEmpty.setText(R.string.rule_set_catalog_empty)
                binding.catalogEmpty.isVisible = rows.isEmpty()
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun isEntrySelected(entry: String): Boolean {
        if (entry in selected) return true
        val colonVariant = entry.replaceFirst('-', ':')
        return colonVariant in selected
    }

    private fun toggleSelection(entry: String) {
        val colonVariant = entry.replaceFirst('-', ':')
        val removed = selected.remove(entry) or selected.remove(colonVariant)
        if (!removed) {
            selected.add(entry)
        }
    }

    private fun finishWithResult() {
        setResult(RESULT_OK, Intent().putStringArrayListExtra(EXTRA_RULE_SETS, ArrayList(selected)))
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finishWithResult()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.rule_set_picker_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_add_url -> {
                addUrl()
                return true
            }

            R.id.action_auto_update_interval -> {
                RuleSetAutoUpdateDialog.show(this) {
                    updateSubtitle()
                }
                return true
            }

            R.id.action_refresh_catalog -> {
                refreshCatalog()
                return true
            }

            R.id.action_clear_selections -> {
                selected.clear()
                updateSubtitle()
                rebuild()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun addUrl() {
        val layout = TextInputLayout(this).apply {
            hint = getString(R.string.rule_set_add_url_hint)
        }
        val input = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        layout.addView(input)
        val container = FrameLayout(this).apply {
            setPadding(dp2px(20), dp2px(12), dp2px(20), 0)
            addView(layout)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rule_set_add_url)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val url = input.text?.toString()?.trim().orEmpty()
            if (!RuleSets.isUrl(url)) {
                layout.error = getString(R.string.rule_set_add_url_invalid)
                return@setOnClickListener
            }
            selected.add(url)
            DataStore.customRuleSets = (DataStore.customRuleSets + url).distinct()
            updateSubtitle()
            rebuild()
            binding.list.scrollToPosition(0)
            dialog.dismiss()
        }
    }

    private fun refreshCatalog() {
        Toast.makeText(this, R.string.rule_set_refreshing_catalog, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                RuleSetCatalog.refreshCatalog()
            }
            catalog = withContext(Dispatchers.IO) { RuleSetCatalog.current() }
            rebuild()
            if (error == null) {
                Toast.makeText(
                    this@RuleSetPickerActivity,
                    getString(R.string.rule_set_refresh_catalog_done, catalog.size),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this@RuleSetPickerActivity,
                    getString(R.string.rule_set_refresh_catalog_failed, error),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowHolder>(), FastScrollRecyclerView.SectionedAdapter {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RowHolder(LayoutRuleSetItemBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: RowHolder, position: Int) = holder.bind(rows[position])

        override fun getItemCount() = rows.size

        override fun getSectionName(position: Int): String {
            val row = rows[position]
            return if (row.kind == Kind.KNOWN) row.entry.removePrefix("geoip-").removePrefix("geosite-").take(1).uppercase() else "*"
        }
    }

    private inner class RowHolder(val binding: LayoutRuleSetItemBinding) : RecyclerView.ViewHolder(binding.root) {
        private lateinit var row: Row
        private val sourceColors = binding.source.textColors

        init {
            binding.root.setOnClickListener {
                if (row.kind == Kind.CUSTOM_DB) {
                    showCustomDbDialog(row)
                } else {
                    toggleSelection(row.entry)
                    binding.check.isChecked = isEntrySelected(row.entry)
                    updateSubtitle()
                }
            }

            binding.root.setOnLongClickListener {
                val targetFile = row.file
                if ((row.kind == Kind.LOCAL || row.kind == Kind.CUSTOM_DB) && targetFile != null && targetFile.isFile) {
                    MaterialAlertDialogBuilder(this@RuleSetPickerActivity)
                        .setTitle(R.string.delete)
                        .setMessage(getString(R.string.rule_set_delete_confirm))
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            targetFile.delete()
                            selected.remove(row.entry)
                            selected.remove(targetFile.name)
                            DataStore.customRuleSets = DataStore.customRuleSets.filterNot {
                                it == targetFile.name || it == row.entry
                            }
                            updateSubtitle()
                            rebuild()
                            Toast.makeText(this@RuleSetPickerActivity, getString(R.string.rule_set_deleted, targetFile.name), Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                } else if (row.kind == Kind.URL) {
                    val displayName = RuleSets.shortName(row.entry)
                    MaterialAlertDialogBuilder(this@RuleSetPickerActivity)
                        .setTitle(R.string.delete)
                        .setMessage(getString(R.string.rule_set_delete_url_confirm, displayName))
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            selected.remove(row.entry)
                            DataStore.customRuleSets = DataStore.customRuleSets.filterNot { it == row.entry }
                            updateSubtitle()
                            rebuild()
                            Toast.makeText(this@RuleSetPickerActivity, getString(R.string.rule_set_deleted, displayName), Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                } else {
                    false
                }
            }
        }

        fun bind(row: Row) {
            this.row = row
            binding.name.text = when (row.kind) {
                Kind.URL -> RuleSets.shortName(row.entry)
                else -> row.entry
            }

            val externalDir = io.nekohasekai.sagernet.SagerNet.application.getExternalFilesDir(null) ?: io.nekohasekai.sagernet.SagerNet.application.filesDir
            val srsDir = File(externalDir, "srs")
            val effectiveCache = if (row.kind == Kind.KNOWN || row.kind == Kind.URL) {
                RuleSets.findCachedSrsFile(srsDir, row.entry)
            } else {
                null
            }
            val cacheSizeStr = if (effectiveCache != null) " · " + android.text.format.Formatter.formatFileSize(this@RuleSetPickerActivity, effectiveCache.length()) else ""

            binding.source.text = when (row.kind) {
                Kind.KNOWN -> row.source + cacheSizeStr
                Kind.URL -> getString(R.string.rule_set_url_source, row.source) + cacheSizeStr
                Kind.UNKNOWN -> getString(R.string.rule_set_unknown)
                Kind.LOCAL, Kind.CUSTOM_DB -> row.source
            }
            if (row.kind == Kind.UNKNOWN) {
                binding.source.setTextColor(ContextCompat.getColor(this@RuleSetPickerActivity, R.color.color_route_block))
            } else {
                binding.source.setTextColor(sourceColors)
            }
            binding.check.isChecked = isEntrySelected(row.entry)
        }
    }

    private fun showCustomDbDialog(row: Row) {
        val file = row.file
        val displayName = file?.name ?: row.entry.removePrefix("local:")
        val size = file?.takeIf { it.isFile }?.let {
            android.text.format.Formatter.formatFileSize(this, it.length())
        }.orEmpty()
        val alreadySelected = isEntrySelected(row.entry)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rule_set_custom_db_title)
            .setMessage(getString(R.string.rule_set_custom_db_message, displayName, size))
            .setPositiveButton(if (alreadySelected) R.string.rule_set_custom_db_remove else R.string.rule_set_custom_db_add) { _, _ ->
                if (alreadySelected) {
                    selected.remove(row.entry)
                    selected.remove(displayName)
                } else {
                    selected.add(row.entry)
                    DataStore.customRuleSets = (DataStore.customRuleSets + row.entry).distinct()
                }
                updateSubtitle()
                rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
