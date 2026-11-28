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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 全局液态玻璃开关 CompositionLocal
 */
val LocalLiquidGlassEnabled = compositionLocalOf { false }

/**
 * 极光流体环境微光场 (LiquidGlassAtmosphere)
 *
 * 为液态玻璃提供透光的灵动空间环境底板。
 * 真正的液态玻璃必须置于富含光线梯度的空间才能产生光学折射与通透感，
 * 在纯黑或纯白死寂背景下任何玻璃都会退化为单调卡片。
 * 开启时在暗黑背景中注入流动的主题色极光云，呈现如深空极光与水滴折射交织的高级质感。
 */
@Composable
fun LiquidGlassAtmosphere(
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalLiquidGlassEnabled.current,
    content: @Composable BoxScope.() -> Unit
) {
    if (!enabled) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.background),
            content = content
        )
        return
    }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val accent = MaterialTheme.colorScheme.primary
    val secondaryTint = MaterialTheme.colorScheme.tertiary.copy(alpha = if (isDark) 0.16f else 0.10f)

    // 背景基础深邃色
    val baseBg = if (isDark) Color(0xFF0C1017) else Color(0xFFF4F6F9)

    Box(
        modifier = modifier
            .background(baseBg)
            .drawWithContent {
                // 1. 右上方漂浮极光光斑 (主色扩散)
                val orb1Center = Offset(size.width * 0.88f, size.height * 0.14f)
                val orb1Radius = size.width * 0.72f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = if (isDark) 0.22f else 0.14f),
                            accent.copy(alpha = if (isDark) 0.08f else 0.05f),
                            Color.Transparent
                        ),
                        center = orb1Center,
                        radius = orb1Radius
                    ),
                    radius = orb1Radius,
                    center = orb1Center
                )

                // 2. 左中部柔和次级极光云 (丰富折射色彩层次)
                val orb2Center = Offset(size.width * 0.08f, size.height * 0.48f)
                val orb2Radius = size.width * 0.68f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            secondaryTint,
                            accent.copy(alpha = if (isDark) 0.06f else 0.03f),
                            Color.Transparent
                        ),
                        center = orb2Center,
                        radius = orb2Radius
                    ),
                    radius = orb2Radius,
                    center = orb2Center
                )

                // 3. 底部微光漫反射
                val orb3Center = Offset(size.width * 0.75f, size.height * 0.86f)
                val orb3Radius = size.width * 0.60f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = if (isDark) 0.14f else 0.08f),
                            Color.Transparent
                        ),
                        center = orb3Center,
                        radius = orb3Radius
                    ),
                    radius = orb3Radius,
                    center = orb3Center
                )

                // 绘制内部实际页面内容
                drawContent()
            },
        content = content
    )
}

/**
 * 方案三（Shu Ding 核心透镜折射算法）物理级液态玻璃修饰符 (Modifier.liquidGlass)
 *
 * 核心光学物理层级：
 *  1. Ambient Aura Dispersion Shadow：带主题色散色散光晕的双层柔和悬浮投影
 *  2. Frosted Crystal Base：高透冰晶底质（暗色不发灰黑、亮色不发惨白）
 *  3. Lens Inner Inset Depth：边缘 5dp 弧形向内折射厚度暗角（物理透镜边缘厚度感）
 *  4. UI Content：正文内容置于其中，中间平整通透，文字极度清晰
 *  5. Top Specular Meniscus Hairline：顶部 1.5dp 水滴张力极细高光切线
 *  6. Convex Surface Gloss Glare：上部 40% 弧面环境光折射反射层
 *  7. Fresnel Refraction Rim：左上纯白高光、右下主题色折射的双色水晶精密切边
 */
