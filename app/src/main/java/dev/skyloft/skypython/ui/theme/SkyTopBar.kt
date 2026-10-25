package dev.skyloft.skypython.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import dev.skyloft.skypython.R

/** Colours shared by every top bar: the page's own [Slate] (a calm, continuous header), light icons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun skyTopBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = Slate,
    scrolledContainerColor = SlateBar,
    navigationIconContentColor = TextBright,
    titleContentColor = TextBright,
    actionIconContentColor = TextBright,
)

/** 1dp hairline under a bar or between sections. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = 1.dp, color = SlateLine)
}

/** The one top bar of every secondary screen: back arrow, title, a [Hairline] under it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkyTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    divider: Boolean = true,
) {
    Column {
        TopAppBar(
            title = {
                Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
            actions = actions,
            colors = skyTopBarColors(),
        )
        // Screens with their own strip under the bar (tabs, breadcrumbs) draw the line below it.
        if (divider) Hairline()
    }
}
