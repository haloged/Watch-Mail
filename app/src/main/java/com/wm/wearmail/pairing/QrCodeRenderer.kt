package com.wm.wearmail.pairing

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.wm.wearmail.core.Logs

/**
 * 二维码生成器（ZXing）。
 *
 * 参数取舍：
 * - 纠错级别 M（约 15%）：手机隔着几厘米扫表盘，M 在亮度一般的手表屏幕上足够稳定，
 *   又不会像 H 那样把模块数放大导致低分辨率下糊成一团；
 * - 静默区 MARGIN=1：手表屏幕宝贵，1 个模块的白边已能满足多数扫码实现；
 * - 字符集显式指定 UTF-8，避免默认字符集在不同 ROM 上不一致。
 */
object QrCodeRenderer {

    private const val TAG = "QrCodeRenderer"

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** 静默区宽度（模块数），1 已是多数扫码实现可接受的最小值 */
    private const val MARGIN = 1

    /**
     * 生成二维码位图。
     *
     * @param content 二维码内容（配对地址）
     * @param sizePx 输出位图边长（像素）
     * @return 生成失败（内容过长、尺寸非法等）时返回 null，调用方需降级提示
     */
    fun render(content: String, sizePx: Int): Bitmap? {
        if (content.isEmpty() || sizePx <= 0) {
            Logs.w(TAG, "二维码参数非法：内容长度=${content.length} 尺寸=$sizePx")
            return null
        }
        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to MARGIN,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val matrix = QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx,
                hints,
            )

            // 一次性写入像素数组再建位图：比逐像素 setPixel 快一个数量级
            val pixels = IntArray(sizePx * sizePx)
            for (y in 0 until sizePx) {
                val rowOffset = y * sizePx
                for (x in 0 until sizePx) {
                    pixels[rowOffset + x] = if (matrix.get(x, y)) BLACK else WHITE
                }
            }
            Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            // WriterException（内容过长）与 OOM 都在这里兜底
            Logs.w(TAG, "二维码生成失败：${t.javaClass.simpleName}", t)
            null
        }
    }
}
