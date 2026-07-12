package dev.zapstore.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil3.load
import coil3.request.crossfade

object ZapUi {
    val background = Color.rgb(10, 15, 26)
    val surface = Color.rgb(15, 20, 27)
    val surfaceVariant = Color.rgb(22, 28, 39)
    val outline = Color.rgb(45, 55, 72)
    val primary = Color.rgb(58, 111, 204)
    val textColor = Color.rgb(232, 234, 237)
    val muted = Color.rgb(184, 188, 200)
    private val iconBackground = Color.rgb(45, 45, 45)

    fun title(context: Context, value: String, size: Float = 24f) = TextView(context).apply {
        text = value
        textSize = size
        typeface = Typeface.create(context.resources.getFont(R.font.inter_display_extra_bold), Typeface.BOLD)
        setTextColor(textColor)
    }

    fun label(context: Context, value: String, size: Float = 14f) = TextView(context).apply {
        text = value
        textSize = size
        typeface = context.resources.getFont(R.font.inter_regular)
        setTextColor(muted)
    }

    fun appCard(
        context: Context,
        app: AppInfo,
        release: ReleaseInfo? = null,
        onClick: () -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(16.dp(context), 14.dp(context), 16.dp(context), 16.dp(context))
        background = rounded(surface, 18.dp(context))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }

        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(appIcon(context, app.name, app.identifier, app.iconUrl), LinearLayout.LayoutParams(52.dp(context), 52.dp(context)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = app.name
                    textSize = 17f
                    typeface = Typeface.create(context.resources.getFont(R.font.inter_display_extra_bold), Typeface.BOLD)
                    setTextColor(textColor)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(label(context, buildString {
                    release?.let {
                        append(it.version)
                        it.channel?.let { channel -> append(" · ").append(channel) }
                        append(" · ")
                    }
                    append(app.identifier)
                }, 12f))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 12.dp(context)
            })
        })
        if (app.summary.isNotBlank()) {
            addView(TextView(context).apply {
                text = ZapMarkdown.parse(app.summary)
                textSize = 14f
                typeface = context.resources.getFont(R.font.inter_regular)
                setTextColor(muted)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, 10.dp(context), 0, 0)
            })
        }
    }

    fun appIcon(context: Context, title: String, identity: String, iconUrl: String?): FrameLayout =
        FrameLayout(context).apply {
            addView(TextView(context).apply {
                text = title.take(1).uppercase()
                textSize = 20f
                gravity = Gravity.CENTER
                typeface = context.resources.getFont(R.font.inter_bold)
                setTextColor(Color.WHITE)
                background = rounded(iconBackground, 16.dp(context))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            iconUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { url ->
                val image = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = "$title icon"
                    clipToOutline = true
                    background = rounded(iconBackground, 16.dp(context))
                }
                addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                image.load(url) {
                    crossfade(250)
                }
            }
        }

    fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    fun View.setVerticalMargin(bottom: Int) {
        layoutParams = (layoutParams as? ViewGroup.MarginLayoutParams)?.apply { bottomMargin = bottom } ?: layoutParams
    }

    private fun Int.dp(context: Context): Int = (this * context.resources.displayMetrics.density).toInt()
}
