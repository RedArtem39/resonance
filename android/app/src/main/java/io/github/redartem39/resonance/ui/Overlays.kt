package io.github.redartem39.resonance.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.redartem39.resonance.AppVm
import io.github.redartem39.resonance.DialogResult

@Composable
fun ToastHost(vm: AppVm, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    var last by remember { mutableStateOf("") }
    vm.toastMsg?.let { last = it }
    AnimatedVisibility(
        vm.toastMsg != null, modifier,
        enter = fadeIn(tween(300)) + slideInVertically(springy()) { it / 2 },
        exit = fadeOut(tween(250)) + slideOutVertically(tween(250)) { it / 3 },
    ) {
        Box(Modifier.padding(horizontal = 20.dp).clip(RoundedCornerShape(18.dp)).background(pal.onSurface).padding(horizontal = 22.dp, vertical = 14.dp)) {
            T(last, pal.surface, 14f, 600, maxLines = 8, lineHeight = 20f)
        }
    }
}

/** The long-press menu: a card that rises from the bottom (the touch counterpart of the desktop right-click menu). */
@Composable
fun MenuSheet(vm: AppVm) {
    val m = vm.menu ?: return
    val pal = LocalPal.current
    Dialog({ vm.menu = null }, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(MutableInteractionSource(), null) { vm.menu = null }, contentAlignment = Alignment.BottomCenter) {
            var shown by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { shown = true }
            AnimatedVisibility(shown, enter = fadeIn(tween(160)) + slideInVertically(springy()) { it / 3 }) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 72.dp).clip(RoundedCornerShape(28.dp)).background(pal.surfaceHighest)
                        .clickable(MutableInteractionSource(), null) {}.heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.8f).dp)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(8.dp),
                ) {
                    T(m.title, pal.onSurfaceVar, 13f, 650, Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                    m.items.forEach { it ->
                        if (it.sep) Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp).fillMaxWidth().height(1.dp).background(pal.onSurface.copy(alpha = 0.12f)))
                        else Morph(
                            Modifier.fillMaxWidth().height(48.dp), Color.Transparent, 14.dp, 20.dp, pressScale = 0.98f, enabled = !it.disabled, align = Alignment.CenterStart,
                            onClick = { vm.menu = null; it.run() },
                        ) {
                            T(it.label, if (it.danger) pal.danger else pal.onSurface, 15f, 600, Modifier.padding(horizontal = 14.dp).let { m -> if (it.disabled) m else m })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DialogHost(vm: AppVm) {
    val d = vm.dialog ?: return
    val pal = LocalPal.current
    var text by remember(d) { mutableStateOf(d.input ?: "") }
    var pick by remember(d) { mutableIntStateOf(-1) }
    val focus = remember { FocusRequester() }
    Dialog({ vm.closeDialog(DialogResult(false)) }, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(24.dp).fillMaxWidth().heightIn(max = 560.dp).clip(RoundedCornerShape(30.dp)).background(pal.surfaceHigh).padding(start = 26.dp, end = 26.dp, top = 26.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            T(d.title, pal.onSurface, 22f, 750, spacing = -0.4f, maxLines = 2)
            T(d.text, pal.onSurfaceVar, 14.5f, 500, maxLines = 8, lineHeight = 21f)
            if (d.input != null) {
                Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(pal.surfaceHighest).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
                    BasicTextField(
                        text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                        textStyle = TextStyle(color = pal.onSurface, fontSize = 15.sp), cursorBrush = Brush.linearGradient(listOf(pal.primary, pal.primary)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) vm.closeDialog(DialogResult(true, text.trim())) }),
                    )
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
            if (d.list != null) {
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(d.list) { i, it ->
                        val (top, bottom) = cornersFor(i, d.list.size, 20.dp)
                        val on = pick == i
                        Morph(Modifier.fillMaxWidth(), if (on) pal.primaryContainer else pal.surfaceHighest, if (on) 22.dp else 18.dp, 18.dp,
                            topBase = if (on) 22.dp else top, bottomBase = if (on) 22.dp else bottom, pressScale = 0.98f, align = Alignment.CenterStart, onClick = { pick = i }) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                T(it.label, if (on) pal.onPrimaryContainer else pal.onSurface, 14f, 650)
                                T(it.sub, if (on) pal.onPrimaryContainer.copy(alpha = 0.75f) else pal.onSurfaceVar, 12f, 500)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Morph(Modifier.height(46.dp), pal.surfaceHighest, 23.dp, 14.dp, onClick = { vm.closeDialog(DialogResult(false)) }) { T("Отмена", pal.onSurface, 14.5f, 700, Modifier.padding(horizontal = 24.dp)) }
                val enabled = (d.input == null || text.isNotBlank()) && (d.list == null || pick >= 0)
                Morph(
                    Modifier.height(46.dp).let { m -> if (enabled) m else m.alpha0(0.4f) }, if (d.danger) pal.dangerBg else pal.primary, 23.dp, 14.dp, enabled = enabled,
                    onClick = { vm.closeDialog(DialogResult(true, text.trim(), pick)) },
                ) { T(d.ok, if (d.danger) Color.White else pal.onPrimary, 14.5f, 700, Modifier.padding(horizontal = 24.dp)) }
            }
        }
    }
}

private fun Modifier.alpha0(a: Float): Modifier = this.alpha(a)
