package dev.zapstore.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.text.method.LinkMovementMethod
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class AppDetailActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var content: LinearLayout
    private var app: AppInfo? = null
    private var release: ReleaseInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val identifier = intent.getStringExtra(EXTRA_IDENTIFIER) ?: run {
            finish()
            return
        }
        val author = intent.getStringExtra(EXTRA_AUTHOR)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 0, 20.dp, 36.dp)
        }
        setContentView(android.widget.ScrollView(this).apply {
            setBackgroundColor(ZapUi.background)
            addView(content)
        })
        render()
        observeApp(identifier, author)
        observeRelease(identifier)
    }

    private fun observeApp(identifier: String, author: String?) {
        scope.launch {
            Catalog.query(
                this@AppDetailActivity,
                Filter(
                    authors = author?.let(::listOf),
                    kinds = listOf(Catalog.appKind),
                    tags = mapOf("d" to listOf(identifier)),
                    limit = 3,
                ),
            ).collect { state ->
                state.items.firstOrNull()?.let { app = AppInfo(it) }
                render(state.error?.message)
            }
        }
    }

    private fun observeRelease(identifier: String) {
        scope.launch {
            Catalog.query(
                this@AppDetailActivity,
                Filter(kinds = listOf(Catalog.releaseKind), tags = mapOf("i" to listOf(identifier)), limit = 10),
            ).collect { state ->
                release = state.items.map(::ReleaseInfo).maxByOrNull { it.event.createdAt }
                render(state.error?.message)
            }
        }
    }

    private fun render(error: String? = null) {
        content.removeAllViews()

        val currentApp = app
        if (currentApp == null) {
            content.addView(TextView(this).apply {
                background = ZapUi.rounded(ZapUi.surfaceVariant, 16.dp)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100.dp).apply { topMargin = 20.dp })
            error?.let { content.addView(ZapUi.label(this, it)) }
            return
        }
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.TOP
            addView(ZapUi.appIcon(this@AppDetailActivity, currentApp.name, currentApp.identifier, currentApp.iconUrl), LinearLayout.LayoutParams(84.dp, 84.dp))
            addView(LinearLayout(this@AppDetailActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(ZapUi.title(this@AppDetailActivity, currentApp.name, 27f))
                addView(ZapUi.label(this@AppDetailActivity, currentApp.identifier, 13f))
                release?.let {
                    addView(TextView(this@AppDetailActivity).apply {
                        text = buildString {
                            append(it.version)
                            it.channel?.let { channel -> append(" · ").append(channel) }
                        }
                        textSize = 13f
                        setTextColor(ZapUi.textColor)
                        background = ZapUi.rounded(ZapUi.primary, 12.dp)
                        setPadding(10.dp, 4.dp, 10.dp, 4.dp)
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = 10.dp
                    })
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 16.dp })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 14.dp })

        content.addView(ZapUi.label(this, "Published by · ${currentApp.event.pubKey.take(16)}…", 13f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 14.dp
        })
        if (currentApp.screenshots.isNotEmpty()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            currentApp.screenshots.take(5).forEach { url ->
                row.addView(ZapUi.appIcon(this, currentApp.name, url, url), LinearLayout.LayoutParams(176.dp, 176.dp).apply { marginEnd = 10.dp })
            }
            content.addView(HorizontalScrollView(this).apply { addView(row) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 20.dp
            })
        }
        if (currentApp.event.content.isNotBlank()) {
            content.addView(body(currentApp.event.content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 20.dp
            })
        } else if (currentApp.summary.isNotBlank()) {
            content.addView(body(currentApp.summary), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 20.dp
            })
        }

        latestReleaseDivider()
        release?.let {
            content.addView(versionRow(it))
            if (it.notes.isNotBlank()) content.addView(body(it.notes), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 18.dp
            })
        } ?: content.addView(TextView(this).apply {
            background = ZapUi.rounded(ZapUi.surfaceVariant, 8.dp)
        }, LinearLayout.LayoutParams(220.dp, 32.dp))

        content.addView(infoCard(currentApp), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 28.dp
        })
    }

    private fun section(value: String) {
        content.addView(ZapUi.title(this, value, 19f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 26.dp
            bottomMargin = 8.dp
        })
    }

    private fun latestReleaseDivider() {
        val divider = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        divider.addView(TextView(this).apply { setBackgroundColor(ZapUi.outline) }, LinearLayout.LayoutParams(0, 1.dp, 1f))
        divider.addView(TextView(this).apply {
            text = "LATEST RELEASE"
            textSize = 12f
            typeface = resources.getFont(R.font.inter_bold)
            letterSpacing = .12f
            setTextColor(ZapUi.textColor)
            setPadding(16.dp, 0, 16.dp, 0)
        })
        divider.addView(TextView(this).apply { setBackgroundColor(ZapUi.outline) }, LinearLayout.LayoutParams(0, 1.dp, 1f))
        content.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 30.dp
            bottomMargin = 16.dp
        })
    }

    private fun versionRow(value: ReleaseInfo): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = ZapUi.rounded(ZapUi.surfaceVariant, 8.dp)
        setPadding(12.dp, 8.dp, 12.dp, 8.dp)
        addView(ZapUi.label(this@AppDetailActivity, "Version:", 14f))
        addView(TextView(this@AppDetailActivity).apply {
            text = value.version
            textSize = 14f
            typeface = resources.getFont(R.font.inter_bold)
            setTextColor(ZapUi.textColor)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 6.dp })
        addView(ZapUi.label(this@AppDetailActivity, "(${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(value.event.createdAt * 1_000))})", 13f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = 6.dp
        })
    }

    private fun infoCard(currentApp: AppInfo): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ZapUi.rounded(ZapUi.surface, 12.dp)
        setPadding(16.dp, 8.dp, 16.dp, 8.dp)
        infoRow("Source", currentApp.repository ?: "Not available", currentApp.repository)
        currentApp.license?.let { infoRow("License", it) }
        infoRow("App ID", currentApp.identifier)
        infoRow("Author", "${currentApp.event.pubKey.take(16)}…")
        release?.let { infoRow("Release date", DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.event.createdAt * 1_000))) }
    }

    private fun LinearLayout.infoRow(label: String, value: String, link: String? = null) {
        addView(LinearLayout(this@AppDetailActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10.dp, 0, 10.dp)
            addView(ZapUi.label(this@AppDetailActivity, label, 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@AppDetailActivity).apply {
                text = value
                textSize = 14f
                setTextColor(if (link != null) ZapUi.primary else ZapUi.textColor)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                if (link != null && (link.startsWith("https://") || link.startsWith("http://"))) {
                    setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    private fun body(value: String) = TextView(this).apply {
        text = ZapMarkdown.parse(value)
        textSize = 15f
        typeface = resources.getFont(R.font.inter_regular)
        setTextColor(ZapUi.muted)
        setLineSpacing(5f, 1f)
        movementMethod = LinkMovementMethod.getInstance()
    }

    private fun addExternalLink(label: String, rawUrl: String) {
        if (!rawUrl.startsWith("https://") && !rawUrl.startsWith("http://")) return
        content.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(ZapUi.primary)
            setPadding(0, 10.dp, 0, 0)
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rawUrl))) }
        })
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
        private const val EXTRA_IDENTIFIER = "identifier"
        private const val EXTRA_AUTHOR = "author"

        fun intent(context: Context, identifier: String, author: String?): Intent =
            Intent(context, AppDetailActivity::class.java)
                .putExtra(EXTRA_IDENTIFIER, identifier)
                .putExtra(EXTRA_AUTHOR, author)
    }
}
