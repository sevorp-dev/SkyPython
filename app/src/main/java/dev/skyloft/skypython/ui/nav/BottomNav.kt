package dev.skyloft.skypython.ui.nav

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import dev.skyloft.skypython.R
import dev.skyloft.skypython.ui.theme.Hairline
import dev.skyloft.skypython.ui.theme.MutedText
import dev.skyloft.skypython.ui.theme.OrangeSoft
import dev.skyloft.skypython.ui.theme.OrangeTint
import dev.skyloft.skypython.ui.theme.SlateBar

/** The five top-level screens of the bottom navigation bar, in bar order. [route] is the NavHost route. */
enum class TopLevelDestination(val route: String, @param:StringRes val labelRes: Int, val icon: ImageVector) {
    Editor("editor", R.string.nav_editor, Icons.Outlined.Code),
    Terminal("terminal", R.string.nav_terminal, Icons.Outlined.Terminal),
    Packages("pip", R.string.nav_packages, Icons.Outlined.Inventory2),
    Projects("files", R.string.nav_projects, Icons.Outlined.Folder),
    Settings("settings", R.string.nav_settings, Icons.Outlined.Settings);

    /** Test tag of this destination's bar item. */
    val tag: String get() = "bottom_nav_$route"

    companion object {
        /** The top-level destination shown for [route], or null for a secondary screen. */
        fun fromRoute(route: String?): TopLevelDestination? = entries.firstOrNull { it.route == route }
    }
}

/** Pure rules for when and how the bottom bar shows (JVM-tested). */
object BottomNavPolicy {
    /** Below this window height (dp) the bar drops its labels: landscape phones, split screen. */
    const val LABELS_MIN_HEIGHT_DP = 560

    /** Bar height without the system navigation inset. */
    const val HEIGHT_DP = 60
    const val COMPACT_HEIGHT_DP = 48

    /**
     * The bar shows on the five top-level screens only, and gets out of the way while the soft
     * keyboard is up (typing needs the rows above it), while the editor's ☰ drawer is open (the
     * drawer covers the whole screen) and while the debugger panel sits under the code.
     */
    fun isVisible(
        enabled: Boolean,
        route: String?,
        imeVisible: Boolean,
        drawerOpen: Boolean = false,
        debugPanelOpen: Boolean = false,
    ): Boolean {
        if (!enabled || imeVisible || drawerOpen) return false
        val destination = TopLevelDestination.fromRoute(route) ?: return false
        return !(destination == TopLevelDestination.Editor && debugPanelOpen)
    }

    /** Icons with labels when there is room; icons only on short windows. */
    fun showLabels(windowHeightDp: Int): Boolean = windowHeightDp >= LABELS_MIN_HEIGHT_DP
}

/**
 * The app's bottom navigation bar: a slate strip with a hairline on top; the current item's
 * icon and label are orange (on a soft orange pill), the others muted. Pads itself for the
 * system navigation bar, so the screens above it must not.
 */
@Composable
fun SkyBottomBar(
    current: TopLevelDestination?,
    showLabels: Boolean,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(SlateBar)
            .testTag(BOTTOM_BAR_TAG),
    ) {
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .height((if (showLabels) BottomNavPolicy.HEIGHT_DP else BottomNavPolicy.COMPACT_HEIGHT_DP).dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TopLevelDestination.entries.forEach { destination ->
                BottomItem(
                    destination = destination,
                    selected = destination == current,
                    showLabel = showLabels,
                    onClick = { onSelect(destination) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

const val BOTTOM_BAR_TAG = "bottom_nav_bar"

@Composable
private fun BottomItem(
    destination: TopLevelDestination,
    selected: Boolean,
    showLabel: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (selected) OrangeSoft else MutedText
    val label = stringResource(destination.labelRes)
    Column(
        modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 36.dp),
            )
            .testTag(destination.tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(width = 52.dp, height = 28.dp)
                .background(if (selected) OrangeTint else Color.Transparent, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // The label says it already; without a label the icon carries the name.
            Icon(destination.icon, contentDescription = if (showLabel) null else label, tint = color, modifier = Modifier.size(22.dp))
        }
        if (showLabel) {
            Spacer(Modifier.height(3.dp))
            Text(
                label,
                color = color,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}
