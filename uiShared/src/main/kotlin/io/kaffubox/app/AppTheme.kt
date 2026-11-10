// 定义桌面应用的浅深色主题、圆角面板和公共表单组件
package io.kaffubox.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import io.kaffubox.core.Workbench

private val LightPalette = lightColorScheme(
    primary = Color(0xFF316C82), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCECF3), onPrimaryContainer = Color(0xFF21556A),
    secondary = Color(0xFF65717F), secondaryContainer = Color(0xFFEDF1F4),
    background = Color(0xFFF3F4F7), onBackground = Color(0xFF202A35),
    surface = Color.White, onSurface = Color(0xFF202A35),
    surfaceVariant = Color(0xFFF2F4F7), onSurfaceVariant = Color(0xFF65717F),
    outline = Color(0xFFD5DBE2), outlineVariant = Color(0xFFE1E5E9),
    error = Color(0xFFA4474D), errorContainer = Color(0xFFF9EDED)
)
private val DarkPalette = darkColorScheme(
    primary = Color(0xFF9BCFE3), onPrimary = Color(0xFF102F3D),
    primaryContainer = Color(0xFF294653), onPrimaryContainer = Color(0xFFB9E3F3),
    secondary = Color(0xFFACB5C5), secondaryContainer = Color(0xFF303440),
    background = Color(0xFF191B24), onBackground = Color(0xFFEDF0F6),
    surface = Color(0xFF262933), onSurface = Color(0xFFEDF0F6),
    surfaceVariant = Color(0xFF303440), onSurfaceVariant = Color(0xFFACB5C5),
    outline = Color(0xFF515766), outlineVariant = Color(0xFF3B404D),
    error = Color(0xFFEDB1B5), errorContainer = Color(0xFF483036)
)

@Composable
fun KaffuTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val base = Typography()
    MaterialTheme(
        colorScheme = if (dark) DarkPalette else LightPalette,
        typography = base.copy(
            bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.SansSerif, fontSize = 14.sp),
            bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.SansSerif, fontSize = 13.sp),
            bodySmall = base.bodySmall.copy(fontFamily = FontFamily.SansSerif, fontSize = 12.sp),
            titleLarge = base.titleLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 23.sp),
            titleMedium = base.titleMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp),
            titleSmall = base.titleSmall.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp),
            labelLarge = base.labelLarge.copy(fontFamily = FontFamily.SansSerif, fontSize = 13.sp)
        ),
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(22.dp)),
        content = content
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, title: String? = null, spacing: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(spacing)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            content()
        }
    }
}

@Composable
fun PageHeader(title: String, back: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        back?.let { IconButton(onClick = it) { Icon(Icons.Outlined.ArrowBack, "返回") } }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
fun ChoiceField(label: String, value: String, choices: List<Pair<String, String>>, onChange: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp), enabled = enabled, shape = RoundedCornerShape(11.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Text(choices.firstOrNull { it.first == value }?.second ?: value, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                Icon(Icons.Outlined.ArrowDropDown, null, modifier = Modifier.size(19.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 390.dp)) {
                choices.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onChange(id) }, leadingIcon = if (id == value) ({ Icon(Icons.Outlined.Check, null, Modifier.size(17.dp)) }) else null) }
            }
        }
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = onChange)
        Text(label, Modifier.weight(1f))
    }
}

@Composable
fun InlineError(text: String?) {
    if (!text.isNullOrBlank()) Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

fun workbenchIcon(workbench: Workbench): ImageVector = when (workbench) {
    Workbench.Format -> Icons.Outlined.SwapHoriz
    Workbench.Media -> Icons.Outlined.Movie
    Workbench.Image -> Icons.Outlined.Image
    Workbench.File -> Icons.Outlined.FolderOpen
    Workbench.Document -> Icons.Outlined.Description
    Workbench.Text -> Icons.Outlined.Notes
    Workbench.Developer -> Icons.Outlined.Code
    Workbench.Network -> Icons.Outlined.Lan
    Workbench.Device -> Icons.Outlined.Computer
    Workbench.Transfer -> Icons.Outlined.Devices
    Workbench.Science -> Icons.Outlined.Calculate
    Workbench.Daily -> Icons.Outlined.Timer
}
