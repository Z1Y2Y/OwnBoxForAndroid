package io.nekohasekai.sagernet.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/** A small platform-themed separator for preference rows, independent of Telegram UI code. */
class PreferenceDividerDecoration(context: Context) : RecyclerView.ItemDecoration() {
    private val divider: Drawable?

    init {
        val attributes = context.obtainStyledAttributes(intArrayOf(android.R.attr.listDivider))
        divider = attributes.getDrawable(0)
        attributes.recycle()
    }

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        outRect.bottom = divider?.intrinsicHeight?.coerceAtLeast(1) ?: 0
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val itemDivider = divider ?: return
        val left = parent.paddingLeft
        val right = parent.width - parent.paddingRight
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            val params = child.layoutParams as RecyclerView.LayoutParams
            val top = child.bottom + params.bottomMargin
            itemDivider.setBounds(left, top, right, top + itemDivider.intrinsicHeight.coerceAtLeast(1))
            itemDivider.draw(canvas)
        }
    }
}
