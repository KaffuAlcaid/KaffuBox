// 提供桌面平台的界面适配实现
package io.kaffubox.app

import androidx.compose.ui.graphics.*
import io.kaffubox.core.RasterImage
import java.awt.image.BufferedImage
import kotlin.math.*

fun RasterImage.previewBitmap(maxSide: Int = 1200): ImageBitmap {
    val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    try {
        buffered.setRGB(0, 0, width, height, pixels, 0, width)
        val scale = min(1.0, maxSide.toDouble() / max(width, height))
        if (scale == 1.0) return buffered.toComposeImageBitmap()
        val small = BufferedImage(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()), BufferedImage.TYPE_INT_ARGB)
        try { small.createGraphics().apply { setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR); drawImage(buffered, 0, 0, small.width, small.height, null); dispose() }; return small.toComposeImageBitmap() }
        finally { small.flush() }
    } finally { buffered.flush() }
}
