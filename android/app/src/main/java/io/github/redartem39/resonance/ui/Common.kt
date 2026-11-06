package io.github.redartem39.resonance.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.redartem39.resonance.data.Covers
import io.github.redartem39.resonance.data.Track
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/** One shared animation clock: composables read [nanos] in their draw phase, so only canvases redraw per frame. */
@Stable
class FrameState { var nanos by mutableLongStateOf(0L) }
val LocalFrame = staticCompositionLocalOf { FrameState() }

/** A circle whose edge is modulated by 9 lobes ("cookie"). amp 0 = circle. Same maths as the desktop clip-path. */
class CookieShape(private val amp: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val p = Path()
        val n = 120
        for (i in 0 until n) {
            val a = i.toDouble() / n * 2 * PI
            val r = 1 - amp + amp * cos(9 * a)
            val x = (size.width / 2 + size.width / 2 * r * cos(a)).toFloat()
            val y = (size.height / 2 + size.height / 2 * r * sin(a)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return Outline.Generic(p)
    }
}

@Composable
fun rememberCover(t: Track?, max: Int = 400): Bitmap? {
    val ctx = LocalContext.current
    return produceState<Bitmap?>(null, t?.path, t?.albumKey, max) { value = if (t == null) null else Covers.load(ctx, t, max) }.value
}

/** Cover or a placeholder icon in a tonal tile. */
@Composable
fun CoverTile(t: Track?, modifier: Modifier, bg: Color, fg: Color, max: Int = 400, iconSize: Dp = 44.dp) {
    val bmp = rememberCover(t, max)
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().background(bg))
        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Ico("album", fg.copy(alpha = 0.7f), iconSize)
    }
}

/** The wide rounded range slider of the desktop app: thick track, a thin vertical bar as the thumb. [fromCenter] fills from the middle (balance). */
@Composable
fun M3Slider(
    value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, min: Float = 0f, max: Float = 1f,
    fromCenter: Boolean = false, haloColor: Color, onFinish: () -> Unit = {},
) {
    val pal = LocalPal.current
    val cur by rememberUpdatedState(onChange)
    val fin by rememberUpdatedState(onFinish)
    Canvas(
        modifier.fillMaxWidth().height(36.dp).pointerInput(min, max) {
            awaitEachGesture {
                val down = awaitFirstDown()
                fun at(x: Float) = min + (x / size.width).coerceIn(0f, 1f) * (max - min)
                cur(at(down.position.x)); down.consume()
                drag(down.id) { cur(at(it.position.x)); it.consume() }
                fin()
            }
        },
    ) {
        val th = 16.dp.toPx(); val cy = size.height / 2; val pad = 3.dp.toPx()
        val p = ((value - min) / (max - min)).coerceIn(0f, 1f)
        val x = pad + (size.width - 2 * pad) * p
        drawRoundRect(pal.surfaceHighest, Offset(0f, cy - th / 2), Size(size.width, th), CornerRadius(th / 2))
        val a = if (fromCenter) minOf(size.width / 2, x) else 0f
        val b = if (fromCenter) maxOf(size.width / 2, x) else x
        if (b > a) drawRoundRect(pal.primary, Offset(a, cy - th / 2), Size(b - a, th), CornerRadius(th / 2))
        val tw = 6.dp.toPx(); val tH = 28.dp.toPx(); val halo = 4.dp.toPx()
        drawRoundRect(haloColor, Offset(x - tw / 2 - halo, cy - tH / 2 - halo), Size(tw + 2 * halo, tH + 2 * halo), CornerRadius((tw + 2 * halo) / 2))
        drawRoundRect(pal.primary, Offset(x - tw / 2, cy - tH / 2), Size(tw, tH), CornerRadius(tw / 2))
    }
}
