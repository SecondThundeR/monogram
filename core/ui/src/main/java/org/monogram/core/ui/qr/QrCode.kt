package org.monogram.core.ui.qr

import android.graphics.Bitmap
import uniffi.monogram_mtproto.decodeQrCode
import uniffi.monogram_mtproto.generateQrCode

object QrCode {
    fun render(text: String, size: Int, foreground: Int, background: Int): Bitmap {
        require(size in 1..2048)
        val matrix = generateQrCode(text, size.toUInt())
        val pixels =
            IntArray(matrix.luma.size) { index ->
                if (matrix.luma[index] == 0.toByte()) foreground else background
            }
        val side = matrix.size.toInt()
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }

    fun decode(luma: ByteArray, width: Int, height: Int): String? {
        require(width in 1..2048 && height in 1..2048 && luma.size == width * height)
        return decodeQrCode(luma, width.toUInt(), height.toUInt())
    }
}
