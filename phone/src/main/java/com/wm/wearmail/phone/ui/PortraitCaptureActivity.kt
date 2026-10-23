package com.wm.wearmail.phone.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import com.journeyapps.barcodescanner.CaptureActivity

/**
 * 强制竖屏的扫码页。
 *
 * 为什么需要自定义 Activity：zxing-android-embedded 的
 * `ScanOptions.setOrientationLocked(true)` 在 `CaptureActivity.onCreate` 里调用的是
 * `SCREEN_ORIENTATION_LOCKED`（**锁定为当前方向**）—— 手机横着拿时扫码页就是横的，
 * 这不符合「扫码界面应固定竖向」的预期（真机反馈）。
 *
 * 这里在 `super.onCreate()` **之后**再请求竖屏，覆盖库内部的「锁定当前方向」；
 * 同时 Manifest 里也声明 `screenOrientation="portrait"` 作为兜底。
 */
class PortraitCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 必须放在 super 之后：库会在 super.onCreate 内部把方向锁定为当前方向
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}
