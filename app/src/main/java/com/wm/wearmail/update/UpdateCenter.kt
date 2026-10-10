package com.wm.wearmail.update

import com.wm.wearmail.core.Logs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 检查更新的应用级协调器（注册在 AppContainer，全进程单例）。
 *
 * 两个触发点：
 * 1. **启动自动检查**（[checkOnStartup]）：由根组合（WearMailNav）在首帧后触发一次；
 *    失败静默（只记日志），发现新版本时进入 [UpdateState.pendingUpdate] 触发弹窗。
 * 2. **设置页手动检查**（[check]）：结果通过返回值反馈（「已是最新」/失败原因），
 *    发现新版本时同样进入弹窗。
 *
 * 为什么启动检查放在 UI 层而不是 Application.onCreate：
 * 冷启动目标 < 2 秒，容器与 Application 刻意不做任何网络请求
 * （见 [com.wm.wearmail.core.WearMailApp] 注释）；首帧渲染后再异步检查，
 * 既不拖慢启动，也满足「开启应用时自动检查」。
 */
class UpdateCenter(
    private val checker: UpdateChecker,
    /** 当前应用版本（弹窗展示「当前 vX」；来源为 BuildConfig.VERSION_NAME） */
    val currentVersion: String,
) {

    /** 一次检查的结局（手动检查的调用方据此给出提示） */
    sealed interface CheckResult {
        /** 发现新版本（同时已写入 [UpdateState.pendingUpdate]，UI 应弹窗） */
        data class UpdateAvailable(val info: UpdateInfo) : CheckResult

        /** 已是最新版本 */
        data object UpToDate : CheckResult

        /** 检查失败（网络、限流、解析等，文案面向用户） */
        data class Failed(val reason: String) : CheckResult
    }

    /**
     * 检查更新的 UI 状态。
     *
     * @param checking 正在检查（设置页按钮置灰 + 文案「检查中…」）
     * @param pendingUpdate 非空表示应展示「发现新版本」弹窗；用户关闭后清空
     */
    data class UpdateState(
        val checking: Boolean = false,
        val pendingUpdate: UpdateInfo? = null,
    )

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** 进程内只做一次启动检查（组合重组、旋转屏幕不会重复触发） */
    private var startupChecked = false

    /** 启动自动检查：仅一次；失败静默（用户在设置页可手动重查） */
    suspend fun checkOnStartup() {
        if (startupChecked) return
        startupChecked = true
        check(manual = false)
    }

    /**
     * 执行一次检查。
     *
     * @param manual 手动检查（设置页）时失败原因会反馈给调用方提示；
     *        自动检查仅消费 [CheckResult.UpdateAvailable]。
     */
    suspend fun check(manual: Boolean): CheckResult {
        if (_state.value.checking) return CheckResult.Failed(MSG_BUSY)
        _state.update { it.copy(checking = true) }
        return try {
            checker.fetchLatest().fold(
                onSuccess = { info ->
                    if (AppVersion.isNewer(info.version, currentVersion)) {
                        _state.update { it.copy(pendingUpdate = info) }
                        Logs.i(TAG, "发现新版本 ${info.version}（当前 $currentVersion）")
                        CheckResult.UpdateAvailable(info)
                    } else {
                        Logs.i(TAG, "已是最新版本（当前 $currentVersion）")
                        CheckResult.UpToDate
                    }
                },
                onFailure = { t ->
                    // 手动检查失败记 warn（用户可感知、需要可排查）；
                    // 启动自动检查失败降为 debug，避免每次冷启动都产生告警噪音
                    if (manual) {
                        Logs.w(TAG, "检查更新失败：${t.javaClass.simpleName}")
                    } else {
                        Logs.d(TAG, "启动检查更新失败：${t.javaClass.simpleName}")
                    }
                    CheckResult.Failed(MSG_FAILED)
                },
            )
        } finally {
            _state.update { it.copy(checking = false) }
        }
    }

    /** 关闭「发现新版本」弹窗；本次会话不再弹出（手动再次检查仍会弹出） */
    fun dismissUpdate() {
        _state.update { it.copy(pendingUpdate = null) }
    }

    companion object {
        private const val TAG = "UpdateCenter"

        /** 上一次检查尚未结束时的防重入提示（正常路径下用户看不到） */
        private const val MSG_BUSY = "检查正在进行，请稍候"

        /** 检查失败的通用用户文案（不暴露 HTTP 状态码等实现细节） */
        private const val MSG_FAILED = "网络不可用或请求被拒绝"
    }
}
