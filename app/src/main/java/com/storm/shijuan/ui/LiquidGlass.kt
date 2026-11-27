package com.storm.shijuan.ui

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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.launch
import kotlin.math.abs
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
    pageFraction: () -> Float,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val density = LocalDensity.current
    val shape = RoundedCornerShape(26.dp)

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
            .border(1.dp, borderColor, shape)
    ) {
        // 毛玻璃底色（Android 12+ API 31+ 硬件级高斯模糊 + 细腻通透渐变）
        Box(
            Modifier
                .matchParentSize()
                .then(
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        Modifier.blur(18.dp)
                    } else Modifier
                )
                .background(Brush.verticalGradient(listOf(glassTop, glassBottom)))
        )
        // 玻璃表面流动的高光：在 drawBehind 阶段读取 sweep，不触发整体重组
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val s = sweep
                    if (size.width > 0f && size.height > 0f) {
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = if (dark) 0.10f else 0.34f),
                                    Color.Transparent,
                                ),
                                start = Offset(size.width * (s * 1.8f - 0.9f), -size.height * 0.2f),
                                end = Offset(size.width * (s * 1.8f - 0.5f), size.height * 1.2f),
                            )
                        )
                    }
                }
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

        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 7.dp)) {
            val count = labels.size.coerceAtLeast(1)
            val cell = maxWidth / count
            val cellPx = with(density) { cell.toPx() }
            val inset = 3.dp
            val insetPx = with(density) { inset.toPx() }

            // 胶囊位置状态（连续浮点 0f..count-1）与拖拽控制
            val pillPos = remember { Animatable(selected.toFloat()) }
            var isDragging by remember { mutableStateOf(false) }

            // 未处于底栏拖拽手势时，自动与 Pager 实际翻页位移保持 1:1 连续同步
            val currentFraction = pageFraction().coerceIn(0f, (count - 1).toFloat())
            LaunchedEffect(currentFraction, isDragging) {
                if (!isDragging) {
                    if (abs(pillPos.value - currentFraction) > 0.001f) {
                        pillPos.snapTo(currentFraction)
                    }
                }
            }

            // 液态流体拉伸与回弹形变 (X 轴沿拖拽方向弹性延展至 1.15x，Y 轴收缩至 0.92x，呈现水滴水银般的液态张力)
            val stretchScaleX by animateFloatAsState(
                targetValue = if (isDragging) 1.15f else 1f,
                animationSpec = spring(dampingRatio = 0.52f, stiffness = 600f),
                label = "pillStretchX"
            )
            val stretchScaleY by animateFloatAsState(
                targetValue = if (isDragging) 0.92f else 1f,
                animationSpec = spring(dampingRatio = 0.52f, stiffness = 600f),
                label = "pillStretchY"
            )

            // 液态激活胶囊（底层，根据 pillPos 连续滑动）
            Box(
                Modifier
                    .offset {
                        val x = pillPos.value * cellPx + insetPx
                        IntOffset(x.roundToInt(), 0)
                    }
                    .width(cell - inset * 2)
                    .height(46.dp)
                    .graphicsLayer {
                        scaleX = pillScale.value * stretchScaleX
                        scaleY = pillScale.value * stretchScaleY
                    }
                    .clip(RoundedCornerShape(21.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                accent.copy(alpha = if (dark) 0.40f else 0.25f),
                                accent.copy(alpha = if (dark) 0.24f else 0.13f),
                            )
                        )
                    )
                    .border(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(
                                accent.copy(alpha = 0.45f),
                                accent.copy(alpha = 0.20f),
                            )
                        ),
                        RoundedCornerShape(21.dp)
                    )
            )

            // 五个等宽项（上层 Row：统一接管全条水平拖动手势与单项点击）
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .pointerInput(count, cellPx) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                isDragging = true
                            },
                            onDragEnd = {
                                if (isDragging) {
                                    isDragging = false
                                    val target = pillPos.value.roundToInt().coerceIn(0, count - 1)
                                    bumpPill(target)
                                    scope.launch {
                                        pillPos.animateTo(
                                            target.toFloat(),
                                            spring(dampingRatio = 0.52f, stiffness = 550f)
                                        )
                                    }
                                    if (target != selected) {
                                        onSelect(target)
                                    }
                                }
                            },
                            onDragCancel = {
                                isDragging = false
                                val target = selected.toFloat()
                                scope.launch {
                                    pillPos.animateTo(
                                        target,
                                        spring(dampingRatio = 0.55f, stiffness = 600f)
                                    )
                                }
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val deltaPage = dragAmount / cellPx
                                scope.launch {
                                    pillPos.snapTo(
                                        (pillPos.value + deltaPage).coerceIn(0f, (count - 1).toFloat())
                                    )
                                }
                            }
                        )
                    }
            ) {
                labels.forEachIndexed { i, label ->
                    val dist = abs(pillPos.value - i).coerceIn(0f, 1f)
                    val activeWeight = 1f - dist

                    val iconScale = 1f + 0.18f * activeWeight
                    val labelScale = 0.94f + 0.06f * activeWeight
                    val itemColor = lerp(ink.copy(alpha = 0.52f), accent, activeWeight)

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
                                scope.launch {
                                    pillPos.animateTo(
                                        i.toFloat(),
                                        spring(dampingRatio = 0.52f, stiffness = 550f)
                                    )
                                }
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
                                imageVector = if (activeWeight > 0.45f) iconsOn[i] else iconsOff[i],
                                contentDescription = label,
                                tint = itemColor,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = label,
                            fontSize = if (activeWeight > 0.5f) 10.5.sp else 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = if (activeWeight > 0.5f) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            color = itemColor,
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
        }
    }
}