fun Modifier.liquidGlass(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(18.dp),
    isDark: Boolean = false,
    tintColor: Color? = null,
    borderWidth: Dp = 1.2.dp,
    elevation: Dp = 10.dp,
): Modifier {
    if (!enabled) return this
    val accent = tintColor ?: if (isDark) Color(0xFF3482FF) else Color(0xFF9C4A2F)

    return this
        // 1. 悬浮双层彩色散色投影
        .shadow(
            elevation = elevation,
            shape = shape,
            ambientColor = accent.copy(alpha = if (isDark) 0.32f else 0.12f),
            spotColor = Color.Black.copy(alpha = if (isDark) 0.45f else 0.16f)
        )
        .clip(shape)
        // 2. 复合光学物理全层绘制（严格依照光线入射与反射顺序，绝不遮盖）
        .drawWithContent {
            val cornerPx = 18f * density
            val corner = CornerRadius(cornerPx, cornerPx)

            // A. 底层：高透冰晶半透明层 (Frosted Crystal Base)
            val glassBrush = Brush.linearGradient(
                colors = if (isDark) {
                    listOf(
                        Color.White.copy(alpha = 0.14f),        // 顶部高透白霜
                        Color(0xFF1B2332).copy(alpha = 0.45f),  // 中层通透深晶
                        Color(0xFF0F1420).copy(alpha = 0.58f)   // 底部透光暗底
                    )
                } else {
                    listOf(
                        Color.White.copy(alpha = 0.88f),        // 顶部晶莹白
                        Color.White.copy(alpha = 0.65f),        // 中层柔光
                        Color(0xFFEEF3F8).copy(alpha = 0.72f)   // 底部冷晶灰
                    )
                },
                start = Offset(0f, 0f),
                end = Offset(size.width * 0.7f, size.height)
            )
            drawRoundRect(
                brush = glassBrush,
                cornerRadius = corner
            )

            // B. 方案三核心：边缘 5dp 弧形向内折射厚度暗角 (Inner Inset Refraction Shadow)
            // 营造水滴边缘圆弧凸起、光线向内折射聚集的真实三维厚度感
            val lensEdgeWidth = 5.2f * density
            val insetShadowBrush = Brush.verticalGradient(
                colors = if (isDark) {
                    listOf(
                        Color.Black.copy(alpha = 0.10f),
                        Color.Black.copy(alpha = 0.36f)
                    )
                } else {
                    listOf(
                        Color.Black.copy(alpha = 0.04f),
                        Color.Black.copy(alpha = 0.15f)
                    )
                }
            )
            drawRoundRect(
                brush = insetShadowBrush,
                cornerRadius = corner,
                style = Stroke(width = lensEdgeWidth)
            )

            // C. 左上方微小环境彩色漫反射注入 (Ambient Light Leak)
            val leakRadius = size.width * 0.45f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = if (isDark) 0.24f else 0.12f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.15f, size.height * 0.15f),
                    radius = leakRadius
                ),
                radius = leakRadius,
                center = Offset(size.width * 0.15f, size.height * 0.15f)
            )

            // D. 绘制卡片内部正文内容 (Content)
            drawContent()

            // E. 表面光层 1：顶部 1.5dp 水滴张力极细镜面高光 (Specular Meniscus Hairline)
            val topSheenHeight = 1.6f * density
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = if (isDark) 0.60f else 0.85f),
                        Color.White.copy(alpha = if (isDark) 0.95f else 1.00f),
                        Color.White.copy(alpha = if (isDark) 0.60f else 0.85f),
                        Color.Transparent
                    )
                ),
                topLeft = Offset(cornerPx * 0.5f, 0f),
                size = Size((size.width - cornerPx).coerceAtLeast(0f), topSheenHeight)
            )

            // F. 表面光层 2：对角弧面环境镜面反光 (Convex Surface Gloss Glare)
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (isDark) 0.11f else 0.22f),
                        Color.White.copy(alpha = if (isDark) 0.03f else 0.08f),
                        Color.Transparent
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(size.width * 0.55f, size.height * 0.40f)
                ),
                topLeft = Offset(0f, 0f),
                size = Size(size.width, size.height * 0.45f)
            )

            // G. 菲涅尔双色精密水晶切边 (Fresnel Specular Rim)
            val rimStroke = borderWidth.toPx()
            val fresnelBrush = Brush.linearGradient(
                colors = if (isDark) {
                    listOf(
                        Color.White.copy(alpha = 0.92f), // 左上方纯净高反光切面
                        Color.White.copy(alpha = 0.45f),
                        accent.copy(alpha = 0.65f),     // 右下方折射环境溢彩色
                        Color.White.copy(alpha = 0.25f)
                    )
                } else {
                    listOf(
                        Color.White.copy(alpha = 1.00f),
                        Color.White.copy(alpha = 0.70f),
                        accent.copy(alpha = 0.45f),
                        Color.White.copy(alpha = 0.40f)
                    )
                },
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height)
            )
            drawRoundRect(
                brush = fresnelBrush,
                cornerRadius = corner,
                style = Stroke(width = rimStroke)
            )
        }
}

