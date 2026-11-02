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

/**
 * Searchable multi-select rule-set picker over `srslist.h` (~2300+ remote rule-sets)
 * plus custom `.srs` URLs and remote auto-update interval settings.
 */
class RuleSetPickerActivity : ThemedActivity() {

    companion object {
        const val EXTRA_RULE_SETS = "ruleSets"
        private const val STATE_SELECTED = "selected"
    }

    class Contract : ActivityResultContract<List<String>, List<String>?>() {
        override fun createIntent(context: Context, input: List<String>) =
            Intent(context, RuleSetPickerActivity::class.java).putStringArrayListExtra(EXTRA_RULE_SETS, ArrayList(input))

        override fun parseResult(resultCode: Int, intent: Intent?): List<String>? =
            if (resultCode == RESULT_OK) intent?.getStringArrayListExtra(EXTRA_RULE_SETS) else null
    }

    private enum class Kind { KNOWN, URL, UNKNOWN }

    private class Row(val entry: String, val source: String, val kind: Kind)

    private lateinit var binding: LayoutRuleSetPickerBinding
    private val selected = LinkedHashSet<String>()
    private var catalog = RuleSetCatalog(emptyList())
    private var rows = emptyList<Row>()
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
        updateSubtitle()

        binding.list.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        binding.list.adapter = adapter
        ViewCompat.setOnApplyWindowInsetsListener(binding.list, ListListener)
        binding.search.addTextChangedListener { rebuild() }
        onBackPressedDispatcher.addCallback(this) { finishWithResult() }

        lifecycleScope.launch {
            catalog = withContext(Dispatchers.IO) { RuleSetCatalog.current() }
            binding.catalogEmpty.isVisible = catalog.size == 0
            rebuild()
            binding.list.crossFadeFrom(binding.loading)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_SELECTED, ArrayList(selected))
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

    private fun rowOf(entry: String): Row {
        val normalized = entry.trim().replace(':', '-')
        catalog.urlOf(entry)?.let { return Row(normalized, RuleSets.source(it), Kind.KNOWN) }
        if (RuleSets.isUrl(entry)) return Row(entry, RuleSets.source(entry), Kind.URL)
        return Row(entry, "", Kind.UNKNOWN)
    }

    @Suppress("NotifyDataSetChanged")
    private fun rebuild() {
        val query = binding.search.text?.toString()?.trim().orEmpty()
        val normalizedSelected = selected.map { if (catalog.urlOf(it) != null) it.replace(':', '-') else it }.toSet()
        val extra = selected.map { rowOf(it) }.filter { it.kind != Kind.KNOWN }
            .sortedBy { if (it.kind == Kind.UNKNOWN) 0 else 1 }
            .filter { query.isEmpty() || it.entry.contains(query, true) }
        val matches = catalog.search(query, Int.MAX_VALUE).map { (name, url) -> Row(name, RuleSets.source(url), Kind.KNOWN) }
        val (picked, rest) = matches.partition { it.entry in normalizedSelected }
        rows = extra + picked + rest
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
            binding.catalogEmpty.isVisible = catalog.size == 0
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
                toggleSelection(row.entry)
                binding.check.isChecked = isEntrySelected(row.entry)
                updateSubtitle()
            }
        }

        fun bind(row: Row) {
            this.row = row
            binding.name.text = when (row.kind) {
                Kind.URL -> RuleSets.shortName(row.entry)
                else -> row.entry
            }
            binding.source.text = when (row.kind) {
                Kind.KNOWN -> row.source
                Kind.URL -> getString(R.string.rule_set_url_source, row.source)
                Kind.UNKNOWN -> getString(R.string.rule_set_unknown)
            }
            if (row.kind == Kind.UNKNOWN) {
                binding.source.setTextColor(ContextCompat.getColor(this@RuleSetPickerActivity, R.color.color_route_block))
            } else {
                binding.source.setTextColor(sourceColors)
            }
            binding.check.isChecked = isEntrySelected(row.entry)
        }
    }
}