@Composable
fun LiquidGlassBar(
    labels: List<String>,
    iconsOn: List<ImageVector>,
    iconsOff: List<ImageVector>,
    selected: Int,
    onSelect: (Int) -> Unit,
    pageFraction: Float,
    modifier: Modifier = Modifier,
) = LiquidGlassBar(
    labels = labels,
    iconsOn = iconsOn,
    iconsOff = iconsOff,
    selected = selected,
    onSelect = onSelect,
    pageFraction = { pageFraction },
    modifier = modifier,
)


/**
 * 侧栏入口：小说 / 漫画 的切换放在抽屉里，这里就是那个按钮。
 * 长得跟底栏一套：玻璃底、圆角、按下去有回弹。
 */
@Composable
fun DrawerButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val isLiquidGlass = LocalLiquidGlassEnabled.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 780f),
        label = "drawer-press",
    )
    val shape = RoundedCornerShape(13.dp)
    val baseMod = if (isLiquidGlass) {
        modifier
            .size(40.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .liquidGlass(enabled = true, shape = shape, isDark = isDark, tintColor = accent, borderWidth = 1.dp)
    } else {
        modifier
            .size(40.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(ink.copy(alpha = 0.07f))
            .border(1.dp, ink.copy(alpha = 0.08f), shape)
    }
    Box(
        modifier = baseMod.clickable(
            interactionSource = interaction,
            indication = ripple(bounded = true, color = accent),
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Menu,
            contentDescription = "切换小说 / 漫画",
            tint = if (isLiquidGlass) (if (isDark) Color.White else ink) else ink.copy(alpha = 0.72f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 全局液态玻璃质感开关 CompositionLocal
 */
val LocalLiquidGlassEnabled = compositionLocalOf { true }

/**
 * 【方案三 Shu Ding 物理透镜折射算法】高阶液态玻璃 (Liquid Glass)
 * 参考 Vercel 设计工程师 Shu Ding 开源方案 (shuding/liquid-glass)
 *
 * 核心光学机制：
 *  1. 边界 SDF 透镜折射过渡区：在卡片边缘 4~6dp 范围形成光学曲率凹陷与透镜厚度过渡；
 *  2. 中间平坦透光：卡片正文区域零变形，保持文字绝对锐利清透；
 *  3. 双层内嵌物理景深（Shu Ding Inset Shadow）：模拟真实的物理玻璃厚度折射；
 *  4. 菲涅尔双色高光水晶切边（Crisp Specular Rim）：受光面高饱和纯白光边与反光；
 *  5. 顶部水滴抛光反光线（Chamfer Specular Hairline）：1dp 截面透镜反光。
 */
fun Modifier.liquidGlass(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(16.dp),
    isDark: Boolean = false,
    tintColor: Color? = null,
    borderWidth: Dp = 1.2.dp,
): Modifier {
    if (!enabled) return this
    val accent = tintColor ?: if (isDark) Color(0xFF3482FF) else Color(0xFF9C4A2F)

    return this
        .shadow(
            elevation = if (isDark) 8.dp else 5.dp,
            shape = shape,
            ambientColor = accent.copy(alpha = if (isDark) 0.25f else 0.08f),
            spotColor = Color.Black.copy(alpha = if (isDark) 0.40f else 0.14f)
        )
        .clip(shape)
        // 1. 底层：环境光晕漫反射 + 方案三内嵌透镜折射厚度 (Inset Refraction Shadow)
        .drawBehind {
            // 背景左上方环境彩色光晕 (透出环境背景色)
            val glowRadius = size.maxDimension * 0.85f
            val glowCenter = Offset(size.width * 0.10f, size.height * 0.15f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = if (isDark) 0.28f else 0.16f),
                        Color.Transparent
                    ),
                    center = glowCenter,
                    radius = glowRadius
                ),
                radius = glowRadius,
                center = glowCenter
            )

            // 方案三核心：边缘透镜厚度阴影 (Inner Inset Refraction Depth)
            // 仅在边缘 4~6dp 产生暗部下沉与透镜厚度过渡，中间保持平坦通透
            val lensEdgeWidth = 4.5f * density
            val cornerPx = 16f * density
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = if (isDark) listOf(
                        Color.Black.copy(alpha = 0.08f),
                        Color.Black.copy(alpha = 0.28f)
                    ) else listOf(
                        Color.Black.copy(alpha = 0.03f),
                        Color.Black.copy(alpha = 0.12f)
                    )
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx, cornerPx),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = lensEdgeWidth)
            )

            // 顶部 1dp 抛光截面水滴张力反光线
            val topSheenHeight = 1.2f * density
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = if (isDark) 0.45f else 0.85f),
                        Color.White.copy(alpha = if (isDark) 0.75f else 0.98f),
                        Color.White.copy(alpha = if (isDark) 0.45f else 0.85f),
                        Color.Transparent,
                    ),
                    startX = 0f,
                    endX = size.width
                ),
                topLeft = Offset(0f, 0f),
                size = Size(size.width, topSheenHeight)
            )
        }
        // 2. 通透磨砂白霜层 (Frosted Mist Body)
        .background(
            brush = Brush.linearGradient(
                colors = if (isDark) {
                    listOf(
                        Color.White.copy(alpha = 0.18f),         // 顶部白霜反光
                        Color(0xFF222B3A).copy(alpha = 0.58f),  // 中层通透磨砂亚克力
                        Color(0xFF131822).copy(alpha = 0.70f)   // 底部深邃通透
                    )
                } else {
                    listOf(
                        Color.White.copy(alpha = 0.92f),         // 顶部高光霜白
                        Color.White.copy(alpha = 0.70f),         // 中层通透乳白
                        Color(0xFFEEF3F8).copy(alpha = 0.78f)    // 底部冰感淡蓝灰
                    )
                },
                start = Offset(0f, 0f),
                end = Offset.Infinite
            ),
            shape = shape
        )
        // 3. 水晶边缘表面张力高光边框 (Crisp Specular Rim)
        .border(
            width = borderWidth,
            brush = Brush.linearGradient(
                colors = if (isDark) {
                    listOf(
                        Color.White.copy(alpha = 0.85f), // 左上受光面晶莹高光
                        Color.White.copy(alpha = 0.35f),
                        accent.copy(alpha = 0.45f),     // 右下折射环境彩光
                        Color.White.copy(alpha = 0.15f)
                    )
                } else {
                    listOf(
                        Color.White.copy(alpha = 0.98f),
                        Color.White.copy(alpha = 0.65f),
                        accent.copy(alpha = 0.35f),
                        Color.White.copy(alpha = 0.30f)
                    )
                },
                start = Offset(0f, 0f),
                end = Offset.Infinite
            ),
            shape = shape
        )
}

/**
 * 开箱即用的方案三液态玻璃卡片容器 (LiquidGlassCard)
 * 支持水滴微弹性交互响应与全局开关控制
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalLiquidGlassEnabled.current,
    shape: Shape = RoundedCornerShape(16.dp),
    isDark: Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f,
    tintColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // 点击时的水滴微弹性微缩放 (0.985f)
    val scale by animateFloatAsState(
        targetValue = if (isPressed && onClick != null) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 780f),
        label = "liquid_glass_press"
    )

    val baseModifier = if (enabled) {
        modifier
            .scale(scale)
            .liquidGlass(enabled = true, shape = shape, isDark = isDark, tintColor = tintColor)
    } else {
        modifier
            .scale(scale)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f), shape)
    }

    Box(
        modifier = baseModifier.then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true, color = MaterialTheme.colorScheme.primary),
                    onClick = onClick
                )
            } else Modifier
        ),
        content = content
    )
}
