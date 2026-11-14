package com.storm.shijuan.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 经典与 MiuiX 风格自适应 Switch 开关
 * 当处于 MiuiX 主题时自动呈现 Xiaomi HyperOS / compose-miuix-ui 弹簧动效胶囊开关；
 * 当处于经典主题时呈现沉香墨 / 赭石原生开关。
 */
@Composable
fun AdaptiveSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (LocalAppThemeStyle.current == AppThemeStyle.MiuiX) {
        MiuixSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled
        )
    } else {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

/**
 * 纯正小米 MIUI / HyperOS 弹簧动效胶囊开关（参考 compose-miuix-ui / CZeroX / ZeroUI）
 */
@Composable
fun MiuixSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val currentOnCheckedChange by rememberUpdatedState(onCheckedChange)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isDragged by interactionSource.collectIsDraggedAsState()

    val hapticFeedback = LocalHapticFeedback.current
    var rawDragOffset by remember { mutableFloatStateOf(0f) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    val capsuleShape = RoundedCornerShape(14.dp)
    val thumbOffsetSpringSpec = remember { spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.72f, stiffness = 950f) }
    val thumbScaleSpringSpec = remember { spring<Float>(dampingRatio = 0.62f, stiffness = 980f) }

    // 48dp 宽度 - 22dp 滑块 - 3dp 边距 = 23dp 终点位移
    val thumbOffsetState = animateDpAsState(
        targetValue = (if (checked) 23.dp else 3.dp) + dragOffset.dp,
        animationSpec = thumbOffsetSpringSpec,
        label = "MiuixSwitchOffset"
    )

    val thumbScaleState = animateFloatAsState(
        targetValue = if (!enabled) 1f else if (isPressed || isDragged) 1.12f else 1f,
        animationSpec = thumbScaleSpringSpec,
        label = "MiuixSwitchScale"
    )

    val activeTrackColor = MaterialTheme.colorScheme.primary
    val inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)

    val backgroundColorState = animateColorAsState(
        targetValue = if (checked) activeTrackColor else inactiveTrackColor,
        animationSpec = spring(dampingRatio = 0.95f, stiffness = 450f),
        label = "MiuixSwitchBg"
    )

    val toggleableModifier = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            onValueChange = { v ->
                currentOnCheckedChange?.invoke(v)
            },
            enabled = enabled,
            role = Role.Switch,
            interactionSource = interactionSource,
            indication = null
        )
    } else {
        Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
            if (!enabled) disabled()
        }
    }

    Box(
        modifier = modifier
            .wrapContentSize(Alignment.Center)
            .size(48.dp, 28.dp)
            .clip(capsuleShape)
            .drawBehind {
                drawRect(backgroundColorState.value)
            }
            .then(toggleableModifier)
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .align(Alignment.CenterStart)
                .offset {
                    IntOffset(thumbOffsetState.value.roundToPx(), 0)
                }
                .graphicsLayer {
                    scaleX = thumbScaleState.value
                    scaleY = thumbScaleState.value
                }
                .shadow(2.dp, CircleShape)
                .background(Color.White, CircleShape)
                .then(
                    if (enabled && onCheckedChange != null) {
                        Modifier.draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { delta ->
                                rawDragOffset += delta / 2f
                                dragOffset = if (checked) {
                                    rawDragOffset.coerceIn(-20f, 0f)
                                } else {
                                    rawDragOffset.coerceIn(0f, 20f)
                                }
                            },
                            onDragStopped = {
                                if (checked && dragOffset < -10f) {
                                    currentOnCheckedChange?.invoke(false)
                                } else if (!checked && dragOffset > 10f) {
                                    currentOnCheckedChange?.invoke(true)
                                }
                                rawDragOffset = 0f
                                dragOffset = 0f
                            }
                        )
                    } else Modifier
                )
        )
    }
}

/**
 * MiuiX / CZeroX 标志性圆角分组卡片容器
 */
@Composable
fun MiuixCardGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = if (LocalAppThemeStyle.current == AppThemeStyle.MiuiX) 0.5.dp else 0.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}

/**
 * MiuiX 分组卡片内嵌项
 */
