package com.wm.wearmail.pairing

import android.graphics.Bitmap
import kotlinx.coroutines.flow.StateFlow

/**
 * 扫码配对状态。
 *
 * @param running 本地配置服务是否在监听
 * @param url 手机端需要访问的地址，形如 `http://192.168.1.23:8080`
 * @param qrBitmap 该地址对应的二维码位图（表盘上直接显示，无需手机装应用）
 * @param lanIp 当前 Wi-Fi 的局域网 IP，null 表示未连接 Wi-Fi
 * @param port 监听端口
 * @param submittedCount 已通过网页提交并成功保存的账户数量
 * @param lastMessage 最近一次成功提示（如「已保存 1 个账户」）
 * @param error 失败原因（端口被占用、未连接 Wi-Fi 等）
 */
data class PairingState(
    val running: Boolean = false,
    val url: String? = null,
    val qrBitmap: Bitmap? = null,
    val lanIp: String? = null,
    val port: Int = DEFAULT_PORT,
    val submittedCount: Int = 0,
    val lastMessage: String? = null,
    val error: String? = null,
) {
    companion object {
        /** 默认监听端口：>1024 无需 root 权限，且冲突概率低 */
        const val DEFAULT_PORT: Int = 8080
    }
}

/**
 * 局域网扫码配置服务。
 *
 * 使用场景：手表上输入邮箱地址/密码/服务器参数极其困难。
 * 本服务在手表上开启一个极简 HTTP 服务器，把访问地址渲染成二维码显示在表盘上；
 * 手机与手表处于同一 Wi-Fi 时，扫码即可在手机浏览器里填写账户信息，
 * 提交后手表端完成加密保存。
 *
 * 安全边界（务必遵守）：
 * - 仅在 [start] 后的会话期内监听，[stop] 必须立即关闭套接字；
 * - 页面提交的凭据只经内存传递给 [com.wm.wearmail.data.repo.AccountRepository] 加密落盘；
 * - 不落任何明文日志；
 * - 提交接口使用一次性会话 token，防止局域网内其它设备误提交。
 */
interface PairingService {

    /** 配对状态（UI 观察） */
    val state: StateFlow<PairingState>

    /**
     * 启动本地配置服务并生成二维码。
     *
     * 若未连接 Wi-Fi（拿不到局域网 IP），返回的 [PairingState.error] 会给出提示，
     * 此时应引导用户先连接 Wi-Fi。
     */
    fun start(): PairingState

    /** 停止服务，释放端口 */
    fun stop()

    /** 生成二维码位图（供 UI 直接显示） */
    fun renderQr(content: String, sizePx: Int): Bitmap?
}
