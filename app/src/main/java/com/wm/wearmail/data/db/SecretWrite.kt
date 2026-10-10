package com.wm.wearmail.data.db

/**
 * 账户密文列（`password_enc` / `oauth_token_enc`）的写入意图。
 *
 * **为什么需要专门的类型**（真实缺陷，值得记录）：
 * 早期实现把「不改动凭据」和「清空凭据」都用 `null` 表达，但 `ContentValues` 里的 null
 * 就是「写成 SQL NULL」—— 于是「只改别名」的重命名流程把 `password_enc` 与
 * `oauth_token_enc` 一起清空了，用户表现为「改完名字就收不到信，提示要重新配置账户」。
 * 把两种语义在**类型上**分开，是这条契约唯一不会被误读的表达方式。
 *
 * 纯数据 + 纯函数、不依赖任何 Android API，因此可在纯 JVM 单测里完整覆盖 ——
 * DAO 本身依赖 `android.database.*`，单测覆盖不到，这正是上面那个缺陷漏网的原因。
 */
sealed interface SecretWrite {

    /** 保持数据库中的原值（例如「只改别名」） */
    data object Keep : SecretWrite

    /** 写入新值；[value] 为 null 表示**显式清空**该列（例如密码登录改成了 OAuth2） */
    data class Set(val value: String?) : SecretWrite

    companion object {
        /**
         * 由「本次是否更新凭据」+「已加密的新值」推导写入意图。
         *
         * [update] 为 false 时一律 [Keep]：仓储层「`secrets == null` 表示不改动凭据」的契约
         * 就是靠这里落地的，任何调用方都不该再有机会把 null 误当成清空。
         */
        fun of(update: Boolean, value: String?): SecretWrite = if (update) Set(value) else Keep
    }
}
