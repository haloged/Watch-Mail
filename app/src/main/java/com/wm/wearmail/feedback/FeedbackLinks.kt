package com.wm.wearmail.feedback

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 反馈中心的外部链接。
 *
 * 两条反馈路径的取舍（也是界面上的提示文案）：
 * - GitHub Issues：**解决更快**（开发者直接看到），但国内网络可能打不开或很慢；
 * - 反馈问卷（维格表分享页）：网络稳定，但**解决更慢**（约两个版本内集中处理）。
 */
object FeedbackLinks {

    /** GitHub 新建 Issue 页 */
    const val GITHUB_NEW_ISSUE: String = "https://github.com/haloged/Watch-Mail/issues/new"

    /** 反馈问卷（维格表分享页） */
    const val FORM_URL: String = "https://vika.cn/share/shrqKZoCV3CGi7fMfHP6P"
}

/**
 * 反馈日志号（纯函数，可单元测试）。
 *
 * 格式：`MMdd-HHmm-XXX`（月日 + 时分 + 3 位序号，共 **13 字符**）。
 * 演进过程（都来自真机反馈「手表显示不全」）：
 * - `WM-yyyyMMdd-HHmmss-XXX`（24 字符）→ 18 字符 → **13 字符**；
 * - 手表圆屏上该行的可用宽度约 130–150dp，18 字符即便降到 14sp 也需约 165dp，
 *   必然被裁切；13 字符在 14sp 下约 119dp，可以完整显示；
 * - 去掉年份与 `WM-` 前缀（反馈表里另有创建时间可定位年份），同分钟靠 3 位序号仍不重复。
 *
 * 手表上只显示这个号，用户把它填进反馈表单/Issue，开发者即可在维格表里按号查到日志文件。
 */
object FeedbackLogId {

    fun generate(
        now: Long = System.currentTimeMillis(),
        random: Int = (0..999).random(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val stamp = SimpleDateFormat("MMdd-HHmm", Locale.US)
            .apply { this.timeZone = timeZone }
            .format(Date(now))
        val suffix = (random.coerceIn(0, 999)).toString().padStart(3, '0')
        return "$stamp-$suffix"
    }
}
