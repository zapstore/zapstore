package dev.zapstore.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class StackDetailActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var content: LinearLayout
    private var stack: StackInfo? = null
    private var appsByAddress: Map<String, AppInfo> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stackId = intent.getStringExtra(EXTRA_STACK_ID) ?: run {
            finish()
            return
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 0, 20.dp, 36.dp)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(ZapUi.background)
            addView(content)
        })
        render()
        observeStack(stackId)
    }

    private fun observeStack(stackId: String) {
        scope.launch {
            Catalog.query(
                this@StackDetailActivity,
                Filter(ids = listOf(stackId), kinds = listOf(Catalog.appStackKind), limit = 1),
            ).collect { state ->
                stack = state.items.firstOrNull()?.let(::StackInfo)
                render(state.error?.message)
                stack?.let(::observeApps)
            }
        }
    }

    private fun observeApps(currentStack: StackInfo) {
        val coordinates = currentStack.appAddresses.mapNotNull(String::toAppCoordinate)
        if (coordinates.isEmpty()) return
        scope.launch {
            Catalog.query(
                this@StackDetailActivity,
                Filter(
                    authors = coordinates.map(AppCoordinate::author).distinct(),
                    kinds = listOf(Catalog.appKind),
                    tags = mapOf("d" to coordinates.map(AppCoordinate::identifier).distinct()),
                    limit = coordinates.size * 2,
                ),
            ).collect { state ->
                appsByAddress = state.items.map(::AppInfo).associateBy(AppInfo::address)
                render(state.error?.message)
            }
        }
    }

    private fun render(error: String? = null) {
        content.removeAllViews()
        val currentStack = stack
        if (currentStack == null) {
            content.addView(ZapUi.title(this, "Loading stack…", 24f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 20.dp
            })
            error?.let { content.addView(ZapUi.label(this, it)) }
            return
        }
        content.addView(ZapUi.title(this, currentStack.name, 27f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 14.dp
        })
        content.addView(ZapUi.label(this, currentStack.description.ifBlank { "${currentStack.appAddresses.size} curated apps" }, 14f))
        content.addView(ZapUi.title(this, "Apps", 20f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 28.dp
            bottomMargin = 10.dp
        })
        currentStack.appAddresses.forEach { address ->
            val app = appsByAddress[address] ?: return@forEach
            content.addView(
                ZapUi.appCard(this, app) {
                    startActivity(AppDetailActivity.intent(this, app.identifier, app.event.pubKey))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12.dp },
            )
        }
        if (appsByAddress.isEmpty()) content.addView(ZapUi.label(this, "Loading apps in this stack…"))
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

    companion object {
        private const val EXTRA_STACK_ID = "stack_id"

        fun intent(context: Context, stackId: String): Intent =
            Intent(context, StackDetailActivity::class.java).putExtra(EXTRA_STACK_ID, stackId)
    }
}
