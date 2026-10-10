package com.wm.wearmail.update

/**
 * 最新 Release 查询器。
 *
 * 抽成接口便于测试替身，也为将来更换数据源（镜像、自建 API）留出余地。
 * 当前唯一实现：[GitHubUpdateChecker]。
 */
interface UpdateChecker {
    /** 查询最新 Release；网络与解析失败返回 [Result.failure]（不向外抛异常） */
    suspend fun fetchLatest(): Result<UpdateInfo>
}
