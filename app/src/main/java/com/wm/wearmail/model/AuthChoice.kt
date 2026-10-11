package com.wm.wearmail.model

/**
 * 「登录方式」的 UI 选项（纯函数，可单元测试）。
 *
 * 与 [AuthType] 的关系：对用户而言「登录密码」与「应用专用密码」是同一件事
 * —— 都是填一串密码/授权码，区别只存在于各家服务商的文档里。分成两个按钮
 * 只会造成选择困惑，因此 UI 只呈现两项：**密码/授权码**
 * （合并 PASSWORD + APP_PASSWORD）与 **OAuth2 令牌**。
 *
 * 底层存储仍使用 [AuthType]（数据库枚举名不变，兼容存量数据）；
 * 选择「密码/授权码」时落到 [AuthType.APP_PASSWORD]
 * （第三方客户端最常见的形态，也是表单默认值）。
 *
 * 「添加账户」页与手机扫码页（[com.wm.wearmail.pairing.PairingPage]）
 * 共用本函数，保证两端呈现完全一致。
 */
data class AuthChoice(
    /** 按钮/选项的展示文案 */
    val label: String,
    /** 选中该项时写入表单的底层认证方式 */
    val authType: AuthType,
) {
    /**
     * 该选项是否对应 [current] 这一底层认证方式。
     * 「密码/授权码」同时对应 PASSWORD 与 APP_PASSWORD（合并展示）。
     */
    fun matches(current: AuthType): Boolean =
        current == authType ||
            (authType == AuthType.APP_PASSWORD && current == AuthType.PASSWORD)

    companion object {
        /** 密码/授权码（合并 PASSWORD 与 APP_PASSWORD，底层落到 APP_PASSWORD） */
        private val PASSWORD_CHOICE = AuthChoice("密码/授权码", AuthType.APP_PASSWORD)

        /** OAuth2 令牌（沿用枚举自身的展示文案） */
        private val OAUTH2_CHOICE = AuthChoice(AuthType.OAUTH2.label, AuthType.OAUTH2)

        /**
         * 按服务商允许的 [AuthType] 列表生成 UI 选项。
         *
         * 顺序稳定：密码在前、OAuth2 在后；两个密码枚举只折叠为一项。
         */
        fun forTypes(types: List<AuthType>): List<AuthChoice> {
            val choices = mutableListOf<AuthChoice>()
            if (types.any { it == AuthType.PASSWORD || it == AuthType.APP_PASSWORD }) {
                choices += PASSWORD_CHOICE
            }
            if (AuthType.OAUTH2 in types) {
                choices += OAUTH2_CHOICE
            }
            return choices
        }
    }
}
