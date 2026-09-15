package com.minimal.browser.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.minimal.browser.DataStore
import com.minimal.browser.R
import com.minimal.browser.UrlBar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * `#s-home` — greeting, big search, eight quick links, "Continue reading" and
 * the shields counter. Everything here is live: the quick links really open,
 * the recents really come from history, the blocked count is real.
 */
class HomeScreen(context: Context) : ScrollView(context) {

    var onSubmitSearch: ((String) -> Unit)? = null
    var onOpenUrl: ((String) -> Unit)? = null

    private val searchField: EditText
    private val recentsBox: LinearLayout
    private val emptyLabel: TextView
    private val footer: TextView
    private val dateLabel: TextView
    private val greeting: TextView

    private data class QuickLink(val emoji: String, val label: String, val url: String)

    private val quickLinks = listOf(
        QuickLink("✉️", "Mail", "https://mail.google.com"),
        QuickLink("▶️", "YouTube", "https://m.youtube.com"),
        QuickLink("🗺️", "Maps", "https://maps.google.com"),
        QuickLink("📚", "Wikipedia", "https://en.wikipedia.org"),
        QuickLink("🐙", "GitHub", "https://github.com"),
        QuickLink("📰", "News", "https://news.ycombinator.com"),
        QuickLink("🛒", "Shop", "https://www.daraz.pk"),
        QuickLink("🕶️", "Private", "__private__")
    )

    init {
        isFillViewport = true
        setBackgroundColor(Ink.SHELL)
        isVerticalScrollBarEnabled = true

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(context.dp(24), context.dp(8), context.dp(24), context.dp(16))
        }
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        /* ---------------- greeting ---------------- */
        dateLabel = TextView(context).apply {
            setTextColor(Ink.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
        }
        greeting = TextView(context).apply {
            setTextColor(Ink.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        root.addView(dateLabel, wrap().apply { topMargin = context.dp(22) })
        root.addView(greeting, wrap().apply { topMargin = context.dp(4) })

        /* ---------------- big search (.bigsearch) ---------------- */
        val search = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.roundRect(16, Ink.PANEL, Ink.EDGE2)
            setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
        }
        search.addView(ImageView(context).apply {
            icon(R.drawable.ic_search, Ink.MUTED)
        }, LinearLayout.LayoutParams(context.dp(20), context.dp(20)).apply {
            rightMargin = context.dp(10)
        })

        searchField = EditText(context).apply {
            hint = context.getString(R.string.home_search_hint)
            setHintTextColor(Ink.MUTED)
            setTextColor(Ink.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isSingleLine = true
            maxLines = 1
            background = null
            setPadding(0, 0, 0, 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            UiKeys.configureTextInput(this)
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && UiKeys.isEnterKey(event.keyCode))
                ) {
                    submit()
                    true
                } else false
            }
            setOnKeyListener { _, keyCode, event ->
                // Some attached keyboards are consumed by the editor before
                // ACTION_UP. Submit on the first down event instead.
                if (event.action == KeyEvent.ACTION_DOWN &&
                    event.repeatCount == 0 &&
                    UiKeys.isEnterKey(keyCode)
                ) {
                    submit()
                    true
                } else false
            }
        }
        search.addView(searchField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            weight = 1f
        })

        // the "⏎" keycap from the HTML — and it actually submits
        val keycap = TextView(context).apply {
            text = "⏎"
            setTextColor(Ink.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            background = context.roundRect(7, Color.TRANSPARENT, Ink.EDGE2)
            setPadding(context.dp(8), context.dp(3), context.dp(8), context.dp(3))
            setOnClickListener { submit() }
        }
        search.addView(keycap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = context.dp(10) })

