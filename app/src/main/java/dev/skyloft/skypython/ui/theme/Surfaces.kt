package dev.skyloft.skypython.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Surfaces that stand off the window: cards, chips, avatars, outlined floating layers. One
// recipe everywhere — a step-up fill ([SlatePanel]) and a 1dp [SlateLine] edge, no shadows —
// so grouped content reads as a unit without getting loud.

val CardShape = RoundedCornerShape(12.dp)

/** Top corners of bottom sheets (Material's default sheet shape, at our radius). */
val SheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

/**
 * A raised group: [SlatePanel] with a hairline border and 12dp corners. Content is laid out in a
 * column with [contentPadding]; [onClick] makes the whole card a button.
 */
@Composable
fun SkyCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = SlatePanel,
    border: Color = SlateLine,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val body: @Composable () -> Unit = { Column(Modifier.padding(contentPadding), content = content) }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = CardShape,
            color = color,
            contentColor = TextBright,
            border = BorderStroke(1.dp, border),
            content = body,
        )
    } else {
        Surface(
            modifier = modifier,
            shape = CardShape,
            color = color,
            contentColor = TextBright,
            border = BorderStroke(1.dp, border),
            content = body,
        )
    }
}

enum class ChipTone { Accent, Success, Muted }

/** A small pill: a status ("Installed"), a badge ("Prebuilt for Android") or a version. */
@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    tone: ChipTone = ChipTone.Muted,
    icon: ImageVector? = null,
    mono: Boolean = false,
) {
    val (fill, edge, ink) = when (tone) {
        ChipTone.Accent -> Triple(OrangeTint, Orange.copy(alpha = 0.45f), OrangeSoft)
        ChipTone.Success -> Triple(SuccessGreen.copy(alpha = 0.12f), SuccessGreen.copy(alpha = 0.40f), SuccessGreen)
        ChipTone.Muted -> Triple(SlateRaised, SlateLine, MutedText)
    }
    val shape = RoundedCornerShape(50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .background(fill, shape)
            .border(1.dp, edge, shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(14.dp))
        Text(
            text,
            color = ink,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = if (mono) CodeFont else InterFamily,
            maxLines = 1,
        )
    }
}

/** Calm tints for [LetterAvatar], picked by name so a package keeps its colour. */
private val avatarTints = listOf(
    Color(0xFFF5A962), // orange
    Color(0xFF7FB4F0), // blue
    Color(0xFF8FD19A), // green
    Color(0xFFC3A6F0), // violet
    Color(0xFFE8C46A), // amber
    Color(0xFF6FCFC4), // teal
)

private fun tintFor(key: String): Color = avatarTints[Math.floorMod(key.lowercase().hashCode(), avatarTints.size)]

/** [icon] on a soft rounded square in a calm colour picked by [key] (sample cards). */
@Composable
fun TintedIcon(icon: ImageVector, key: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val tint = tintFor(key)
    val shape = RoundedCornerShape(12.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .background(tint.copy(alpha = 0.14f), shape)
            .border(1.dp, tint.copy(alpha = 0.30f), shape),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** A round avatar: the first letter of [name] on a soft tint of its colour. */
@Composable
fun LetterAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val tint = tintFor(name)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .background(tint.copy(alpha = 0.14f), CircleShape)
            .border(1.dp, tint.copy(alpha = 0.30f), CircleShape),
    ) {
        Text(
            name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
            color = tint,
            fontFamily = CodeFont,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}

/** The hairline edge of a floating layer (dialog, sheet) so it doesn't melt into the scrim. */
fun Modifier.floatingOutline(shape: Shape): Modifier = border(1.dp, SlateLine, shape)

/** [AlertDialog] with the app's outlined, opaque surface. */
@Composable
fun SkyAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.extraLarge
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.floatingOutline(shape),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = SlatePanel,
    )
}

/** Text fields: a visible outline at rest, the orange edge when focused, a slightly raised fill. */
@Composable
fun skyFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = SlateOutline,
    focusedBorderColor = OrangeSoft,
    unfocusedContainerColor = SlateBar,
    focusedContainerColor = SlateBar,
    disabledContainerColor = SlateBar,
    errorContainerColor = SlateBar,
    focusedLabelColor = OrangeSoft,
    cursorColor = Orange,
)

/**
 * A card that holds a list of rows (settings, links): no inner padding, so each row keeps its own
 * and its press ripple fills the card's width. Separate rows with [CardDivider].
 */
@Composable
fun CardGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    SkyCard(modifier.fillMaxWidth().padding(horizontal = 16.dp), contentPadding = 0.dp, content = content)
}

/** A hairline between rows of a [CardGroup], inset past the leading icon. */
@Composable
fun CardDivider(inset: Dp = 72.dp) {
    HorizontalDivider(Modifier.padding(start = inset), thickness = 1.dp, color = SlateLine)
}
