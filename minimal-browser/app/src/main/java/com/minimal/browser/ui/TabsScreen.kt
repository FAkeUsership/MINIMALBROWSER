package com.minimal.browser.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.minimal.browser.R
import com.minimal.browser.Tab

/**
 * `#s-tabs` — "Open tabs" grid. Three columns of cards, each with a real
 * thumbnail (captured from the active page), the favicon letter, title and host, an ✕ to
 * close, plus the dashed "New tab" tile.
 */
class TabsScreen(context: Context) : LinearLayout(context) {

    var onSelect: ((Tab) -> Unit)? = null
    var onClose: ((Tab) -> Unit)? = null
    var onNewTab: (() -> Unit)? = null

    private val subtitle: TextView
    private val list: RecyclerView
    private val adapter = TabsAdapter()

    init {
        orientation = VERTICAL
        setBackgroundColor(Ink.SHELL)
        setPadding(context.dp(24), context.dp(24), context.dp(24), context.dp(24))

        addView(TextView(context).apply {
            text = "Open tabs"
            setTextColor(Ink.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTypeface(typeface, Typeface.BOLD)
        })

        subtitle = TextView(context).apply {
            setTextColor(Ink.MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }
        addView(subtitle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(6)
            bottomMargin = context.dp(18)
        })

        list = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 3)
            adapter = this@TabsScreen.adapter
            clipToPadding = false
            addItemDecoration(Spacing(context.dp(16)))
        }
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0).apply { weight = 1f })
    }

    fun refresh(tabs: List<Tab>, activeId: String?) {
        val count = tabs.size
        subtitle.text = "$count ${if (count == 1) "tab" else "tabs"} · tap to switch, ✕ to close"
        adapter.submit(
            tabs.mapIndexed { i, t ->
                Card(
                    id = t.id,
                    title = t.displayTitle,
                    host = t.host.ifEmpty { "new tab" },
                    letter = t.faviconLetter,
                    thumb = t.thumbnail,
                    gradient = i % 4,
                    chip = when (i % 4) {
                        0 -> context.color(R.color.f1)
                        1 -> context.color(R.color.f2)
                        2 -> context.color(R.color.f3)
                        else -> context.color(R.color.f4)
                    },
                    private = t.private,
                    active = t.id == activeId
                )
            },
            tabs.firstOrNull { it.id == activeId }?.let { a -> tabs.indexOfFirst { it.id == a.id } } ?: -1,
            tabs
        )
    }

    private data class Card(
        val id: String,
        val title: String,
        val host: String,
        val letter: String,
        val thumb: Bitmap?,
        val gradient: Int,
        val chip: Int,
        val private: Boolean,
        val active: Boolean
    )

    private inner class TabsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var cards: List<Card> = emptyList()
        private var source: List<Tab> = emptyList()

        fun submit(newCards: List<Card>, @Suppress("UNUSED_PARAMETER") activeIndex: Int, tabs: List<Tab>) {
            cards = newCards
            source = tabs
            notifyDataSetChanged()
        }

        override fun getItemCount() = cards.size + 1

        override fun getItemViewType(position: Int) = if (position == cards.size) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 1) NewTabHolder(buildNewTabTile()) else CardHolder(buildCard())
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is NewTabHolder) {
                holder.itemView.setOnClickListener { onNewTab?.invoke() }
                return
            }
            val card = cards[position]
            (holder as CardHolder).bind(card, source.firstOrNull { it.id == card.id })
        }

        /* ---------------- card ---------------- */

        private fun buildCard(): FrameLayout {
            val card = FrameLayout(context).apply {
                background = context.getDrawable(R.drawable.bg_card)
                clipToOutline = true
                isClickable = true
                isFocusable = true
            }

            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            card.addView(column, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ))

            val thumb = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Ink.PANEL2)
                tag = "thumb"
            }
            column.addView(thumb, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, context.dp(110)
            ))

            val meta = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(12), context.dp(11), context.dp(12), context.dp(11))
            }
            val chip = TextView(context).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                tag = "chip"
            }
            meta.addView(chip, LinearLayout.LayoutParams(context.dp(24), context.dp(24)).apply {
                rightMargin = context.dp(9)
            })

            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(context).apply {
                setTextColor(Ink.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTypeface(typeface, Typeface.BOLD)
                isSingleLine = true
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                tag = "title"
            }
            val host = TextView(context).apply {
                setTextColor(Ink.MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                isSingleLine = true
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                tag = "host"
            }
            texts.addView(title)
            texts.addView(host)
            meta.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            })
            column.addView(meta)

            val close = TextView(context).apply {
                text = "✕"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                background = context.getDrawable(R.drawable.bg_x)
                tag = "close"
                isClickable = true
                isFocusable = true
            }
            card.addView(close, FrameLayout.LayoutParams(context.dp(24), context.dp(24)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = context.dp(8)
                rightMargin = context.dp(8)
            })
            return card
        }

        private inner class CardHolder(val root: FrameLayout) : RecyclerView.ViewHolder(root) {
            fun bind(card: Card, tab: Tab?) {
                val thumb = root.findViewWithTag<ImageView>("thumb")
                val chip = root.findViewWithTag<TextView>("chip")
                val title = root.findViewWithTag<TextView>("title")
                val host = root.findViewWithTag<TextView>("host")
                val close = root.findViewWithTag<TextView>("close")

                if (card.thumb != null) {
                    thumb.setImageBitmap(card.thumb)
                    thumb.setBackgroundColor(Color.TRANSPARENT)
                } else {
                    thumb.setImageDrawable(null)
                    thumb.setBackgroundColor(Color.TRANSPARENT)
                    thumb.setBackgroundResource(
                        when (card.gradient) {
                            0 -> R.drawable.grad_t1
                            1 -> R.drawable.grad_t2
                            2 -> R.drawable.grad_t3
                            else -> R.drawable.grad_t4
                        }
                    )
                }
                chip.text = if (card.private) "🕶" else card.letter
                chip.background = context.roundRect(7, card.chip)
                chip.setTextColor(if (card.chip == context.color(R.color.f4)) Color.BLACK else Color.WHITE)
                title.text = card.title
                host.text = if (card.private) "private tab" else card.host

                root.setOnClickListener { tab?.let { onSelect?.invoke(it) } }
                close.setOnClickListener { tab?.let { onClose?.invoke(it) } }
            }
        }

        /* ---------------- new tab tile ---------------- */

        private fun buildNewTabTile(): LinearLayout {
            val tile = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = context.getDrawable(R.drawable.bg_newtab)
                isClickable = true
                isFocusable = true
            }
            tile.addView(TextView(context).apply {
                text = "+"
                setTextColor(Ink.TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                gravity = Gravity.CENTER
                background = context.getDrawable(R.drawable.bg_plus_circle)
            }, LinearLayout.LayoutParams(context.dp(44), context.dp(44)))

            tile.addView(TextView(context).apply {
                text = "New tab"
                setTextColor(Ink.MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = context.dp(10) })
            return tile
        }

        private inner class NewTabHolder(root: View) : RecyclerView.ViewHolder(root)
    }

    private class Spacing(private val gap: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            outRect.set(gap / 2, gap / 2, gap / 2, gap / 2)
        }
    }
}