        root.addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(22)
        })

        /* ---------------- quick links (.quicklinks) ---------------- */
        val grid = GridLayout(context).apply {
            columnCount = 4
        }
        quickLinks.forEachIndexed { index, link ->
            grid.addView(quickLinkView(link), GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(index % 4, 1f)
                rowSpec = GridLayout.spec(index / 4)
                setMargins(
                    context.dp(7), context.dp(7), context.dp(7), context.dp(7)
                )
            })
        }
        root.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(23)
        })

        /* ---------------- continue reading (.recent) ---------------- */
        val recentWrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        recentWrap.addView(TextView(context).apply {
            text = context.getString(R.string.home_continue).uppercase(Locale.US)
            setTextColor(Ink.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.14f
        }, wrap().apply { bottomMargin = context.dp(12) })

        emptyLabel = TextView(context).apply {
            text = context.getString(R.string.home_empty_recent)
            setTextColor(Ink.DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(context.dp(14), context.dp(6), context.dp(14), context.dp(6))
        }
        recentsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        recentWrap.addView(recentsBox)
        recentWrap.addView(emptyLabel)

        root.addView(recentWrap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(26)
        })

        /* ---------------- footer (.homefoot) ---------------- */
        footer = TextView(context).apply {
            setTextColor(Ink.DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
        }
        root.addView(footer, wrap().apply { topMargin = context.dp(26) })

        refreshGreeting()
    }

    /* ------------------------------------------------------------------ */

    private fun wrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun submit() {
        val q = searchField.text?.toString()?.trim().orEmpty()
        if (q.isEmpty()) return
        searchField.setText("")
        UiKeys.hideKeyboard(searchField)
        onSubmitSearch?.invoke(q)
    }

    /** Activity-level fallback for physical Enter events consumed by an OEM IME/editor. */
    fun submitSearchIfFocused(): Boolean {
        if (!searchField.hasFocus() || searchField.text?.toString()?.trim().isNullOrEmpty()) return false
        submit()
        return true
    }

    private fun quickLinkView(link: QuickLink): View {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
        }
        val tile = TextView(context).apply {
            text = link.emoji
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
            background = context.roundRect(16, Ink.PANEL2, Ink.EDGE)
        }
        column.addView(tile, LinearLayout.LayoutParams(context.dp(58), context.dp(58)))
        column.addView(TextView(context).apply {
            text = link.label
            setTextColor(Ink.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = Gravity.CENTER
        }, wrap().apply { topMargin = context.dp(8) })

        column.setOnClickListener {
            if (link.url == "__private__") {
                onOpenUrl?.invoke("__private__")
            } else {
                onOpenUrl?.invoke(link.url)
            }
        }
        return column
    }

    /** Re-evaluate soft-input behavior after a physical keyboard changes. */
    fun refreshInputMode() {
        UiKeys.configureTextInput(searchField)
    }

    /** Called every time the screen becomes visible. */
    fun refresh() {
        refreshGreeting()
        val ctx = context
        Thread {
            val result = runCatching {
                val db = DataStore.get(ctx)
                val history = db.history(4)
                val today = Calendar.getInstance(TimeZone.getDefault()).apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                history to db.blockedSince(today)
            }.getOrElse { emptyList<com.minimal.browser.HistoryEntry>() to 0 }
            val (history, blockedToday) = result
            post {
                recentsBox.removeAllViews()
                emptyLabel.visibility = if (history.isEmpty()) VISIBLE else GONE
                for (entry in history) recentsBox.addView(recentRow(entry.url, entry.title))
                footer.text = ctx.resources.getQuantityString(
                    R.plurals.blocked_today_fmt, blockedToday, blockedToday
                )
            }
        }.start()
    }

    private fun recentRow(url: String, title: String): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = context.roundRect(12)
            setPadding(context.dp(14), context.dp(11), context.dp(14), context.dp(11))
            isClickable = true
            isFocusable = true
        }
        val host = UrlBar.hostOf(url)
        val letter = (host.trimStart('w', 'W').trimStart('.').firstOrNull() ?: '•').uppercaseChar().toString()

        row.addView(TextView(context).apply {
            text = letter
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = context.roundRect(9, 0xFF2A2A2A.toInt(), 0xFF3A3A3A.toInt())
        }, LinearLayout.LayoutParams(context.dp(34), context.dp(34)).apply {
            rightMargin = context.dp(12)
        })

        val textCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(TextView(context).apply {
            text = title.ifEmpty { url }
            setTextColor(Ink.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTypeface(typeface, Typeface.BOLD)
            isSingleLine = true
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        textCol.addView(TextView(context).apply {
            text = host
            setTextColor(Ink.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            isSingleLine = true
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            weight = 1f
        })

        row.addView(TextView(context).apply {
            text = "›"
            setTextColor(Ink.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })

        row.setOnClickListener { onOpenUrl?.invoke(url) }
        val holder = FrameLayout(context)
        holder.addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        holder.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = context.dp(4) }
        return holder
    }

    private fun refreshGreeting() {
        val now = Date()
        val cal = Calendar.getInstance(TimeZone.getDefault())
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        greeting.text = when (hour) {
            in 5..11 -> "Good morning 👋"
            in 12..16 -> "Good afternoon 👋"
            in 17..21 -> "Good evening 👋"
            else -> "Good night 👋"
        }
        val dateFmt = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
        val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        val tzName = TimeZone.getDefault().displayName ?: ""
        dateLabel.text = "${dateFmt.format(now)} · ${timeFmt.format(now)} · $tzName"
    }
}
