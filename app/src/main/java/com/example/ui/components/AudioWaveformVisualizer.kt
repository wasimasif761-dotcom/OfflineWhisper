package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.RecordingRed
import kotlin.random.Random

@Composable
fun AudioWaveformVisualizer(
    amplitude: Float,
    isRecording: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 28
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .testTag("audio_waveform_visualizer"),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val baseAmp = if (isRecording) maxOf(0.08f, amplitude) else 0.05f

        for (i in 0 until barCount) {
            // Apply slight rhythmic variation across bars
            val factor = 0.5f + 0.5f * kotlin.math.sin((i.toDouble() / barCount) * Math.PI).toFloat()
            val noise = if (isRecording) (Random.nextFloat() * 0.25f - 0.12f) else 0f
            val targetHeightFraction = (baseAmp * factor * 1.4f + noise).coerceIn(0.06f, 1.0f)

            val animatedHeight by animateFloatAsState(
                targetValue = targetHeightFraction * 50f,
                animationSpec = tween(durationMillis = 100),
                label = "bar_height_$i"
            )

            val brush = if (isRecording) {
                Brush.verticalGradient(
                    colors = listOf(RecordingRed, AccentCyan)
                )
            } else {
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.outlineVariant,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                )
            }

            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(animatedHeight.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(brush)
            )
        }
    }
}
