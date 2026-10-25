package dev.skyloft.skypython.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours shared by every top bar: [BarNavy], white icons and title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun skyTopBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = BarNavy,
    navigationIconContentColor = Color.White,
    titleContentColor = Color.White,
    actionIconContentColor = Color.White,
)

/** A faint cyan → violet hairline under a top bar, the banner's glow in one pixel. */
@Composable
fun GlowLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).alpha(0.55f).background(GlowLineBrush))
}

/** The one top bar of every secondary screen: [BarNavy], white back arrow, bold title, [GlowLine] under it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkyTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    glow: Boolean = true,
) {
    Column {
        TopAppBar(
            title = {
                Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = actions,
            colors = skyTopBarColors(),
        )
        // Screens with their own strip under the bar (tabs, breadcrumbs) draw the line below it.
        if (glow) GlowLine()
    }
}
