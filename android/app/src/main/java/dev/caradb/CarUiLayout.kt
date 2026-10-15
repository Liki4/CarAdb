package dev.caradb

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

internal object CarUiLayout {
    fun apply(activity: Activity, imeVisible: Boolean = false) {
        val configuration = activity.resources.configuration
        val wideContent = configuration.screenWidthDp >= 840
        val minimumRailHeightDp = activity.resources.getDimension(R.dimen.ui_rail_min_height) /
            activity.resources.displayMetrics.density
        val sideNavigation = wideContent && configuration.screenHeightDp >= minimumRailHeightDp && !imeVisible
        val mode = (if (wideContent) 1 else 0) or (if (sideNavigation) 2 else 0)
        val shell = activity.findViewById<LinearLayout>(R.id.app_shell)
        if (shell.tag == mode) return
        shell.tag = mode
        fun dimension(id: Int) = activity.resources.getDimensionPixelSize(id)
        val gap = dimension(R.dimen.ui_gap)
        val navigation = activity.findViewById<LinearLayout>(R.id.navigation)
        val main = activity.findViewById<View>(R.id.main_content)
        val items = activity.findViewById<LinearLayout>(R.id.nav_items)
        val caption = activity.findViewById<View>(R.id.nav_caption)
        val body = activity.findViewById<LinearLayout>(R.id.connection_body)
        val copy = activity.findViewById<View>(R.id.connection_copy)
        val actions = activity.findViewById<View>(R.id.connection_actions)
        val footer = activity.findViewById<LinearLayout>(R.id.config_footer)
        val storage = activity.findViewById<View>(R.id.storage_status)
        val save = activity.findViewById<View>(R.id.save)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        // Short wide windows keep two-column content but use a single bottom navigation row.
        shell.orientation = if (sideNavigation) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        shell.removeView(navigation)
        shell.addView(navigation, if (sideNavigation) 0 else shell.childCount)
        main.layoutParams = if (sideNavigation) LinearLayout.LayoutParams(0, match, 1f) else LinearLayout.LayoutParams(match, 0, 1f)
        navigation.layoutParams = LinearLayout.LayoutParams(if (sideNavigation) dimension(R.dimen.ui_nav_width) else match, if (sideNavigation) match else wrap)
        val navPadding = dimension(R.dimen.ui_nav_padding)
        val verticalPadding = if (sideNavigation) navPadding else gap / 2
        navigation.setPadding(navPadding, verticalPadding, navPadding, verticalPadding)
        activity.findViewById<View>(R.id.nav_brand).visibility = if (sideNavigation) View.VISIBLE else View.GONE
        caption.visibility = if (sideNavigation) View.VISIBLE else View.GONE
        caption.layoutParams = LinearLayout.LayoutParams(match, wrap)
        items.orientation = if (sideNavigation) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        items.gravity = Gravity.CENTER
        items.layoutParams = if (sideNavigation) LinearLayout.LayoutParams(match, 0, 1f) else LinearLayout.LayoutParams(match, wrap)
        val navIcons = mapOf(
            R.id.nav_connection to R.drawable.ui_nav_connection,
            R.id.nav_config to R.drawable.ui_nav_config,
            R.id.nav_settings to R.drawable.ui_nav_settings,
        )
        for ((id, drawable) in navIcons) {
            activity.findViewById<Button>(id).apply {
                layoutParams = if (sideNavigation) {
                    LinearLayout.LayoutParams(match, wrap).apply { topMargin = gap / 2; bottomMargin = gap / 2 }
                } else {
                    LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = gap / 4; marginEnd = gap / 4 }
                }
                val height = dimension(if (sideNavigation) R.dimen.ui_nav_item_height else R.dimen.ui_control_height)
                minHeight = height
                minimumHeight = height
                val icon = activity.getDrawable(drawable)?.mutate()
                val size = dimension(R.dimen.ui_nav_icon)
                icon?.setBounds(0, 0, size, size)
                if (wideContent && !sideNavigation) setCompoundDrawablesRelative(icon, null, null, null)
                else setCompoundDrawablesRelative(null, icon, null, null)
            }
        }

        body.orientation = if (wideContent) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        copy.layoutParams = if (wideContent) LinearLayout.LayoutParams(0, match, 1f) else LinearLayout.LayoutParams(match, 0, 1f)
        actions.layoutParams = LinearLayout.LayoutParams(if (wideContent) dimension(R.dimen.ui_action_width) else match, wrap).apply {
            gravity = Gravity.CENTER_VERTICAL
            if (wideContent) marginStart = dimension(R.dimen.ui_body_gap) else topMargin = gap
        }
        activity.findViewById<View>(R.id.action_caption).visibility = if (wideContent && !sideNavigation) View.GONE else View.VISIBLE
        for (id in listOf(R.id.connect, R.id.disconnect)) {
            activity.findViewById<Button>(id).apply {
                val height = dimension(if (sideNavigation) R.dimen.ui_action_height else R.dimen.ui_compact_action_height)
                minHeight = height
                minimumHeight = height
                val icon = activity.getDrawable(R.drawable.ui_power)?.mutate()
                val size = dimension(if (sideNavigation) R.dimen.ui_action_icon else R.dimen.ui_compact_action_icon)
                icon?.setBounds(0, 0, size, size)
                if (sideNavigation) setCompoundDrawablesRelative(null, icon, null, null)
                else setCompoundDrawablesRelative(icon, null, null, null)
            }
        }

        footer.orientation = if (wideContent) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        storage.layoutParams = if (wideContent) LinearLayout.LayoutParams(0, wrap, 1f) else LinearLayout.LayoutParams(match, wrap)
        save.layoutParams = LinearLayout.LayoutParams(if (wideContent) wrap else match, wrap).apply {
            if (wideContent) marginStart = gap else topMargin = gap / 2
        }
    }
}
