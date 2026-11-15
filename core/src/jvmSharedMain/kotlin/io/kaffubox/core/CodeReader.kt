// 为图片识码、剪贴板识码和相机预览帧复用同一个本地条码解码器
package io.kaffubox.core

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import kotlinx.serialization.json.JsonObject

object CodeReader {
    fun read(image: RasterImage): JsonObject = decode(RGBLuminanceSource(image.width, image.height, image.pixels))
    fun readLuminance(bytes: ByteArray, width: Int, height: Int): JsonObject = decode(PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false))
    private fun decode(source: LuminanceSource): JsonObject {
        try {
            val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8"))
            return jsonObject("text" to result.text, "format" to result.barcodeFormat.name)
        } catch (_: ReaderException) { throw ToolFailure("codeNotFound") }
    }
}
