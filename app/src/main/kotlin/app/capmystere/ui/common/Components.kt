package app.capmystere.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.capmystere.theme.Cap

/** Titre dans la police de titrage du thème (respecte la casse voulue par le thème). */
@Composable
fun DisplayText(
    text: String, modifier: Modifier = Modifier, style: TextStyle = Cap.type.display,
    color: Color = Cap.colors.text, textAlign: TextAlign? = null, maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        if (Cap.type.displayUppercase) text.uppercase() else text,
        modifier, color = color, style = style, textAlign = textAlign, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

/** Petite étiquette en capitales espacées. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Cap.colors.textMuted) {
    Text(text.uppercase(), modifier, color = color, style = Cap.type.label)
}

@Composable
fun BodyText(text: String, modifier: Modifier = Modifier, color: Color = Cap.colors.text, style: TextStyle = Cap.type.body, textAlign: TextAlign? = null) {
    Text(text, modifier, color = color, style = style, textAlign = textAlign)
}

@Composable
fun CapCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    highlighted: Boolean = false,
    padding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Cap.colors
    val shape = cardShape()
    val border = Cap.spec.shape.border
    Column(
        modifier
            .clip(shape)
            .background(if (highlighted) colors.surfaceHigh else colors.surface)
            .border(BorderStroke(border.dp, if (highlighted) colors.primary else colors.outline), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

enum class ButtonKind { PRIMARY, GHOST, DANGER }

@Composable
fun CapButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PRIMARY,
    enabled: Boolean = true,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = Cap.colors
    val shape = buttonShape()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
    val (bg, fg, stroke) = when (kind) {
        ButtonKind.PRIMARY -> Triple(colors.primary, colors.onPrimary, Color.Transparent)
        ButtonKind.GHOST -> Triple(Color.Transparent, colors.text, colors.outline)
        ButtonKind.DANGER -> Triple(Color.Transparent, colors.danger, colors.danger)
    }
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.4f }
            .clip(shape)
            .background(bg)
            .border(BorderStroke(Cap.spec.shape.border.dp, stroke), shape)
            .clickable(interaction, indication = androidx.compose.material3.ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 54.dp)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(10.dp)) }
        Text(text, color = fg, style = Cap.type.button, textAlign = TextAlign.Center)
    }
}

@Composable
fun CapChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Cap.colors
    val shape = buttonShape()
    Box(
        modifier
            .clip(shape)
            .background(if (selected) colors.primary else Color.Transparent)
            .border(BorderStroke(1.dp, if (selected) colors.primary else colors.outline), shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .defaultMinSize(minHeight = 44.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) colors.onPrimary else colors.text, style = Cap.type.body)
    }
}

@Composable
fun CapTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    textStyle: TextStyle = Cap.type.bodyLarge,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    placeholder: String? = null,
) {
    val colors = Cap.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label, style = Cap.type.body) },
        placeholder = placeholder?.let { { Text(it, style = textStyle, color = colors.textMuted) } },
        singleLine = singleLine,
        minLines = minLines,
        textStyle = textStyle,
        shape = buttonShape(),
        keyboardOptions = keyboardOptions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.text, unfocusedTextColor = colors.text,
            focusedBorderColor = colors.primary, unfocusedBorderColor = colors.outline,
            focusedLabelColor = colors.primary, unfocusedLabelColor = colors.textMuted,
            cursorColor = colors.primary,
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
        ),
    )
}

/** Barre de titre sobre : retour, étiquette, titre, actions. */
@Composable
fun CapTopBar(
    title: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconAction(Glyph.BACK, onBack, contentDescription = "Retour") else Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            if (overline != null) Label(overline)
            DisplayText(title, style = Cap.type.title, maxLines = 1)
        }
        actions()
    }
}

/** Trait de séparation discret. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Cap.colors.outline.copy(alpha = 0.6f)))
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color))
}
