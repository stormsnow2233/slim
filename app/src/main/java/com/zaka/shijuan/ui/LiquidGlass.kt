package com.zaka.shijuan.ui

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 液态玻璃底栏。
 *
 * 视觉：半透明渐变玻璃 + 顶部内高光 + 细描边 + 柔和投影，
 * 表面有一道缓慢扫过的高光，让它看起来是「活」的。
 *
 * 交互（v2.2 起）：
 *  - 整条底栏固定，不再跟着手指乱跑
 *  - 只有那枚玻璃胶囊可以左右拖动，松手吸附到最近的一项
 *  - 每项等宽、图标与文字固定基线，五个也不挤
 */
@Composable
fun LiquidGlassBar(
    labels: List<String>,
    iconsOn: List<ImageVector>,
    iconsOff: List<ImageVector>,
    selected: Int,
    onSelect: (Int) -> Unit,
    /** 当前页面位置（0..n-1），让胶囊跟着左右翻页一起走 */
    pageFraction: Float,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val density = LocalDensity.current
    val shape = RoundedCornerShape(26.dp)

    var barSize by remember { mutableStateOf(Size.Zero) }
    var pillDrag by remember { mutableFloatStateOf(0f) }

    // 点击时的「啵」一下：胶囊瞬间撑大再弹回，酷安那种手感。
    val pillScale = remember { Animatable(1f) }
    // 被点中的那一格额外弹一下（同一格连点也会弹）
    var bumped by remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()
    val bumpPill: (Int) -> Unit = { idx ->
        bumped = idx
        scope.launch {
            pillScale.snapTo(1f)
            pillScale.animateTo(1.10f, tween(90, easing = LinearEasing))
            pillScale.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 900f))
        }
    }

    val trans = rememberInfiniteTransition(label = "sheen")
    val sweep by trans.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7200, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )

    val glassTop = if (dark) Color.White.copy(alpha = 0.13f) else Color.White.copy(alpha = 0.70f)
    val glassBottom = if (dark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.36f)
    val borderColor = if (dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.85f)
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .shadow(
                elevation = 20.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.14f),
                spotColor = Color.Black.copy(alpha = 0.20f),
            )
            .clip(shape)
            .background(Brush.verticalGradient(listOf(glassTop, glassBottom)))
            .border(1.dp, borderColor, shape)
            .onSizeChanged { barSize = Size(it.width.toFloat(), it.height.toFloat()) }
    ) {
        // 玻璃表面流动的高光
        if (barSize.width > 0f) {
            Box(
                Modifier.matchParentSize().background(
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
        }

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

        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 7.dp)) {
            val count = labels.size.coerceAtLeast(1)
            val cell = maxWidth / count
            val cellPx = with(density) { cell.toPx() }
            val inset = 3.dp
            val insetPx = with(density) { inset.toPx() }

            val basePx = pageFraction.coerceIn(0f, (count - 1).toFloat()) * cellPx
            val pillX by animateFloatAsState(
                targetValue = basePx + pillDrag,
                animationSpec = spring(dampingRatio = 0.78f, stiffness = 560f),
                label = "pill",
            )

            // 胶囊（只在它自己身上响应拖动）
            Box(
                Modifier
                    .offset { IntOffset((pillX + insetPx).roundToInt(), 0) }
                    .width(cell - inset * 2)
                    .height(46.dp)
                    .graphicsLayer {
                        scaleX = pillScale.value
                        scaleY = pillScale.value
                    }
                    .clip(RoundedCornerShape(21.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                accent.copy(alpha = if (dark) 0.36f else 0.20f),
                                accent.copy(alpha = if (dark) 0.20f else 0.11f),
                            )
                        )
                    )
                    .border(1.dp, accent.copy(alpha = 0.26f), RoundedCornerShape(21.dp))
            )

            // 五个等宽项
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                labels.forEachIndexed { i, label ->
                    val on = i == selected
                    // 选中项图标弹起来一点，跟胶囊同步
                    val iconScale by animateFloatAsState(
                        targetValue = if (on) 1.18f else 1f,
                        animationSpec = spring(dampingRatio = 0.48f, stiffness = 620f),
                        label = "icon-$i",
                    )
                    val labelScale by animateFloatAsState(
                        targetValue = if (on) 1f else 0.94f,
                        animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
                        label = "label-$i",
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(21.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true, color = accent),
                            ) {
                                bumpPill(i)
                                onSelect(i)
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            Modifier
                                .size(22.dp)
                                .graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (on) iconsOn[i] else iconsOff[i],
                                contentDescription = label,
                                tint = if (on) accent else ink.copy(alpha = 0.52f),
                                modifier = Modifier.size(21.dp),
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = label,
                            fontSize = if (on) 10.5.sp else 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            color = if (on) accent else ink.copy(alpha = 0.52f),
                            modifier = Modifier
                                .height(13.dp)
                                .graphicsLayer {
                                    scaleX = labelScale
                                    scaleY = labelScale
                                },
                        )
                    }
                }
            }

            // 拖动层：盖在胶囊正上方，只有这一小块能接手势。
            // 点别的格子照常是点击（tap 会落到下面那层按钮上），
            // 只有从胶囊上按下并横向拖动，胶囊才跟着手指走。
            val baseNow by rememberUpdatedState(basePx)
            var dragging by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .offset { IntOffset((pillX + insetPx).roundToInt(), 0) }
                    .width(cell - inset * 2)
                    .height(46.dp)
                    .pointerInput(count, cellPx) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (dragging) {
                                    val target = ((baseNow + pillDrag + insetPx) / cellPx)
                                        .roundToInt().coerceIn(0, count - 1)
                                    pillDrag = 0f
                                    if (target != selected) onSelect(target)
                                }
                                dragging = false
                            },
                            onDragCancel = {
                                pillDrag = 0f
                                dragging = false
                            },
                            onHorizontalDrag = { change, amount ->
                                dragging = true
                                change.consume()
                                val left = -baseNow
                                val right = cellPx * (count - 1) - baseNow
                                pillDrag = (pillDrag + amount)
                                    .coerceIn(minOf(left, right), maxOf(left, right))
                            },
                        )
                    }
            )
        }
    }
}

/** 按压缩放动画 */
@Composable
fun animatedScale(pressed: Boolean): Float {
    val v by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 700f),
        label = "press",
    )
    return v
}

/**
 * 侧栏入口：小说 / 漫画 的切换放在抽屉里，这里就是那个按钮。
 * 长得跟底栏一套：玻璃底、圆角、按下去有回弹。
 */
@Composable
fun DrawerButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    // 按压状态直接从 clickable 的 interactionSource 读。
    // （之前这里另外挂了一层 detectTapGestures，它会把 down/up 事件消费掉，
    //  导致 clickable 收不到点击 —— 按下去有回弹，抽屉却打不开。）
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 780f),
        label = "drawer-press",
    )
    Box(
        modifier = modifier
            .size(40.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(13.dp))
            .background(ink.copy(alpha = 0.07f))
            .border(1.dp, ink.copy(alpha = 0.08f), RoundedCornerShape(13.dp))
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true, color = accent),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Menu,
            contentDescription = "切换小说 / 漫画",
            tint = ink.copy(alpha = 0.72f),
            modifier = Modifier.size(20.dp),
        )
    }
}
