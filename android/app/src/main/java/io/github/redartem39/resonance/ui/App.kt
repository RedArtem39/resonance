package io.github.redartem39.resonance.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import io.github.redartem39.resonance.AppVm
import io.github.redartem39.resonance.View
import io.github.redartem39.resonance.data.plural

@Composable
fun ResonanceRoot(vm: AppVm, pickFolder: () -> Unit, pickCookies: () -> Unit) {
    val hue by animateFloatAsState(vm.hue, tween(900, easing = SmoothEasing), label = "h")
    val chroma by animateFloatAsState(vm.chroma, tween(900, easing = SmoothEasing), label = "c")
    val pal = remember(hue, chroma, vm.darkTheme) { Pal(vm.darkTheme, hue, chroma) }
    val frame = remember { FrameState() }
    // The animation clock: full frame rate while something moves (playback, visualizer, equalizer), a slow tick otherwise so seeks still repaint.
    val busy = androidx.compose.runtime.rememberUpdatedState(vm.playing || vm.view == View.Eq || vm.vizMode != "off")
    LaunchedEffect(Unit) {
        while (true) {
            if (busy.value) withFrameNanos { frame.nanos = it } else { kotlinx.coroutines.delay(250); frame.nanos = System.nanoTime() }
        }
    }
    val view = LocalView.current
    SideEffect {
        val ctl = WindowCompat.getInsetsController((view.context as android.app.Activity).window, view)
        ctl.isAppearanceLightStatusBars = !vm.darkTheme; ctl.isAppearanceLightNavigationBars = !vm.darkTheme
    }
    CompositionLocalProvider(LocalPal provides pal, LocalFrame provides frame) {
        BoxWithConstraints(Modifier.fillMaxSize().background(pal.surface).imePadding()) {
            val wide = maxWidth >= 840.dp
            BackHandler(vm.pickOpen) { vm.pickOpen = false }
            BackHandler(!vm.pickOpen && !wide && vm.nowOpen) { vm.nowOpen = false }
            BackHandler(!vm.pickOpen && (wide || !vm.nowOpen) && (vm.query.isNotEmpty() || vm.filter != null)) {
                if (vm.query.isNotEmpty()) vm.clearSearch() else vm.filter = null
            }
            if (wide) WideLayout(vm, pickFolder, pickCookies) else CompactLayout(vm, pickFolder, pickCookies)
            ToastHost(vm, Modifier.align(Alignment.BottomCenter).padding(bottom = if (wide) 28.dp else 150.dp))
        }
        MenuSheet(vm)
        DialogHost(vm)
    }
}

// ============================================================ layouts
@Composable
private fun WideLayout(vm: AppVm, pickFolder: () -> Unit, pickCookies: () -> Unit) {
    val nowW by animateDpAsState(if (vm.tab == "lyrics") 560.dp else 400.dp, tween(550, easing = SmoothEasing), label = "nw")
    Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Rail(vm, pickFolder, Modifier.width(96.dp).fillMaxHeight())
        MainPane(vm, true, pickFolder, pickCookies, Modifier.weight(1f).fillMaxHeight())
        NowPanel(vm, vm.tab, { vm.tab = it }, Modifier.width(nowW).fillMaxHeight())
    }
}

