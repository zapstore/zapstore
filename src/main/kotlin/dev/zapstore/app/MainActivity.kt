package dev.zapstore.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import android.text.Editable
import android.text.TextWatcher
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var stackList: LinearLayout
    private lateinit var releaseList: LinearLayout
    private lateinit var searchResults: LinearLayout
    private lateinit var searchStatus: TextView
    private lateinit var searchField: EditText
    private lateinit var status: TextView
    private var appQueryJob: Job? = null
    private var stackPreviewJob: Job? = null
    private var searchJob: Job? = null
    private var releaseApps: Map<String, AppInfo> = emptyMap()
    private var stackApps: Map<String, AppInfo> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        observeStacks()
        observeLatestReleases()
    }

    private fun buildScreen(): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 0, 20.dp, 32.dp)
        }
        lateinit var clearSearch: TextView
        searchField = EditText(this).apply {
            hint = "Search apps"
            textSize = 17f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setHintTextColor(ZapUi.muted)
            setTextColor(ZapUi.textColor)
            setPadding(16.dp, 0, 52.dp, 0)
            background = ZapUi.rounded(ZapUi.surfaceVariant, 16.dp)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    search(searchField.text.toString())
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                    clearSearch.visibility = if (text.isNullOrEmpty()) TextView.GONE else TextView.VISIBLE
                }
                override fun afterTextChanged(text: Editable?) = Unit
            })
        }
        clearSearch = TextView(this).apply {
            text = "×"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(ZapUi.muted)
            visibility = TextView.GONE
            setOnClickListener {
                searchField.text.clear()
                searchStatus.visibility = TextView.GONE
                searchResults.removeAllViews()
            }
            contentDescription = "Clear search"
        }
        content.addView(FrameLayout(this).apply {
            addView(searchField, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 54.dp))
            addView(clearSearch, FrameLayout.LayoutParams(48.dp, 54.dp, Gravity.END))
        })
        searchStatus = ZapUi.label(this, "", 13f).apply {
            visibility = TextView.GONE
        }
        content.addView(searchStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 14.dp
            bottomMargin = 12.dp
        })
        searchResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(searchResults)

        content.addView(sectionTitle("Curated stacks"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 28.dp
            bottomMargin = 10.dp
        })
        stackList = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(stackList)
        })

        content.addView(sectionTitle("Latest releases"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 30.dp
            bottomMargin = 6.dp
        })
        status = ZapUi.label(this, "Connecting to relay.zapstore.dev", 13f)
        content.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 14.dp
        })
        releaseList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(releaseList)
        return ScrollView(this).apply {
            setBackgroundColor(ZapUi.background)
            addView(content)
        }
    }

    private fun observeStacks() {
        scope.launch {
            Catalog.query(
                this@MainActivity,
                Filter(authors = listOf(Catalog.communityPubkey), kinds = listOf(Catalog.appStackKind), limit = 20),
            ).collect { state ->
                val stacks = state.items.map(::StackInfo).sortedByDescending { it.event.createdAt }
                renderStacks(stacks, state.items.isEmpty())
                fetchStackPreviewApps(stacks)
            }
        }
    }

    private fun observeLatestReleases() {
        scope.launch {
            Catalog.query(
                this@MainActivity,
                Filter(kinds = listOf(Catalog.releaseKind), limit = 20),
            ).collect { state ->
                val releases = state.items.map(::ReleaseInfo)
                    .sortedByDescending { it.event.createdAt }
                    .distinctBy { it.appIdentifier ?: it.event.id }
                    .take(10)
                status.text = state.error?.message ?: "${releases.size} recently updated apps"
                renderReleases(releases)
                fetchReleaseApps(releases)
            }
        }
    }

    private fun fetchReleaseApps(releases: List<ReleaseInfo>) {
        val identifiers = releases.mapNotNull(ReleaseInfo::appIdentifier).toSet()
        appQueryJob?.cancel()
        if (identifiers.isEmpty()) return
        appQueryJob = scope.launch {
            Catalog.query(
                this@MainActivity,
                Filter(kinds = listOf(Catalog.appKind), tags = mapOf("d" to identifiers.toList()), limit = identifiers.size * 3),
            ).collect { state ->
                releaseApps = state.items.map(::AppInfo).associateBy(AppInfo::identifier)
                renderReleases(releases)
            }
        }
    }

    private fun fetchStackPreviewApps(stacks: List<StackInfo>) {
        val coordinates = stacks.flatMap(StackInfo::appAddresses).mapNotNull(String::toAppCoordinate).distinct()
        stackPreviewJob?.cancel()
        if (coordinates.isEmpty()) return
        stackPreviewJob = scope.launch {
            Catalog.query(
                this@MainActivity,
                Filter(
                    authors = coordinates.map(AppCoordinate::author).distinct(),
                    kinds = listOf(Catalog.appKind),
                    tags = mapOf("d" to coordinates.map(AppCoordinate::identifier).distinct()),
                    limit = coordinates.size * 2,
                ),
            ).collect { state ->
                stackApps = state.items.map(::AppInfo).associateBy(AppInfo::address)
                renderStacks(stacks, false)
            }
        }
    }

    private fun renderStacks(stacks: List<StackInfo>, loading: Boolean) {
        stackList.removeAllViews()
        if (stacks.isEmpty()) {
            stackList.addView(ZapUi.label(this, if (loading) "Loading community stacks…" else "No stacks found."))
            return
        }
        stacks.forEach { stack ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14.dp, 14.dp, 14.dp, 14.dp)
                background = ZapUi.rounded(ZapUi.surface, 18.dp)
                isClickable = true
                setOnClickListener { startActivity(StackDetailActivity.intent(this@MainActivity, stack.event.id)) }
            }
            card.addView(ZapUi.title(this, stack.name, 16f))
            card.addView(ZapUi.label(this, if (stack.description.isBlank()) "${stack.appAddresses.size} apps" else stack.description, 12f))
            val previews = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12.dp, 0, 0)
            }
            stack.appAddresses.take(3).forEach { address ->
                val app = stackApps[address]
                previews.addView(
                    ZapUi.appIcon(this, app?.name ?: "?", address, app?.iconUrl),
                    LinearLayout.LayoutParams(34.dp, 34.dp).apply { marginEnd = 6.dp },
                )
            }
            card.addView(previews)
            stackList.addView(card, LinearLayout.LayoutParams(176.dp, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 10.dp })
        }
    }

    private fun renderReleases(releases: List<ReleaseInfo>) {
        releaseList.removeAllViews()
        releases.forEach { release ->
            val app = release.appIdentifier?.let(releaseApps::get) ?: AppInfo(release.event)
            releaseList.addView(
                ZapUi.appCard(this, app, release) {
                    startActivity(AppDetailActivity.intent(this, app.identifier, app.event.pubKey))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12.dp },
            )
        }
    }

    private fun search(rawQuery: String) {
        val query = rawQuery.trim()
        searchResults.removeAllViews()
        searchJob?.cancel()
        if (query.length < 3) {
            searchStatus.visibility = TextView.GONE
            return
        }
        searchStatus.visibility = TextView.VISIBLE
        searchStatus.text = "Searching relay.zapstore.dev…"
        searchJob = scope.launch {
            Catalog.queryRemote(
                this@MainActivity,
                Filter(kinds = listOf(Catalog.appKind), search = query, limit = 20),
            ).collect { state ->
                val apps = state.items.map(::AppInfo).distinctBy(AppInfo::address)
                searchStatus.text = state.error?.message ?: when {
                    apps.isEmpty() -> "No apps found for “$query”."
                    else -> "${apps.size} results for “$query”"
                }
                searchResults.removeAllViews()
                apps.forEach { app ->
                    searchResults.addView(
                        ZapUi.appCard(this@MainActivity, app) {
                            startActivity(AppDetailActivity.intent(this@MainActivity, app.identifier, app.event.pubKey))
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = 12.dp
                        },
                    )
                }
            }
        }
    }

    private fun sectionTitle(value: String): TextView = ZapUi.title(this, value, 21f)

    override fun onResume() {
        super.onResume()
        Catalog.refreshConnections()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
