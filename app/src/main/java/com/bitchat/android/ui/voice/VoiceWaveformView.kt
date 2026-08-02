package com.bitchat.android.ui.voice

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animated waveform visualizer displayed during voice note recording.
 * Produces a series of vertical bars that pulse in a wave-like pattern,
 * giving the user visual feedback that audio is being captured.
 *
 * The animation is purely cosmetic (not driven by actual audio levels)
 * but the amplitude can optionally be driven externally via [amplitudeProvider].
 *
 * @param isActive Whether the recording is active (controls animation state).
 * @param barCount Number of bars to display.
 * @param amplitudeProvider Optional callback that provides real-time amplitude (0..1).
 *   If null, uses a pseudo-random animated pattern.
 * @param barColor Color of the bars.
 * @param minHeight Minimum bar height.
 * @param maxHeight Maximum bar height.
 */
@Composable
fun VoiceWaveformView(
    isActive: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 24,
    amplitudeProvider: (() -> Float)? = null,
    barColor: Color = MaterialTheme.colorScheme.primary,
    minHeight: Dp = 4.dp,
    maxHeight: Dp = 32.dp,
    barWidth: Dp = 3.dp,
    barSpacing: Dp = 2.dp,
    cornerRadius: Dp = 1.5.dp
) {
    // For animated mode when no amplitude provider is given
    var animationTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(isActive) {
        if (isActive) {
            while (true) {
                delay(80) // ~12.5 fps
                animationTick++
            }
        }
    }

    val minHeightPx = minHeight
    val maxHeightPx = maxHeight

    Canvas(modifier = modifier.height(maxHeight)) {
        val totalBarWidth = barWidth.toPx()
        val totalSpacing = barSpacing.toPx()
        val minPx = minHeightPx.toPx()
        val maxPx = maxHeightPx.toPx()
        val canvasHeight = size.height

        val totalWidth = barCount * totalBarWidth + (barCount - 1) * totalSpacing
        val startX = (size.width - totalWidth) / 2

        for (i in 0 until barCount) {
            val amplitude = if (amplitudeProvider != null) {
                amplitudeProvider()
            } else if (isActive) {
                // Animated pseudo-wave pattern
                val phase = (animationTick * 0.15f) + (i * 0.4f)
                val base = (sin(phase) * 0.3f + 0.5f).coerceIn(0.1f, 1.0f)
                val noise = Random.nextFloat() * 0.2f
                (base + noise).coerceIn(0.05f, 1.0f)
            } else {
                // Idle state: small static bars
                0.1f
            }

            val barHeight = minPx + (maxPx - minPx) * amplitude
            val x = startX + i * (totalBarWidth + totalSpacing)
            val y = (canvasHeight - barHeight) / 2

            drawRoundRect(
                color = barColor.copy(alpha = 0.6f + amplitude * 0.4f),
                topLeft = Offset(x, y),
                size = Size(totalBarWidth, barHeight),
                cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
            )
        }
    }
}

/**
 * Simpler version that just shows a "recording" pulsing dot animation.
 * Useful as a fallback or for compact UI areas.
 */
@Composable
fun RecordingPulseIndicator(
    isActive: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFFF3B30),
    size: Dp = 8.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recordingPulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    if (isActive) {
        Canvas(modifier = modifier.size(size)) {
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = this.size.minDimension / 2
            )
        }
    }
}

private val EaseInOutSine: Easing = CubicBezierEasing(0.37f, 0.0f, 0.63f, 1.0f)
