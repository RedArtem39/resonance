@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package io.github.redartem39.resonance.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------- icons
private val iconPaths = HashMap<String, Path>()
private fun iconPath(name: String): Path = iconPaths.getOrPut(name) { PathParser().parsePathString(ICONS[name] ?: "").toPath() }

@Composable
fun Ico(name: String, tint: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    val p = remember(name) { iconPath(name) }
    Box(modifier.size(size).drawBehind {
        val s = this.size.minDimension / 24f
        scale(s, s, Offset.Zero) { drawPath(p, tint) }
    })
}

// ---------------------------------------------------------------- text
@Composable
fun T(
    text: String, color: Color, size: Float = 14f, weight: Int = 500, modifier: Modifier = Modifier,
    maxLines: Int = 1, spacing: Float = 0f, lineHeight: Float = 0f, align: androidx.compose.ui.text.style.TextAlign? = null,
) = Text(
    text, modifier, color = color, fontSize = size.sp, fontWeight = FontWeight(weight), maxLines = maxLines,
    overflow = TextOverflow.Ellipsis, letterSpacing = spacing.sp, lineHeight = if (lineHeight > 0) lineHeight.sp else androidx.compose.ui.unit.TextUnit.Unspecified, textAlign = align,
)

// ---------------------------------------------------------------- shape-morphing surfaces: corners and scale spring on press
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Morph(
    modifier: Modifier = Modifier,
    color: Color,
    base: Dp,
    pressed: Dp = base,
    topBase: Dp = base,
    bottomBase: Dp = base,
    pressScale: Float = 0.94f,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    align: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val top by animateDpAsState(if (down) pressed else topBase, springy(), label = "top")
    val bottom by animateDpAsState(if (down) pressed else bottomBase, springy(), label = "bottom")
    val sc by animateFloatAsState(if (down) pressScale else 1f, springy(), label = "sc")
    val clickMod = if (onClick != null || onLongClick != null)
        Modifier.combinedClickable(src, null, enabled = enabled, onClick = { onClick?.invoke() }, onLongClick = onLongClick) else Modifier
    Box(
        modifier.graphicsLayer { scaleX = sc; scaleY = sc }.clip(RoundedCornerShape(top, top, bottom, bottom)).background(color).then(clickMod),
        contentAlignment = align, content = content,
    )
}

/** Connected list rows: first/last get the big radius, the ones between a small one. */
fun cornersFor(i: Int, n: Int, big: Dp, small: Dp = 8.dp): Pair<Dp, Dp> = (if (i == 0) big else small) to (if (i == n - 1) big else small)

@Composable
fun Panel(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalPal.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(pal.surfaceMid).padding(horizontal = 24.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (title != null) T(title.uppercase(), pal.primary, 13f, 700, spacing = 0.8f)
        content()
    }
}

@Composable
fun SwitchM3(checked: Boolean, onChange: (Boolean) -> Unit) {
    val pal = LocalPal.current
    val src = remember { MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val track by animateColorAsState(if (checked) pal.primary else pal.surfaceHighest, tween(250), label = "track")
    val border by animateColorAsState(if (checked) pal.primary else pal.outline, tween(250), label = "border")
    val thumbSize by animateDpAsState(if (down) 28.dp else if (checked) 24.dp else 16.dp, springy(), label = "ts")
    val thumbColor by animateColorAsState(if (checked) pal.onPrimary else pal.outline, tween(250), label = "tc")
    val xOff by animateDpAsState(if (checked) (if (down) 20.dp else 24.dp) - 2.dp else 6.dp - 2.dp, springy(), label = "tx")
    Box(
        Modifier.size(52.dp, 32.dp).clip(RoundedCornerShape(16.dp)).background(track).border(2.dp, border, RoundedCornerShape(16.dp))
            .combinedClickable(src, null, onClick = { onChange(!checked) }),
    ) {
        Box(Modifier.padding(start = xOff).align(Alignment.CenterStart).size(thumbSize).clip(CircleShape).background(thumbColor))
    }
}

@Composable
fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            T(title, pal.onSurface, 15f, 600, maxLines = 3)
            T(desc, pal.onSurfaceVar, 12.5f, 500, maxLines = 6, lineHeight = 17f)
        }
        SwitchM3(checked, onChange)
    }
}

/** Segmented control: first/last button have a big outer radius, the selected one is fully rounded. */
@Composable
fun Seg(labels: List<String>, selected: Int, height: Dp = 44.dp, onSelect: (Int) -> Unit) {
    val pal = LocalPal.current
    val edge = height / 2
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            val start by animateDpAsState(if (on || i == 0) edge else 8.dp, springy(), label = "start")
            val end by animateDpAsState(if (on || i == labels.size - 1) edge else 8.dp, springy(), label = "end")
            val bg by animateColorAsState(if (on) pal.secondaryContainer else pal.surfaceHigh, tween(250), label = "bg")
            val fg by animateColorAsState(if (on) pal.onSecondaryContainer else pal.onSurfaceVar, tween(250), label = "fg")
            Box(
                Modifier.weight(1f).height(height).clip(RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end))
                    .background(bg).combinedClickable(onClick = { onSelect(i) }),
                contentAlignment = Alignment.Center,
            ) { T(l, fg, 14f, 650) }
        }
    }
}

/** Small pill button (".mini" in the desktop CSS). */
@Composable
fun Mini(label: String?, icon: String? = null, on: Boolean = false, enabled: Boolean = true, busy: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val pal = LocalPal.current
    val bg = if (busy) pal.primary else if (on) pal.secondaryContainer else pal.surfaceHighest
    val fg = if (busy) pal.onPrimary else if (on) pal.onSecondaryContainer else pal.onSurfaceVar
    Morph(
        modifier.height(36.dp).graphicsLayer { alpha = if (enabled) 1f else 0.5f }, color = bg, base = 12.dp, pressed = 18.dp, enabled = enabled, onClick = onClick,
    ) {
        Row(Modifier.padding(horizontal = if (label == null) 0.dp else 14.dp).then(if (label == null) Modifier.size(36.dp) else Modifier), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (label == null) Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { if (icon != null) Ico(icon, fg, 18.dp) }
            else { if (icon != null) Ico(icon, fg, 18.dp); T(label, fg, 13f, 650) }
        }
    }
}

/** Wide tonal button (".tonal"). */
@Composable
fun Tonal(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable RowScope.(Color) -> Unit) {
    val pal = LocalPal.current
    Morph(modifier.fillMaxWidth().height(52.dp), color = pal.secondaryContainer, base = 26.dp, pressed = 16.dp, pressScale = 0.96f, onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { content(pal.onSecondaryContainer) }
    }
}
