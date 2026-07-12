package dev.zapstore.app

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SearchActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var searchField: EditText
    private lateinit var status: TextView
    private lateinit var results: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
    }

    private fun buildScreen(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20.dp, 0, 20.dp, 28.dp)
        setBackgroundColor(ZapUi.background)
        addView(ZapUi.title(this@SearchActivity, "Search apps", 24f))
        lateinit var clearSearch: TextView
        searchField = EditText(this@SearchActivity).apply {
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
        clearSearch = TextView(this@SearchActivity).apply {
            text = "×"
            textSize = 28f
            gravity = android.view.Gravity.CENTER
            setTextColor(ZapUi.muted)
            visibility = TextView.GONE
            setOnClickListener {
                searchField.text.clear()
                status.visibility = TextView.GONE
                results.removeAllViews()
            }
            contentDescription = "Clear search"
        }
        addView(FrameLayout(this@SearchActivity).apply {
            addView(searchField, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 54.dp))
            addView(clearSearch, FrameLayout.LayoutParams(48.dp, 54.dp, android.view.Gravity.END))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 54.dp))
        status = ZapUi.label(this@SearchActivity, "", 13f).apply {
            visibility = TextView.GONE
        }
        addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 14.dp
            bottomMargin = 12.dp
        })
        results = LinearLayout(this@SearchActivity).apply { orientation = LinearLayout.VERTICAL }
        addView(ScrollView(this@SearchActivity).apply { addView(results) }, LinearLayout.LayoutParams(0, 0, 1f))
    }

    private fun search(rawQuery: String) {
        val query = rawQuery.trim()
        results.removeAllViews()
        if (query.length < 3) {
            status.visibility = TextView.GONE
            return
        }
        status.visibility = TextView.VISIBLE
        status.text = "Searching relay.zapstore.dev…"
        scope.launch {
            Catalog.queryRemote(this@SearchActivity, Filter(kinds = listOf(Catalog.appKind), search = query, limit = 20)).collect { state ->
                val apps = state.items.map(::AppInfo).distinctBy(AppInfo::address)
                status.text = state.error?.message ?: when {
                    apps.isEmpty() -> "No apps found for “$query”."
                    else -> "${apps.size} results for “$query”"
                }
                results.removeAllViews()
                apps.forEach { app ->
                    results.addView(
                        ZapUi.appCard(this@SearchActivity, app) {
                            startActivity(AppDetailActivity.intent(this@SearchActivity, app.identifier, app.event.pubKey))
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = 12.dp
                        },
                    )
                }
            }
        }
    }

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
