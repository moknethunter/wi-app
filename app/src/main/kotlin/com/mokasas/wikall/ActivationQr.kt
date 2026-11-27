package com.mokasas.wikall

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

private const val ACTIVATION_QR_PREFIX = "wikall:activate:1:"

internal fun activationQrText(bytes: ByteArray): String {
    require(bytes.size in 1..FILE_LIMIT)
    return ACTIVATION_QR_PREFIX + bytes.decodeToString(throwOnInvalidSequence = true)
}

internal fun activationQrBytes(text: String): ByteArray {
    require(text.startsWith(ACTIVATION_QR_PREFIX) && text.length <= FILE_LIMIT + ACTIVATION_QR_PREFIX.length)
    return text.removePrefix(ACTIVATION_QR_PREFIX).encodeToByteArray().also {
        require(it.size in 1..FILE_LIMIT)
    }
}

internal fun activationQrBitmap(bytes: ByteArray): Bitmap {
    val matrix = QRCodeWriter().encode(activationQrText(bytes), BarcodeFormat.QR_CODE, 640, 640)
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix[index % matrix.width, index / matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt()
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}
