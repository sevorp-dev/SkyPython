package dev.skyloft.skypython.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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

/** Round action button: solid [ElectricBlue], white icon, a normal Material shadow. */
@Composable
fun BrandFab(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    iconSize: Dp = 24.dp,
    elevation: Dp = 3.dp,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = ElectricBlue,
        contentColor = Color.White,
        shadowElevation = elevation,
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(iconSize))
        }
    }
}

/** The Run button: a round [BrandFab] with a white ▶. */
@Composable
fun RunButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 60.dp,
    iconSize: Dp = 32.dp,
    elevation: Dp = 3.dp,
) {
    BrandFab(onClick, Icons.Filled.PlayArrow, stringResource(R.string.action_run), modifier, size, iconSize, elevation)
}

/** Filled buttons: solid [ElectricBlue] with white text (5.2:1); disabled stays visible. */
@Composable
fun brandButtonColors() = ButtonDefaults.buttonColors(
    containerColor = ElectricBlue,
    contentColor = Color.White,
    disabledContainerColor = Color(0xFF222B42),
    disabledContentColor = MutedText,
)

/** Icon on a flat tonal rounded square (list leading icons, drawer items). */
@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 36.dp, tint: Color = SkyBlue) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .background(tint.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.56f))
    }
}

/** Section heading of a list: small, medium-weight, in the accent's text blue. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
        color = SkyBlue,
        modifier = modifier,
    )
}

/** Switches: white thumb on the accent track when on, a visible outlined navy track when off. */
@Composable
fun brandSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = ElectricBlue,
    checkedBorderColor = ElectricBlue,
    uncheckedThumbColor = MutedText,
    uncheckedTrackColor = Color(0xFF222B42),
    uncheckedBorderColor = Color(0xFF5E6985),
)