@Composable
private fun CompactLayout(vm: AppVm, pickFolder: () -> Unit, pickCookies: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            MainPane(vm, false, pickFolder, pickCookies, Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp))
            if (vm.current != null) MiniPlayer(vm, { vm.nowOpen = true }, Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp))
            BottomNav(vm)
        }
        AnimatedVisibility(
            vm.nowOpen, Modifier.fillMaxSize(),
            enter = slideInVertically(tween(420, easing = SmoothEasing)) { it } + fadeIn(tween(250)),
            exit = slideOutVertically(tween(320, easing = SmoothEasing)) { it } + fadeOut(tween(250)),
        ) {
            // swipe down anywhere that is not a list or a slider: the full-screen player follows the finger (synchronously, no lag),
            // then glides on with the release velocity and collapses into the mini player
            var dragY by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
            var h by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            LaunchedEffect(vm.nowOpen) { if (vm.nowOpen) dragY = 0f else { kotlinx.coroutines.delay(500); dragY = 0f } }
            val dragState = androidx.compose.foundation.gestures.rememberDraggableState { dragY = (dragY + it).coerceAtLeast(0f) }
            Box(
                Modifier.fillMaxSize().onSizeChanged { h = it.height.toFloat() }.graphicsLayer { translationY = dragY }
                    .draggable(dragState, androidx.compose.foundation.gestures.Orientation.Vertical, onDragStopped = { v ->
                        val collapse = dragY > h * 0.2f || v > 1400f
                        scope.launch {
                            if (collapse) {
                                androidx.compose.animation.core.animate(dragY, h, v.coerceAtLeast(0f), tween(220, easing = androidx.compose.animation.core.FastOutLinearInEasing)) { x, _ -> dragY = x }
                                vm.nowOpen = false
                            } else androidx.compose.animation.core.animate(dragY, 0f, v, springy()) { x, _ -> dragY = x }
                        }
                    }),
            ) {
                NowPanel(vm, vm.tab, { vm.tab = it }, Modifier.fillMaxSize(), onClose = { vm.nowOpen = false }, rounded = false)
            }
        }
    }
}

// ============================================================ navigation
private val NAV = listOf(
    Triple(View.Tracks, "music", "Треки"), Triple(View.Albums, "album", "Альбомы"), Triple(View.Artists, "person", "Артисты"),
    Triple(View.Favs, "heart", "Избранное"), Triple(View.Eq, "eq", "Звук"),
)

@Composable
private fun NavItem(vm: AppVm, v: View, icon: String, label: String, modifier: Modifier = Modifier, size: Float = 12f) {
    val pal = LocalPal.current
    val active = vm.view == v && vm.query.isBlank()
    val bg by androidx.compose.animation.animateColorAsState(if (active) pal.secondaryContainer else Color.Transparent, tween(250), label = "nb")
    val pillW by animateDpAsState(if (active) 56.dp else 48.dp, springy(), label = "pw")
    val src = remember { MutableInteractionSource() }
    Column(
        modifier.androidxClick(src) { if (vm.query.isNotEmpty()) vm.clearSearch(); vm.view = v; vm.filter = null },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(pillW, 32.dp).clip(RoundedCornerShape(16.dp)).background(bg), contentAlignment = Alignment.Center) {
            Ico(icon, if (active) pal.onSecondaryContainer else pal.onSurfaceVar, 24.dp)
        }
        Box(Modifier.wrapContentWidth(unbounded = true)) { T(label, if (active) pal.onSurface else pal.onSurfaceVar, size, 600, spacing = -0.2f) }
    }
}

@Composable
private fun Rail(vm: AppVm, pickFolder: () -> Unit, modifier: Modifier) {
    val pal = LocalPal.current
    Column(modifier.padding(top = 28.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Morph(Modifier.padding(bottom = 14.dp).size(56.dp), pal.primaryContainer, 18.dp, 12.dp, pressScale = 0.9f, onClick = pickFolder) { Ico("add", pal.onPrimaryContainer, 24.dp) }
            NAV.forEach { (v, i, l) -> NavItem(vm, v, i, l) }
        }
        NavItem(vm, View.Settings, "settings", "Настройки")
    }
}

@Composable
private fun BottomNav(vm: AppVm) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        (NAV + Triple(View.Settings, "settings", "Настройки")).forEach { (v, i, l) -> NavItem(vm, v, i, l, Modifier.weight(1f).padding(vertical = 4.dp), size = 10.5f) }
    }
}

private fun Modifier.androidxClick(src: MutableInteractionSource, onClick: () -> Unit): Modifier = this.clickable(src, null, onClick = onClick)

