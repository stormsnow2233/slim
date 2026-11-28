package com.storm.shijuan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Canva 可画风格高精度触控交互色轮 (Color Wheel)
 * 极角表示色相 (Hue, 0°~360°)，极径表示饱和度 (Saturation, 0~1)
 */
@Composable
fun ColorWheel(
    hue: Float,
    saturation: Float,
    lightness: Float,
    onHslChange: (h: Float, s: Float) -> Unit,
    modifier: Modifier = Modifier,
    wheelSize: Dp = 190.dp
) {
    val rainbowColors = remember {
        listOf(
            Color.Red,
            Color.Yellow,
            Color.Green,
            Color.Cyan,
            Color.Blue,
            Color.Magenta,
            Color.Red
        )
    }

    Box(
        modifier = modifier
            .size(wheelSize)
            .aspectRatio(1f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .pointerInput(Unit) {
                    fun updateFromOffset(pos: Offset) {
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val centerX = w / 2f
                        val centerY = h / 2f
                        val radius = min(centerX, centerY)
                        if (radius <= 0f) return

                        val dx = pos.x - centerX
                        val dy = pos.y - centerY
                        val dist = hypot(dx, dy)
                        val s = (dist / radius).coerceIn(0f, 1f)

                        val rad = atan2(dy.toDouble(), dx.toDouble())
                        var deg = Math.toDegrees(rad).toFloat()
                        if (deg < 0f) deg += 360f

                        onHslChange(deg.coerceIn(0f, 360f), s)
                    }

                    detectTapGestures(
                        onPress = { offset ->
                            updateFromOffset(offset)
                        }
                    )
                }
                .pointerInput(Unit) {
                    fun updateFromOffset(pos: Offset) {
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val centerX = w / 2f
                        val centerY = h / 2f
                        val radius = min(centerX, centerY)
                        if (radius <= 0f) return

                        val dx = pos.x - centerX
                        val dy = pos.y - centerY
                        val dist = hypot(dx, dy)
                        val s = (dist / radius).coerceIn(0f, 1f)

                        val rad = atan2(dy.toDouble(), dx.toDouble())
                        var deg = Math.toDegrees(rad).toFloat()
                        if (deg < 0f) deg += 360f

                        onHslChange(deg.coerceIn(0f, 360f), s)
                    }

                    detectDragGestures(
                        onDragStart = { offset -> updateFromOffset(offset) },
                        onDrag = { change, _ ->
                            change.consume()
                            updateFromOffset(change.position)
                        }
                    )
                }
        ) {
            val radius = drawContext.size.minDimension / 2f
            val center = Offset(drawContext.size.width / 2f, drawContext.size.height / 2f)

            // 1. 绘制色相环（SweepGradient 360度彩虹）
            val sweepBrush = Brush.sweepGradient(rainbowColors, center = center)
            drawCircle(
                brush = sweepBrush,
                radius = radius,
                center = center
            )

            // 2. 叠加径向渐变（中心纯白至外边缘透明，实现从中心向外饱和度渐变）
            val radialBrush = Brush.radialGradient(
                colors = listOf(Color.White, Color.Transparent),
                center = center,
                radius = radius
            )
            drawCircle(
                brush = radialBrush,
                radius = radius,
                center = center,
                blendMode = BlendMode.SrcOver
            )

            // 3. 绘制外轮廓细边框，增强立体感
            drawCircle(
                color = Color.Black.copy(alpha = 0.08f),
                radius = radius - 1f,
                center = center,
                style = Stroke(width = 2f)
            )

            // 4. 计算并绘制当前色彩指针 (Handle)
            val angleRad = Math.toRadians(hue.toDouble())
            val handleDist = saturation * radius
            val handleX = center.x + (handleDist * cos(angleRad)).toFloat()
            val handleY = center.y + (handleDist * sin(angleRad)).toFloat()
            val handleCenter = Offset(handleX, handleY)

            val currentColor = ThemeColorGenerator.hslToColor(hue, saturation, lightness)

            // 指针外阴影环
            drawCircle(
                color = Color.Black.copy(alpha = 0.25f),
                radius = 13.dp.toPx(),
                center = handleCenter,
                style = Stroke(width = 3.dp.toPx())
            )
            // 指针白圈外框
            drawCircle(
                color = Color.White,
                radius = 12.dp.toPx(),
                center = handleCenter,
                style = Stroke(width = 2.5.dp.toPx())
            )
            // 指针内圈实心色（展示当前颜色）
            drawCircle(
                color = currentColor,
                radius = 9.dp.toPx(),
                center = handleCenter
            )
        }
    }
}
