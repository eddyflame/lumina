package org.lumina.reader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.lumina.reader.data.preferences.AppThemeMode
import org.lumina.reader.data.preferences.DarkThemeStyle
import org.lumina.reader.data.preferences.ThemeSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsBottomSheet(
    themeSettings: ThemeSettings,
    onThemeModeSelected: (AppThemeMode) -> Unit,
    onDarkThemeStyleSelected: (DarkThemeStyle) -> Unit,
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isSystemDark = isSystemInDarkTheme()
    val isEffectivelyDark = when (themeSettings.themeMode) {
        AppThemeMode.SYSTEM -> isSystemDark
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp)
        ) {
            // 标题栏
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 18.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = "外观与个性化",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "自定义 Lumina 界面色彩与夜间质感",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(20.dp))

            // 主题模式选择
            Text(
                text = "主题模式",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemeModeOptionCard(
                    title = "跟随系统",
                    subtitle = if (isSystemDark) "当前系统处于深色模式" else "当前系统处于浅色模式",
                    icon = Icons.Default.BrightnessAuto,
                    selected = themeSettings.themeMode == AppThemeMode.SYSTEM,
                    onClick = { onThemeModeSelected(AppThemeMode.SYSTEM) }
                )

                ThemeModeOptionCard(
                    title = "日间浅色",
                    subtitle = "经典清爽明亮界面，通透舒适",
                    icon = Icons.Default.LightMode,
                    selected = themeSettings.themeMode == AppThemeMode.LIGHT,
                    onClick = { onThemeModeSelected(AppThemeMode.LIGHT) }
                )

                ThemeModeOptionCard(
                    title = "夜间暗黑",
                    subtitle = "沉浸护眼深色界面，夜间无眩光",
                    icon = Icons.Default.DarkMode,
                    selected = themeSettings.themeMode == AppThemeMode.DARK,
                    onClick = { onThemeModeSelected(AppThemeMode.DARK) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 深色质感风格细分 (在深色激活或系统为深色时提供选择)
            Text(
                text = "深色质感风格",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (isEffectivelyDark) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DarkStyleOptionCard(
                    title = "板岩深灰",
                    subtitle = "柔和护眼·质感层次",
                    previewColor = Color(0xFF0F172A),
                    selected = themeSettings.darkThemeStyle == DarkThemeStyle.SLATE,
                    enabled = true,
                    modifier = Modifier.weight(1f),
                    onClick = { onDarkThemeStyleSelected(DarkThemeStyle.SLATE) }
                )

                DarkStyleOptionCard(
                    title = "极致纯黑",
                    subtitle = "AMOLED 纯黑省电",
                    previewColor = Color(0xFF000000),
                    selected = themeSettings.darkThemeStyle == DarkThemeStyle.AMOLED,
                    enabled = true,
                    modifier = Modifier.weight(1f),
                    onClick = { onDarkThemeStyleSelected(DarkThemeStyle.AMOLED) }
                )
            }
        }
    }
}

@Composable
private fun ThemeModeOptionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = if (selected) {
            borderStrokeSelected()
        } else {
            borderStrokeUnselected()
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
private fun DarkStyleOptionCard(
    title: String,
    subtitle: String,
    previewColor: Color,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = if (selected) {
            borderStrokeSelected()
        } else {
            borderStrokeUnselected()
        },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(previewColor)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )

                if (selected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "已选",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun borderStrokeSelected() = androidx.compose.foundation.BorderStroke(
    width = 1.5.dp,
    color = MaterialTheme.colorScheme.primary
)

@Composable
private fun borderStrokeUnselected() = androidx.compose.foundation.BorderStroke(
    width = 1.dp,
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
)