/**
 * 开箱即用的顶级液态玻璃卡片容器 (LiquidGlassCard)
 * 支持弹性触感反馈、方案三边缘物理折射与极光环境穿透
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalLiquidGlassEnabled.current,
    shape: Shape = RoundedCornerShape(18.dp),
    isDark: Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f,
    tintColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // 点击时的水滴微弹性微缩放 (0.982f)
    val scale by animateFloatAsState(
        targetValue = if (isPressed && onClick != null) 0.982f else 1f,
        animationSpec = spring(dampingRatio = 0.52f, stiffness = 820f),
        label = "liquid_glass_press"
    )

    val baseModifier = if (enabled) {
        modifier
            .scale(scale)
            .liquidGlass(
                enabled = true,
                shape = shape,
                isDark = isDark,
                tintColor = tintColor,
                borderWidth = 1.2.dp,
                elevation = if (isDark) 10.dp else 6.dp
            )
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

/**
 * 顶级液态玻璃底栏 (LiquidGlassBar)
 */
@Composable
fun LiquidGlassBar(
    labels: List<String>,
    iconsOn: List<ImageVector>,
    iconsOff: List<ImageVector>,
    selected: Int,
    onSelect: (Int) -> Unit,
    pageFraction: () -> Float,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val density = LocalDensity.current
    val shape = RoundedCornerShape(26.dp)

    val pillScale = remember { Animatable(1f) }
    var bumped by remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()
    val bumpPill: (Int) -> Unit = { idx ->
        bumped = idx
        scope.launch {
            pillScale.snapTo(1f)
            pillScale.animateTo(1.08f, tween(80, easing = LinearEasing))
            pillScale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 950f))
        }
    }

    val trans = rememberInfiniteTransition(label = "sheen")
    val sweep by trans.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7200, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )

    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .liquidGlass(
                enabled = true,
                shape = shape,
                isDark = dark,
                tintColor = accent,
                borderWidth = 1.4.dp,
                elevation = 16.dp
            )
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp, horizontal = 5.dp)
        ) {
            val count = labels.size.coerceAtLeast(1)
            val cell = maxWidth / count
            val cellPx = with(density) { cell.toPx() }
            val inset = 3.dp
            val insetPx = with(density) { inset.toPx() }

            val pillPos = remember { Animatable(selected.toFloat()) }
            var isDragging by remember { mutableStateOf(false) }

            val currentFraction = pageFraction().coerceIn(0f, (count - 1).toFloat())
            LaunchedEffect(currentFraction) {
                if (!isDragging) {
                    pillPos.snapTo(currentFraction)
                }
            }
            LaunchedEffect(selected) {
                if (!isDragging && abs(pillPos.value - selected) > 0.01f) {
                    pillPos.animateTo(
                        selected.toFloat(),
                        spring(dampingRatio = 0.62f, stiffness = 520f)
                    )
                }
            }

            val stretchScaleX by animateFloatAsState(
                targetValue = if (isDragging) 1.08f else 1f,
                animationSpec = spring(dampingRatio = 0.58f, stiffness = 600f),
                label = "pill-stretch-x",
            )
            val stretchScaleY by animateFloatAsState(
                targetValue = if (isDragging) 0.94f else 1f,
                animationSpec = spring(dampingRatio = 0.58f, stiffness = 600f),
                label = "pill-stretch-y",
            )

            // 玻璃胶囊指示器
            Box(
                Modifier
                    .width(cell - inset * 2)
                    .height(46.dp)
                    .offset {
                        val x = pillPos.value * cellPx + insetPx
                        IntOffset(x.roundToInt(), 0)
                    }
                    .graphicsLayer {
                        scaleX = pillScale.value * stretchScaleX
                        scaleY = pillScale.value * stretchScaleY
                    }
                    .clip(RoundedCornerShape(21.dp))
                    .background(accent.copy(alpha = if (dark) 0.26f else 0.20f))
                    .border(
                        1.2.dp,
                        Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = if (dark) 0.85f else 0.95f),
                                accent.copy(alpha = 0.60f)
                            )
                        ),
                        RoundedCornerShape(21.dp)
                    )
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .pointerInput(count, cellPx) {
                        detectHorizontalDragGestures(
                            onDragStart = { isDragging = true },
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
 * 高质感液态玻璃侧栏入口按钮 (DrawerButton)
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
    val shape = RoundedCornerShape(14.dp)
    val baseMod = if (isLiquidGlass) {
        modifier
            .size(42.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .liquidGlass(
                enabled = true,
                shape = shape,
                isDark = isDark,
                tintColor = accent,
                borderWidth = 1.2.dp,
                elevation = 8.dp
            )
    } else {
        modifier
            .size(42.dp)
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
            modifier = Modifier.size(22.dp),
        )
    }
}