// ============================================================ main pane: header, search, content
@Composable
private fun MainPane(vm: AppVm, wide: Boolean, pickFolder: () -> Unit, pickCookies: () -> Unit, modifier: Modifier) {
    val pal = LocalPal.current
    val searching = vm.query.isNotBlank()
    val h = if (wide) 28.dp else 16.dp
    Column(modifier.clip(RoundedCornerShape(if (wide) 32.dp else 28.dp)).background(pal.surfaceLow).padding(start = h, end = h, top = if (wide) 28.dp else 16.dp)) {
        Header(vm, wide, searching, pickFolder)
        vm.filter?.let { f ->
            if (!searching) Row(Modifier.padding(bottom = 14.dp)) {
                Morph(Modifier.height(40.dp), pal.secondaryContainer, 12.dp, 20.dp, pressScale = 0.94f, onClick = { vm.filter = null }) {
                    Row(Modifier.padding(start = 18.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Ico("close", pal.onSecondaryContainer, 18.dp); T(if (f.kind == "album") "Альбом" else "Артист", pal.onSecondaryContainer, 14f, 600)
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Crossfade(Triple(vm.view, searching, 0), animationSpec = tween(180), label = "view") { (v, s, _) ->
                when {
                    s -> SearchScreen(vm, wide)
                    v == View.Eq -> EqScreen(vm)
                    v == View.Settings -> SettingsScreen(vm, pickFolder, pickCookies)
                    else -> LibraryScreen(vm, wide, pickFolder)
                }
            }
        }
    }
}

@Composable
private fun Header(vm: AppVm, wide: Boolean, searching: Boolean, pickFolder: () -> Unit) {
    val pal = LocalPal.current
    val title = if (searching) "Поиск" else vm.filter?.label ?: vm.view.title
    val list = if (searching) vm.currentShown else vm.currentShown
    val sub = when {
        searching -> "${vm.currentShown.size} в библиотеке${if (vm.settings.yt) " · YouTube включён" else ""}"
        vm.view == View.Eq -> "10 полос · 31 Гц – 16 кГц"
        vm.view == View.Settings -> ""
        vm.scanning && vm.tracks.isEmpty() -> "Сканирую… ${vm.scanCount}"
        vm.scanning -> "Сканирую… ${vm.scanCount}"
        vm.tracks.isEmpty() -> "Библиотека пуста"
        else -> "${vm.currentShown.size} ${plural(vm.currentShown.size, "трек", "трека", "треков")}"
    }
    if (wide) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 18.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) { T(title, pal.onSurface, 46f, 700, spacing = -1.1f); if (sub.isNotEmpty()) T(sub, pal.onSurfaceVar, 14f, 500, Modifier.padding(top = 6.dp)) }
            SearchField(vm, Modifier.width(320.dp))
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) { T(title, pal.onSurface, 34f, 700, spacing = -0.8f); if (sub.isNotEmpty()) T(sub, pal.onSurfaceVar, 13.5f, 500, Modifier.padding(top = 2.dp)) }
                if (!searching && vm.view != View.Eq && vm.view != View.Settings) Morph(Modifier.size(48.dp), pal.primaryContainer, 16.dp, 10.dp, pressScale = 0.9f, onClick = pickFolder) { Ico("add", pal.onPrimaryContainer, 24.dp) }
            }
            SearchField(vm, Modifier.fillMaxWidth())
        }
    }
    @Suppress("UNUSED_VARIABLE") val unused = list
}

@Composable
private fun SearchField(vm: AppVm, modifier: Modifier) {
    val pal = LocalPal.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val focus = LocalFocusManager.current
    val r by animateDpAsState(if (focused) 20.dp else 28.dp, springy(), label = "sr")
    val bg by androidx.compose.animation.animateColorAsState(if (focused) pal.surfaceHighest else pal.surfaceHigh, tween(250), label = "sb")
    Row(modifier.height(56.dp).clip(RoundedCornerShape(r)).background(bg).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Ico("search", if (focused) pal.onSurface else pal.onSurfaceVar, 24.dp)
        Box(Modifier.weight(1f)) {
            if (vm.query.isEmpty()) T("Поиск по музыке", pal.onSurfaceVar, 15f)
            BasicTextField(
                vm.query, { vm.onQuery(it) }, Modifier.fillMaxWidth(), singleLine = true, interactionSource = src,
                textStyle = TextStyle(color = pal.onSurface, fontSize = 15.sp), cursorBrush = Brush.linearGradient(listOf(pal.primary, pal.primary)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            )
        }
        if (vm.query.isNotEmpty()) Morph(Modifier.size(32.dp), Color.Transparent, 16.dp, 10.dp, onClick = { vm.clearSearch() }) { Ico("close", pal.onSurfaceVar, 20.dp) }
    }
}
