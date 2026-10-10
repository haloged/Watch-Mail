package com.wm.wearmail.data.db

/**
 * 正文 HTML 缓存的三态。
 *
 * **为什么必须是三态**（真实缺陷，值得记录）：
 * 只有「有 HTML / 没有 HTML」两态时，「没问过服务器」与「问过了但确实没有」会被混成同一个
 * `null`，于是两件事同时出错：
 * 1. 纯文本邮件（或 HTML 超过保留上限被丢弃的邮件）每次打开详情都被判为「缓存缺少 HTML」，
 *    白白重新下载一整封正文；
 * 2. 离线时这些邮件连已缓存的纯文本都打不开 —— 被判为「缓存不完整」后走了网络分支，
 *    直接报「网络不可用」。
 *
 * 存储约定（`email_bodies.body_html` 列）：
 * - SQL NULL → [UNKNOWN]：没问过（例如缓存时「渲染 HTML」开关是关的）
 * - 空串 `""` → [NONE]：问过了，这封邮件没有可渲染的 HTML
 * - 非空文本 → [AVAILABLE]：已缓存原始 HTML
 *
 * 纯数据 + 纯函数、不依赖任何 Android API，因此可在纯 JVM 单测里完整覆盖。
 */
enum class HtmlCacheState {

    /** 没问过服务器（本地可能只有纯文本） */
    UNKNOWN,

    /** 问过，但这封邮件没有可渲染的 HTML（纯文本邮件，或 HTML 超过保留上限） */
    NONE,

    /** 已缓存可渲染的 HTML */
    AVAILABLE,
    ;

    /** 是否已有确定结论：为 true 时**不必**再为了 HTML 联网（[UNKNOWN] 才需要补取一次） */
    val resolved: Boolean
        get() = this != UNKNOWN

    companion object {

        /** `body_html` 列原值 → 三态 */
        fun fromColumnValue(raw: String?): HtmlCacheState = when {
            raw == null -> UNKNOWN
            raw.isEmpty() -> NONE
            else -> AVAILABLE
        }

        /**
         * 一次抓取的结果 → 要写入 `body_html` 列的值。
         *
         * @param html 抓到的 HTML（null 表示没拿到）
         * @param htmlRequested 本次是否确实请求了 HTML：true 且没拿到时记为 [NONE]，
         *   否则保持 [UNKNOWN]（下次还可以补取）
         */
        fun toColumnValue(html: String?, htmlRequested: Boolean): String? = when {
            !html.isNullOrEmpty() -> html
            htmlRequested -> NONE_COLUMN
            else -> null
        }

        /** 「问过但没有 HTML」的列值：空串，与 SQL NULL（没问过）严格区分 */
        const val NONE_COLUMN: String = ""
    }
}
