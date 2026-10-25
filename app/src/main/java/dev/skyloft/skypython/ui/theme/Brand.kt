package dev.skyloft.skypython.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import dev.skyloft.skypython.R

/** The app name. Solid by default; [brush] paints it (the About screen's soft gradient). */
@Composable
fun BrandText(
    text: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.SemiBold,
    color: Color = TextBright,
    brush: Brush? = null,
) {
    Text(
        text,
        modifier = modifier,
        maxLines = 1,
        style = if (brush != null) {
            TextStyle(brush = brush, fontSize = fontSize, fontWeight = fontWeight)
        } else {
            TextStyle(color = color, fontSize = fontSize, fontWeight = fontWeight)
        },
    )
}

/**
 * Round action button: solid [Orange], dark icon, a soft Material shadow.
 * [onLongClick], if set, is offered on a long press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrandFab(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    iconSize: Dp = 24.dp,
    elevation: Dp = 3.dp,
    onLongClick: (() -> Unit)? = null,
) {
    // Surface(onClick) has no long press: clicks are handled by the modifier instead.
    Surface(
        shape = CircleShape,
        color = Orange,
        contentColor = OnOrange,
        shadowElevation = elevation,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(iconSize))
        }
    }
}

/** The Run button: a round [BrandFab] with a dark ▶; [onLongClick] opens more ways to run. */
@Composable
fun RunButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 60.dp,
    iconSize: Dp = 32.dp,
    elevation: Dp = 3.dp,
    onLongClick: (() -> Unit)? = null,
) {
    BrandFab(onClick, Icons.Filled.PlayArrow, stringResource(R.string.action_run), modifier, size, iconSize, elevation, onLongClick)
}

/** Filled buttons: solid [Orange] with dark text (7.9:1); disabled stays visible. */
@Composable
fun brandButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Orange,
    contentColor = OnOrange,
    disabledContainerColor = SlateRaised,
    disabledContentColor = MutedText,
)

/**
 * Tonal buttons for actions repeated down a list (Install on each package): an orange wash with
 * orange text, so a screen of them stays calm and the one filled button still leads.
 */
@Composable
fun brandTonalButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color(0x2EF28C38),
    contentColor = OrangeSoft,
    disabledContainerColor = SlateRaised,
    disabledContentColor = MutedText,
)

/**
 * Icon on a quiet rounded square with a hairline border (list leading icons, settings rows).
 * Neutral by default so the orange accent stays rare; [accent] tints it for the selected row.
 */
@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 36.dp, accent: Boolean = false) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .background(if (accent) OrangeTint else SlateRaised, shape)
            .border(1.dp, if (accent) Orange.copy(alpha = 0.35f) else SlateLine, shape),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (accent) OrangeSoft else TextBright.copy(alpha = 0.86f),
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/** Section heading of a list: small caps-style, letter-spaced, muted (WORKSPACE, EDITOR …). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = MutedText,
        modifier = modifier,
    )
}

/** Trailing › of a row that leads somewhere. */
@Composable
fun Chevron(modifier: Modifier = Modifier) {
    Icon(
        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = null,
        tint = MutedText,
        modifier = modifier.size(20.dp),
    )
}

/** Switches: dark thumb on the orange track when on, a visible outlined dark track when off. */
@Composable
fun brandSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = OnOrange,
    checkedTrackColor = Orange,
    checkedBorderColor = Orange,
    checkedIconColor = Orange,
    uncheckedThumbColor = MutedText,
    uncheckedTrackColor = SlatePanel,
    uncheckedBorderColor = SlateOutline,
)
