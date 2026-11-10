// 使用桌面 SVG 绘制器显示二维码结果
package io.kaffubox.app

import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter

@Composable
fun SvgPreview(svg: String, modifier: Modifier) {
    val density = LocalDensity.current
    val painter = remember(svg, density) { loadSvgPainter(svg.byteInputStream(), density) }
    Image(painter, "生成的二维码", modifier)
}
