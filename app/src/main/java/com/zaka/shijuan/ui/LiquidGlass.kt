package com.zaka.shijuan.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import kotlin.math.roundToInt

/**
 * 液态玻璃底栏。
 *
 * 视觉：半透明渐变玻璃 + 顶部内高光 + 细描边 + 柔和投影，
 * 表面有一道缓慢扫过的高光，让它看起来是「活」的。
 *
 * 交互：整条可以左右拖动（拖动超过阈值才算拖，点按不受影响），
 * 选中胶囊用弹簧动画在项目之间滑动。
 */
@Composable
fun LiquidGlassBar(
    labels: List<String>,
    iconsOn: List<ImageVector>,
    iconsOff: List<ImageVector>,
    selected: Int,
    onSelect: (Int) -> Unit,
    pageFraction: Float,          // 当前页面位置（0..n-1），让胶囊跟着手指走
    modifier: Modifier = Modifier,
    maxDragPx: Float = 0f,        // 左右可拖动范围
    maxLiftPx: Float = 0f,        // 向上可拖动范围
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val d = LocalDensity.current.density
    val shape = RoundedCornerShape(28.dp)

    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var barSize by remember { mutableStateOf(Size.Zero) }

    // 高光扫动
    val trans = rememberInfiniteTransition(label = "sheen")
    val sweep by trans.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7200, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )

    val glassTop = if (dark) Color.White.copy(alpha = 0.13f) else Color.White.copy(alpha = 0.68f)
    val glassBottom = if (dark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.34f)
    val borderColor = if (dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.85f)
    val inkColor = MaterialTheme.colorScheme.onSurface

    val itemWidth = 68.dp

    Box(
        modifier = modifier
            .offset { IntOffset(dragX.roundToInt(), dragY.roundToInt()) }
            .pointerInput(maxDragPx, maxLiftPx) {
                detectDragGestures(onDrag = { change, dragAmount: Offset ->
                    change.consume()
                    dragX = (dragX + dragAmount.x).coerceIn(-maxDragPx, maxDragPx)
                    dragY = (dragY + dragAmount.y).coerceIn(-maxLiftPx, 0f)
                })
            }
            .shadow(
                elevation = 22.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.16f),
                spotColor = Color.Black.copy(alpha = 0.22f),
            )
            .clip(shape)
            .background(Brush.verticalGradient(listOf(glassTop, glassBottom)))
            .border(1.dp, borderColor, shape)
            .onSizeChanged { barSize = Size(it.width.toFloat(), it.height.toFloat()) }
    ) {
        // 玻璃表面的流动高光
        Box(
            Modifier.matchParentSize().clip(shape).background(
                Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = if (dark) 0.10f else 0.34f),
                        Color.Transparent,
                    ),
                    start = Offset(barSize.width * (sweep * 1.8f - 0.9f), -barSize.height * 0.2f),
                    end = Offset(barSize.width * (sweep * 1.8f - 0.5f), barSize.height * 1.2f),
                )
            )
        )

        // 顶部内高光
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.75f), Color.Transparent)
                    )
                )
        )

        // 选中胶囊：跟着页面位置滑动
        Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
            Box {
                val targetX = pageFraction * itemWidth.value
                val x by animateFloatAsState(
                    targetValue = targetX,
                    animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
                    label = "pill",
                )
                Box(
                    Modifier
                        .offset { IntOffset((x * d).roundToInt(), 0) }
                        .size(width = itemWidth, height = 54.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.34f else 0.20f),
                                    MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.20f else 0.12f),
                                )
                            )
                        )
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                            RoundedCornerShape(22.dp),
                        )
                )

                Row {
                    labels.indices.forEach { i ->
                        val on = i == selected
                        Column(
                            modifier = Modifier
                                .width(itemWidth)
                                .height(54.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(bounded = true, color = MaterialTheme.colorScheme.primary),
                                ) { onSelect(i) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                if (on) iconsOn[i] else iconsOff[i],
                                contentDescription = labels[i],
                                tint = if (on) MaterialTheme.colorScheme.primary
                                else inkColor.copy(alpha = 0.55f),
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                labels[i],
                                fontSize = 10.sp,
                                fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                                color = if (on) MaterialTheme.colorScheme.primary
                                else inkColor.copy(alpha = 0.55f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 拖动/滚动通用的小回弹动画数值 */
@Composable
fun animatedScale(pressed: Boolean): Float {
    val s by animateFloatAsState(
        if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "scale",
    )
    return s
}