@Composable
fun MiuixPreferenceItem(
    title: String,
    subtitle: String = "",
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    iconBg: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    onClick: (() -> Unit)? = null,
    endAction: @Composable (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }

        if (endAction != null) {
            Spacer(Modifier.width(8.dp))
            endAction()
        } else if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * MiuiX 开关偏好设置项（整行可点）
 */
@Composable
fun MiuixSwitchPreference(
    title: String,
    subtitle: String = "",
    icon: ImageVector? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    MiuixPreferenceItem(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onClick = if (enabled) { { onCheckedChange(!checked) } } else null,
        endAction = {
            AdaptiveSwitch(
                checked = checked,
                onCheckedChange = if (enabled) onCheckedChange else null,
                enabled = enabled
            )
        }
    )
}

/**
 * MiuiX 风格组内分割线（精准避让图标宽度，符合 HyperOS 规范）
 */
@Composable
fun MiuixDivider(hasIcon: Boolean = true) {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        modifier = Modifier.padding(start = if (hasIcon) 66.dp else 16.dp, end = 16.dp)
    )
}

/**
 * MiuiX 风格分组标题
 */
@Composable
fun MiuixSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = if (LocalAppThemeStyle.current == AppThemeStyle.MiuiX) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
    )
}

/**
 * 全局自适应滑块控件（依据当前主题动态切换小米 HyperOS 厚轨圆钮风格与经典风格）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val isMiuix = LocalAppThemeStyle.current == AppThemeStyle.MiuiX

    if (isMiuix) {
        val isPressed by interactionSource.collectIsPressedAsState()
        val isDragged by interactionSource.collectIsDraggedAsState()
        val isActive = isPressed || isDragged

        val thumbScale by animateFloatAsState(
            targetValue = if (isActive) 1.18f else 1.0f,
            animationSpec = spring(dampingRatio = 0.7f, stiffness = 800f),
            label = "MiuixSliderThumbScale"
        )

        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            interactionSource = interactionSource,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            ),
            thumb = {
                Surface(
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = if (isActive) 5.dp else 2.5.dp,
                    border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.08f)),
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer {
                            scaleX = thumbScale
                            scaleY = thumbScale
                        }
                ) {}
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(7.dp),
                    thumbTrackGapSize = 0.dp,
                    drawStopIndicator = null
                )
            }
        )
    } else {
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            interactionSource = interactionSource,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            ),
            thumb = {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    shadowElevation = 2.dp,
                    modifier = Modifier.size(20.dp)
                ) {}
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(5.dp),
                    thumbTrackGapSize = 0.dp,
                    drawStopIndicator = null
                )
            }
        )
    }
}

/**
 * MiuiX 风格厚轨圆钮滑块
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiuixSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    AdaptiveSlider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier
    )
}

/**
 * 全局自适应线性进度条（支持 MiuiX 饱满圆角胶囊质感与平滑过渡）
 */
@Composable
fun AdaptiveLinearProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
) {
    val isMiuix = LocalAppThemeStyle.current == AppThemeStyle.MiuiX
    val animatedProgress by animateFloatAsState(
        targetValue = progress(),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 600f),
        label = "AdaptiveProgress"
    )

    if (isMiuix) {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(trackColor)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress.coerceIn(0f, 1f))
                    .clip(CircleShape)
                    .background(color)
            )
        }
    } else {
        LinearProgressIndicator(
            progress = progress,
            modifier = modifier.clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = trackColor
        )
    }
}

/**
 * MiuiX 悬浮药丸分段选择器（HyperOS 控制中心 / 设置风格）
 */
@Composable
fun <T> MiuixSegmentedPicker(
    items: List<Pair<T, String>>,
    selectedItem: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (key, label) ->
            val isSelected = selectedItem == key
            val isMiuix = LocalAppThemeStyle.current == AppThemeStyle.MiuiX

            val pillBg = when {
                !isSelected -> Color.Transparent
                isMiuix -> MaterialTheme.colorScheme.surface
                else -> MaterialTheme.colorScheme.primary
            }

            val textColor = when {
                isSelected && isMiuix -> MaterialTheme.colorScheme.primary
                isSelected -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .then(
                        if (isSelected && isMiuix) {
                            Modifier.shadow(1.5.dp, RoundedCornerShape(18.dp))
                        } else Modifier
                    )
                    .background(pillBg)
                    .clickable { onSelect(key) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = textColor
                )
            }
        }
    }
}
