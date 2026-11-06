package io.github.redartem39.resonance.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** OKLCH -> sRGB with chroma reduction when a colour falls outside the gamut (what CSS does for oklch()). */
object Oklch {
    private fun lin(l: Double, c: Double, h: Double): DoubleArray {
        val hr = h * PI / 180
        val a = c * cos(hr); val b = c * sin(hr)
        val l_ = l + 0.3963377774 * a + 0.2158037573 * b
        val m_ = l - 0.1055613458 * a - 0.0638541728 * b
        val s_ = l - 0.0894841775 * a - 1.2914855480 * b
        val x = l_ * l_ * l_; val y = m_ * m_ * m_; val z = s_ * s_ * s_
        return doubleArrayOf(
            4.0767416621 * x - 3.3077115913 * y + 0.2309699292 * z,
            -1.2684380046 * x + 2.6097574011 * y - 0.3413193965 * z,
            -0.0041960863 * x - 0.7034186147 * y + 1.7076147010 * z,
        )
    }
    private fun inGamut(v: DoubleArray) = v.all { it in -0.0005..1.0005 }
    private fun gamma(v: Double): Float { val x = v.coerceIn(0.0, 1.0); return (if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055).toFloat() }

    fun color(l: Double, c: Double, h: Double, alpha: Float = 1f): Color {
        var v = lin(l, c, h)
        if (!inGamut(v)) {
            var lo = 0.0; var hi = c
            repeat(12) { val mid = (lo + hi) / 2; if (inGamut(lin(l, mid, h))) lo = mid else hi = mid }
            v = lin(l, lo, h)
        }
        return Color(gamma(v[0]), gamma(v[1]), gamma(v[2]), alpha)
    }
}

/** The tonal palette generated from the current cover (hue h, chroma c), dark or light. Same token set as the desktop CSS. */
@Immutable
class Pal(val dark: Boolean, val h: Float, val c: Float) {
    private fun o(l: Double, k: Double, dh: Double = 0.0) = Oklch.color(l, c * k, h + dh)
    val primary = if (dark) o(.80, 1.0) else o(.45, 1.0)
    val onPrimary = if (dark) o(.25, .9) else Color.White
    val primaryContainer = if (dark) o(.36, .9) else o(.89, .6)
    val onPrimaryContainer = if (dark) o(.91, .5) else o(.24, .8)
    val secondaryContainer = if (dark) o(.34, .4) else o(.90, .3)
    val onSecondaryContainer = if (dark) o(.90, .3) else o(.26, .4)
    val tertiary = if (dark) o(.80, .9, 60.0) else o(.48, .9, 60.0)
    val tertiaryContainer = if (dark) o(.36, .8, 60.0) else o(.90, .6, 60.0)
    val onTertiaryContainer = if (dark) o(.91, .5, 60.0) else o(.26, .7, 60.0)
    val surface = if (dark) o(.165, .22) else o(.975, .12)
    val surfaceLow = if (dark) o(.195, .26) else o(.955, .16)
    val surfaceMid = if (dark) o(.225, .3) else o(.935, .2)
    val surfaceHigh = if (dark) o(.26, .32) else o(.915, .22)
    val surfaceHighest = if (dark) o(.30, .34) else o(.89, .24)
    val onSurface = if (dark) o(.93, .1) else o(.22, .15)
    val onSurfaceVar = if (dark) o(.78, .22) else o(.42, .25)
    val outline = if (dark) o(.45, .25) else o(.70, .2)
    val danger = if (dark) Oklch.color(.72, .17, 25.0) else Oklch.color(.48, .2, 25.0)
    val dangerBg = Oklch.color(.58, .2, 25.0)

    // The equalizer stage is a dark glass inset in every theme, with its own bright tokens
    val stagePrimary = Oklch.color(.82, c * 1.15, h.toDouble())
    val stageTertiary = Oklch.color(.82, c * 1.05, h + 62.0)
    val stageOn = Oklch.color(.94, c * .1, h.toDouble())
    val stageOnVar = Oklch.color(.78, c * .25, h.toDouble())
    val stageTop = Oklch.color(.25, c * .55, h.toDouble())
    val stageBottom = Oklch.color(.155, c * .3, h.toDouble())
    val stageSurface = Oklch.color(.175, c * .3, h.toDouble())

    fun mix(a: Color, b: Color, t: Float) = lerp(a, b, t)
    fun on(base: Color, pct: Float) = onSurface.copy(alpha = pct)
}

val LocalPal = staticCompositionLocalOf { Pal(true, 285f, 0.1f) }

val SmoothEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
fun <T> springy() = spring<T>(dampingRatio = 0.52f, stiffness = 340f)
fun <T> springySoft() = spring<T>(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow)

/** Colour for an on-screen gradient across EQ bands (primary -> tertiary). */
fun bandColor(pal: Pal, i: Int) = lerp(pal.stagePrimary, pal.stageTertiary, i / 9f)

fun Float.approx(o: Float, eps: Float = 0.001f) = abs(this - o) < eps
