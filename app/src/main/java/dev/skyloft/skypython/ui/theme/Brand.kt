package dev.skyloft.skypython.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import dev.skyloft.skypython.R

/** Text painted with the brand cyan → blue → violet gradient, like the banner's "SkyPython". */
@Composable
fun BrandText(
    text: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Bold,
    brush: Brush = BrandTextBrush,
) {
    Text(
        text,
        modifier = modifier,
        maxLines = 1,
        style = TextStyle(brush = brush, fontSize = fontSize, fontWeight = fontWeight),
    )
}

/**
 * Round glowing button: blue → violet gradient with a white icon, as on the banner's phone.
 * [onLongClick], if set, is offered on a long press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GradientFab(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    iconSize: Dp = 26.dp,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .shadow(10.dp, CircleShape, ambientColor = ElectricBlue, spotColor = Violet)
            .background(RunBrush, CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
            .then(
                if (onLongClick == null) Modifier.clickable(role = Role.Button, onClick = onClick)
                else Modifier.combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick),
            ),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

/** The Run button: a round [GradientFab] with a white ▶; [onLongClick] opens more ways to run. */
@Composable
fun RunButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    iconSize: Dp = 36.dp,
    onLongClick: (() -> Unit)? = null,
) {
    GradientFab(onClick, Icons.Filled.PlayArrow, stringResource(R.string.action_run), modifier, size, iconSize, onLongClick)
}

/** Filled buttons in electric blue with white text (4.5:1), like the banner's accents. */
@Composable
fun brandButtonColors() = ButtonDefaults.buttonColors(
    containerColor = ElectricBlue,
    contentColor = Color.White,
    disabledContainerColor = PanelNavy,
    disabledContentColor = MutedText,
)

/** Icon in a rounded square with a thin glowing outline, like the banner's feature icons. */
@Composable
fun OutlinedIconTile(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 36.dp, tint: Color = SkyBlue) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .background(Brush.linearGradient(listOf(Color(0x332F6BFF), Color(0x268B5CF6))), shape)
            .border(1.dp, Brush.linearGradient(listOf(SkyCyan.copy(alpha = 0.7f), SoftViolet.copy(alpha = 0.7f))), shape),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.58f))
    }
}

/** Section heading in the banner's style: small, letter-spaced, uppercase, sky cyan. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.6.sp,
        color = SkyCyan,
        modifier = modifier,
    )
}

/** Switches: white thumb on an electric-blue track when on, navy when off. */
@Composable
fun brandSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = ElectricBlue,
    checkedBorderColor = ElectricBlue,
    uncheckedThumbColor = MutedText,
    uncheckedTrackColor = PanelNavy,
    uncheckedBorderColor = Color(0xFF34417A),
)
