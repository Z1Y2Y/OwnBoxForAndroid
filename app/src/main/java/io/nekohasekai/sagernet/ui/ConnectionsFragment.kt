package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.content.Context
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** A small native connection inspector backed only by the local sing-box controller. */
class ConnectionsFragment : ToolbarFragment(R.layout.layout_connections) {
    private enum class Filter { ACTIVE, CLOSED, ALL }

    private data class ConnectionEntry(
        val id: String,
        val network: String,
        val source: String,
        val destination: String,
        val process: String,
        val packageName: String?,
        val processPath: String,
        val uid: Int?,
        val upload: Long,
        val download: Long,
        val uploadRate: Long,
        val downloadRate: Long,
        val chains: List<String>,
        val rule: String,
        val rulePayload: String,
        val start: String,
        val startMillis: Long,
        val metadata: String,
        val closedAt: Long? = null,
    )

    private val apiClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    private lateinit var chipGroup: ChipGroup
    private lateinit var chipActive: Chip
    private lateinit var chipClosed: Chip
    private lateinit var chipAll: Chip
    private lateinit var searchField: android.widget.EditText
    private lateinit var statusText: TextView
    private lateinit var emptyText: TextView
    private lateinit var list: RecyclerView
    private lateinit var adapter: ConnectionAdapter
    private val activeConnections = linkedMapOf<String, ConnectionEntry>()
    private val closedConnections = mutableListOf<ConnectionEntry>()
    private var filter = Filter.ACTIVE
    private var searchQuery = ""
    private var lastSnapshotMillis = 0L
    private var refreshJob: Job? = null
    private var pollJob: Job? = null
    private var statusMessage: String? = null
    private var controllerReady = false
    private val packagesByUid = mutableMapOf<Int, List<String>>()
    private val appIconCache = mutableMapOf<String, Drawable?>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_connections)
        toolbar.inflateMenu(R.menu.connections)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_connections_refresh -> {
                    refreshConnections()
                    true
                }
                R.id.action_connections_close_all -> {
                    confirmCloseAll()
                    true
                }
                else -> false
            }
        }

        chipGroup = view.findViewById(R.id.connections_filters)
        chipActive = view.findViewById(R.id.connections_filter_active)
        chipClosed = view.findViewById(R.id.connections_filter_closed)
        chipAll = view.findViewById(R.id.connections_filter_all)
        searchField = view.findViewById(R.id.connections_search)
        statusText = view.findViewById(R.id.connections_status)
        emptyText = view.findViewById(R.id.connections_empty)
        list = view.findViewById(R.id.connections_list)
        adapter = ConnectionAdapter()
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        chipGroup.setOnCheckedChangeListener { _, checkedId ->
            filter = when (checkedId) {
                R.id.connections_filter_closed -> Filter.CLOSED
                R.id.connections_filter_all -> Filter.ALL
                else -> Filter.ACTIVE
            }
            renderConnections()
        }
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim().orEmpty()
                renderConnections()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        renderConnections()
    }

    override fun onResume() {
        super.onResume()
        if (!::list.isInitialized || pollJob?.isActive == true) return
        pollJob = viewLifecycleOwner.lifecycleScope.launch {
                while (isActive) {
                    refreshConnections()
                    delay(1_000L)
                }
        }
    }

    override fun onPause() {
        pollJob?.cancel()
        pollJob = null
        refreshJob?.cancel()
        refreshJob = null
        controllerReady = false
        if (::list.isInitialized) renderConnections()
        super.onPause()
    }

    private fun refreshConnections() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { fetchConnections() }
            when (result) {
                is FetchResult.Success -> {
                    statusMessage = null
                    controllerReady = true
                    applySnapshot(result.entries)
                }
                is FetchResult.Failure -> {
                    statusMessage = getString(result.messageId)
                    controllerReady = false
                    if (result.messageId == R.string.connections_service_offline ||
                        result.messageId == R.string.connections_api_disabled
                    ) markActiveAsClosed()
                    renderConnections()
                }
            }
        }
    }

    private sealed class FetchResult {
        data class Success(val entries: List<ConnectionEntry>) : FetchResult()
        data class Failure(val messageId: Int) : FetchResult()
    }

    private fun fetchConnections(): FetchResult {
        if (!DataStore.serviceState.connected) {
            return FetchResult.Failure(R.string.connections_service_offline)
        }
        if (!DataStore.enableClashAPI && !DataStore.allowAccess) {
            return FetchResult.Failure(R.string.connections_api_disabled)
        }
        return try {
            val request = Request.Builder()
                .url("${LocalYacdDashboard.ENDPOINT}/connections")
                .header("Authorization", "Bearer ${DataStore.clashApiSecret}")
                .get()
                .build()
            apiClient.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 ->
                        FetchResult.Failure(R.string.connections_auth_failed)
                    !response.isSuccessful ->
                        FetchResult.Failure(R.string.connections_api_unavailable)
                    else -> {
                        val body = response.body?.string().orEmpty()
                        val root = JSONObject(body)
                        val array = root.optJSONArray("connections") ?: JSONArray()
                        val parsed = ArrayList<ConnectionEntry>(array.length())
                        for (index in 0 until array.length()) {
                            val item = array.optJSONObject(index) ?: continue
                            parseConnection(item)?.let(parsed::add)
                        }
                        FetchResult.Success(parsed)
                    }
                }
            }
        } catch (_: Exception) {
            FetchResult.Failure(R.string.connections_api_unavailable)
        }
    }

    private fun parseConnection(item: JSONObject): ConnectionEntry? {
        val id = item.optString("id").takeIf { it.isNotBlank() } ?: return null
        val metadata = item.optJSONObject("metadata") ?: JSONObject()
        val destination = ConnectionAddressFormatter.format(metadata)
        val sourceIp = metadata.optString("sourceIP")
        val sourcePort = metadata.optString("sourcePort").takeIf { it.isNotBlank() }
        val source = when {
            sourceIp.isBlank() -> ""
            sourcePort == null -> sourceIp
            else -> "$sourceIp:$sourcePort"
        }
        val chains = item.optJSONArray("chains")?.toStringList().orEmpty()
        val start = item.optString("start")
        val rule = item.optString("rule")
        val processPath = metadata.optString("processPath").trim()
        val process = metadata.optString("process").takeIf { it.isNotBlank() }
            ?: metadata.optString("processName").takeIf { it.isNotBlank() }
            ?: processPath.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: "—"
        return ConnectionEntry(
            id = id,
            network = metadata.optString("network", "TCP").uppercase().ifBlank { "TCP" },
            source = source,
            destination = destination,
            process = process,
            packageName = metadata.optConnectionPackageName() ?: item.optConnectionPackageName(),
            processPath = processPath,
            uid = metadata.optAndroidUid() ?: item.optAndroidUid()
                ?: processPath.extractAndroidUid() ?: process.extractAndroidUid(),
            upload = item.optLong("upload", 0L).coerceAtLeast(0L),
            download = item.optLong("download", 0L).coerceAtLeast(0L),
            uploadRate = 0L,
            downloadRate = 0L,
            chains = chains,
            rule = rule,
            rulePayload = item.optString("rulePayload"),
            start = start,
            startMillis = parseStartMillis(start),
            metadata = metadata.toString(),
        )
    }

    private fun JSONArray.toStringList(): List<String> = buildList(length()) {
        for (index in 0 until length()) {
            val value = opt(index)?.toString()?.takeIf { it.isNotBlank() }
            if (value != null) add(value)
        }
    }

    private fun JSONObject.optAndroidUid(): Int? {
        for (key in listOf("uid", "processUid", "processUID", "process_uid")) {
            val value = opt(key)
            val uid = when (value) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull()
                else -> null
            }
            if (uid != null && uid in 0..Int.MAX_VALUE.toLong()) return uid.toInt()
        }
        return null
    }

    private fun JSONObject.optConnectionPackageName(): String? {
        for (key in listOf("packageName", "package", "processPackage", "processPackageName")) {
            val value = optString(key).trim()
            if (value.isNotBlank() && value != "null") return value
        }
        return null
    }

    private fun String.extractAndroidUid(): Int? {
        val suffixUid = PROCESS_UID_SUFFIX.find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (suffixUid != null) return suffixUid
        return trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
    }

    private fun resolveApplicationIcon(entry: ConnectionEntry, context: Context): Drawable? {
        val packageManager = context.packageManager
        val ownApplicationInfo = context.applicationInfo
        val ownPackageName = ownApplicationInfo.packageName
        val packages = entry.uid?.let { uid ->
            packagesByUid.getOrPut(uid) {
                runCatching { packageManager.getPackagesForUid(uid)?.toList().orEmpty() }
                    .getOrDefault(emptyList())
            }
        }.orEmpty()
        val hints = listOf(entry.processPath, entry.process, entry.packageName.orEmpty())
        val isOwnApplication = ConnectionPackageResolver.matchesPackage(hints, ownPackageName) ||
            ConnectionPackageResolver.matchesUnambiguousUid(
                connectionUid = entry.uid,
                applicationUid = ownApplicationInfo.uid,
                packageName = ownPackageName,
                packagesForUid = packages,
            )
        val packageName = if (isOwnApplication) ownPackageName else {
            ConnectionPackageResolver.selectPackage(hints, packages) ?: return null
        }

        if (packageName == ownPackageName) {
            appIconCache[packageName]?.let { return it.copyFor(context) }
            val icon = runCatching { ownApplicationInfo.loadIcon(packageManager) }.getOrNull()
            if (icon != null) appIconCache[packageName] = icon
            return icon?.copyFor(context)
        }

        if (appIconCache.containsKey(packageName)) {
            return appIconCache[packageName]?.copyFor(context)
        }
        val icon = runCatching {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            if ((entry.uid != null && applicationInfo.uid != entry.uid) || applicationInfo.icon == 0) null
            else applicationInfo.loadIcon(packageManager)
        }.getOrNull()
        appIconCache[packageName] = icon
        return icon?.copyFor(context)
    }

    private fun Drawable.copyFor(context: Context): Drawable =
        constantState?.newDrawable(context.resources)?.mutate() ?: this

    private fun parseStartMillis(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
    }

    private fun applySnapshot(incoming: List<ConnectionEntry>) {
        val now = System.currentTimeMillis()
        val elapsed = (now - lastSnapshotMillis).coerceAtLeast(1L)
        val next = linkedMapOf<String, ConnectionEntry>()
        incoming.forEach { entry ->
            val previous = activeConnections[entry.id]
            val withRates = if (previous == null) entry else entry.copy(
                uploadRate = ((entry.upload - previous.upload).coerceAtLeast(0L) * 1_000L / elapsed),
                downloadRate = ((entry.download - previous.download).coerceAtLeast(0L) * 1_000L / elapsed),
            )
            next[entry.id] = withRates
            closedConnections.removeAll { it.id == entry.id }
        }
        activeConnections.values.filter { it.id !in next }.forEach { old ->
            closedConnections.add(0, old.copy(closedAt = now))
        }
        if (closedConnections.size > MAX_CLOSED_CONNECTIONS) {
            closedConnections.subList(MAX_CLOSED_CONNECTIONS, closedConnections.size).clear()
        }
        activeConnections.clear()
        activeConnections.putAll(next)
        lastSnapshotMillis = now
        renderConnections()
    }

    private fun markActiveAsClosed() {
        val now = System.currentTimeMillis()
        activeConnections.values.forEach { closedConnections.add(0, it.copy(closedAt = now)) }
        activeConnections.clear()
        lastSnapshotMillis = now
        if (closedConnections.size > MAX_CLOSED_CONNECTIONS) {
            closedConnections.subList(MAX_CLOSED_CONNECTIONS, closedConnections.size).clear()
        }
    }

    private fun renderConnections() {
        if (!::list.isInitialized) return
        chipActive.text = getString(R.string.connections_filter_active, activeConnections.size)
        chipClosed.text = getString(R.string.connections_filter_closed, closedConnections.size)
        chipAll.text = getString(R.string.connections_filter_all)
        val source = when (filter) {
            Filter.ACTIVE -> activeConnections.values.toList()
            Filter.CLOSED -> closedConnections.toList()
            Filter.ALL -> (activeConnections.values + closedConnections).sortedByDescending { it.startMillis }
        }
        val query = searchQuery.lowercase()
        val filtered = if (query.isBlank()) source else source.filter { entry ->
            listOf(entry.destination, entry.source, entry.process, entry.network, entry.rule, entry.rulePayload)
                .any { it.lowercase().contains(query) }
        }
        adapter.submit(filtered)
        emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        emptyText.text = when {
            statusMessage != null -> statusMessage
            filter == Filter.CLOSED -> getString(R.string.connections_empty_closed)
            filter == Filter.ACTIVE -> getString(R.string.connections_empty_active)
            else -> getString(R.string.connections_empty_all)
        }
        if (statusMessage == null) {
            statusText.text = getString(
                R.string.connections_status_active,
                activeConnections.size,
                formatSize(activeConnections.values.sumOf { it.upload }),
                formatSize(activeConnections.values.sumOf { it.download }),
            )
        } else {
            statusText.text = statusMessage
        }
    }

    private fun confirmCloseAll() {
        if (!controllerReady) {
            Snackbar.make(requireView(), R.string.connections_api_unavailable, Snackbar.LENGTH_LONG).show()
            return
        }
        if (activeConnections.isEmpty()) {
            Snackbar.make(requireView(), R.string.connections_none_to_close, Snackbar.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.connections_close_all_title)
            .setMessage(getString(R.string.connections_close_all_message, activeConnections.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.connections_close_all) { _, _ -> closeAllConnections() }
            .show()
    }

    private fun closeAllConnections() {
        performDelete(listOf("connections"))
    }

    private fun closeConnection(connection: ConnectionEntry) {
        performDelete(listOf("connections", connection.id))
    }

    private fun performDelete(pathSegments: List<String>) {
        if (!controllerReady) return
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val urlBuilder = LocalYacdDashboard.ENDPOINT.toHttpUrl().newBuilder()
                    pathSegments.forEach(urlBuilder::addPathSegment)
                    val url = urlBuilder.build()
                    val request = Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer ${DataStore.clashApiSecret}")
                        .method("DELETE", null)
                        .build()
                    apiClient.newCall(request).execute().use { it.isSuccessful }
                }.getOrDefault(false)
            }
            if (result) {
                Snackbar.make(requireView(), R.string.connections_closed, Snackbar.LENGTH_SHORT).show()
                refreshConnections()
            } else {
                Snackbar.make(requireView(), R.string.connections_close_failed, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showDetails(entry: ConnectionEntry) {
        val canClose = entry.closedAt == null && controllerReady
        val closedText = entry.closedAt?.let {
            getString(R.string.connections_detail_closed, DateUtils.getRelativeTimeSpanString(it))
        }.orEmpty()
        val details = buildString {
            append(getString(R.string.connections_detail_network, entry.network))
            append("\n")
            append(getString(R.string.connections_detail_process, entry.process))
            if (entry.source.isNotBlank()) append("\n").append(getString(R.string.connections_detail_source, entry.source))
            append("\n").append(getString(R.string.connections_detail_destination, entry.destination))
            append("\n").append(getString(R.string.connections_detail_traffic, formatSize(entry.upload), formatSize(entry.download)))
            append("\n").append(getString(R.string.connections_detail_chain, entry.chains.joinToString(" → ").ifBlank { getString(R.string.connections_direct) }))
            append("\n").append(getString(R.string.connections_detail_rule, entry.rule.ifBlank { "-" }))
            if (entry.rulePayload.isNotBlank()) append("\n").append(getString(R.string.connections_detail_rule_payload, entry.rulePayload))
            if (entry.start.isNotBlank()) append("\n").append(getString(R.string.connections_detail_start, entry.start))
            if (closedText.isNotBlank()) append("\n").append(closedText)
            val metadata = runCatching { JSONObject(entry.metadata) }.getOrNull()
            metadata?.optString("inbound")?.takeIf { it.isNotBlank() }?.let {
                append("\n").append(getString(R.string.connections_detail_inbound, it))
            }
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(entry.destination)
            .setMessage(details)
            .setPositiveButton(if (canClose) R.string.connections_close else android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            if (canClose) {
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    dialog.dismiss()
                    closeConnection(entry)
                }
            }
        }
        dialog.show()
    }

    private fun formatSize(bytes: Long): String = android.text.format.Formatter.formatShortFileSize(requireContext(), bytes)

    private fun formatRate(bytesPerSecond: Long): String = formatSize(bytesPerSecond)

    private inner class ConnectionAdapter : RecyclerView.Adapter<ConnectionHolder>() {
        private var entries: List<ConnectionEntry> = emptyList()

        fun submit(values: List<ConnectionEntry>) {
            entries = values
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConnectionHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_connection, parent, false)
            return ConnectionHolder(view)
        }

        override fun onBindViewHolder(holder: ConnectionHolder, position: Int) = holder.bind(entries[position])
        override fun getItemCount(): Int = entries.size
    }

    private inner class ConnectionHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val appIcon: ImageView = view.findViewById(R.id.conn_app_icon)
        private val appName: TextView = view.findViewById(R.id.conn_app_name)
        private val network: TextView = view.findViewById(R.id.conn_network)
        private val host: TextView = view.findViewById(R.id.conn_host)
        private val rule: TextView = view.findViewById(R.id.conn_rule)
        private val traffic: TextView = view.findViewById(R.id.conn_traffic)
        private val chains: TextView = view.findViewById(R.id.conn_chains)
        private val close: ImageView = view.findViewById(R.id.conn_close_btn)

        fun bind(entry: ConnectionEntry) {
            appName.text = if (entry.closedAt == null) entry.process else
                "${entry.process} · ${getString(R.string.connections_state_closed)}"
            appIcon.setImageResource(R.drawable.ic_navigation_apps)
            resolveApplicationIcon(entry, itemView.context)?.let(appIcon::setImageDrawable)
            network.text = entry.network
            host.text = entry.destination
            rule.text = getString(R.string.connections_detail_rule, entry.rule.ifBlank { "—" })
            traffic.text = getString(
                R.string.connections_item_traffic,
                formatRate(entry.uploadRate),
                formatRate(entry.downloadRate),
                formatSize(entry.upload),
                formatSize(entry.download),
            )
            val closed = entry.closedAt != null
            chains.text = getString(
                R.string.connections_detail_chain,
                entry.chains.joinToString(" → ").ifBlank { getString(R.string.connections_direct) },
            )
            close.visibility = if (closed || !controllerReady) View.GONE else View.VISIBLE
            close.setOnClickListener { closeConnection(entry) }
            itemView.setOnClickListener { showDetails(entry) }
        }
    }

    private fun JSONArray.optJSONObject(index: Int): JSONObject? = opt(index) as? JSONObject

    companion object {
        private const val MAX_CLOSED_CONNECTIONS = 100
        private val PROCESS_UID_SUFFIX = Regex("""\s+\((\d+)\)$""")
    }
}
