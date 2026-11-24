package moe.matsuri.nb4a.ui

import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar
import io.nekohasekai.sagernet.ui.MainActivity

/**
 * Compatibility hook for the capsule toolbar. The toolbar background itself is drawn by
 * ConfigurationFragment's capsule drawable; this helper keeps the action area unclipped so that
 * the capsule and its icons remain visible at adapted icon sizes.
 */
object MenuScrimHelper {
    @JvmStatic
    fun hookActionMenuView(toolbar: Toolbar, actionMenuView: ActionMenuView, activity: MainActivity) {
        actionMenuView.clipChildren = false
        actionMenuView.clipToPadding = false
        actionMenuView.children().forEach { child ->
            child.clipToOutline = false
        }
        toolbar.invalidate()
        activity.window.decorView.invalidate()
    }

    private fun ActionMenuView.children(): Sequence<android.view.View> =
        (0 until childCount).asSequence().map(::getChildAt)
}
