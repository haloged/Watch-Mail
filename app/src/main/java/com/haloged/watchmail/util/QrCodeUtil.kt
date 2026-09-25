package com.haloged.watchmail.util

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码生成工具
 * 使用 ZXing 生成配对用二维码（内容为本地 Web 服务地址 + 配对码）
 */
object QrCodeUtil {

    private const val TAG = "QrCodeUtil"

    /**
     * 生成二维码 Bitmap
     *
     * @param content 二维码内容（URL 字符串）
     * @param sizePx 输出位图边长（像素），正方形
     * @param quietZone 二维码静默区（模块数），推荐 1-2
     * @return 黑白二维码 Bitmap；生成失败返回 null
     */
    fun generateQrBitmap(
        content: String,
        sizePx: Int = 400,
        quietZone: Int = 1
    ): Bitmap? {
        return try {
            val hints = mapOf(
                // 纠错等级 M：容错率约 15%，适合屏幕展示（会被反光/角度影响）
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to quietZone,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )

            val bitMatrix = QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx,
                hints
            )

            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }

            Log.d(TAG, "二维码生成成功: contentLength=${content.length}, size=${sizePx}px")
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "二维码生成失败", e)
            null
        }
    }
}
