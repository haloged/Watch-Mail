package com.haloged.watchmail.data.repository

import android.content.Context
import android.util.Log
import com.haloged.watchmail.data.local.WatchMailDatabase
import com.haloged.watchmail.data.local.dao.AccountDao
import com.haloged.watchmail.data.local.dao.DraftDao
import com.haloged.watchmail.data.local.dao.EmailBodyDao
import com.haloged.watchmail.data.local.dao.EmailDao
import com.haloged.watchmail.data.local.entity.*
import com.haloged.watchmail.data.remote.imap.BodyDownloadResult
import com.haloged.watchmail.data.remote.imap.ImapSyncManager
import com.haloged.watchmail.data.remote.imap.ImapSyncResult
import com.haloged.watchmail.data.remote.smtp.SmtpSendManager
import com.haloged.watchmail.data.remote.smtp.SmtpSendResult
import com.haloged.watchmail.util.EncryptionUtil
import kotlinx.coroutines.flow.Flow

/**
 * 邮箱Repository
 * 协调本地数据库和远程邮件服务器的数据操作
 */
class EmailRepository(context: Context) {
    
    companion object {
        private const val TAG = "EmailRepository"
        private const val MAX_EMAILS_CACHE = 500
        private const val MAX_BODIES_CACHE = 50
        private const val LRU_CLEANUP_COUNT = 50
        
        @Volatile
        private var INSTANCE: EmailRepository? = null
        
        fun getInstance(context: Context): EmailRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = EmailRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
    
    private val database = WatchMailDatabase.getInstance(context)
    private val accountDao: AccountDao = database.accountDao()
    private val emailDao: EmailDao = database.emailDao()
    private val emailBodyDao: EmailBodyDao = database.emailBodyDao()
    private val draftDao: DraftDao = database.draftDao()
    
    private val imapManager = ImapSyncManager()
    private val smtpManager = SmtpSendManager()
    
    // ==================== 账户管理 ====================
    
    /**
     * 获取所有账户（Flow）
     */
    fun getAllAccountsFlow(): Flow<List<AccountEntity>> {
        return accountDao.getAllAccountsFlow()
    }
    
    /**
     * 获取所有启用的账户
     */
    suspend fun getEnabledAccounts(): List<AccountEntity> {
        return accountDao.getEnabledAccounts()
    }
    
    /**
     * 根据ID获取账户
     */
    suspend fun getAccountById(accountId: Long): AccountEntity? {
        return accountDao.getAccountById(accountId)
    }
    
    /**
     * 添加账户
     */
    suspend fun addAccount(
        email: String,
        password: String,
        alias: String,
        imapHost: String,
        imapPort: Int,
        imapEncryption: EncryptionType,
        smtpHost: String,
        smtpPort: Int,
        smtpEncryption: EncryptionType,
        color: Long
    ): Long {
        // 加密密码
        val encryptedPassword = EncryptionUtil.encrypt(password)
        
        val account = AccountEntity(
            email = email,
            encryptedPassword = encryptedPassword,
            alias = alias.ifBlank { email.substringBefore("@") },
            imapHost = imapHost,
            imapPort = imapPort,
            imapEncryption = imapEncryption,
            smtpHost = smtpHost,
            smtpPort = smtpPort,
            smtpEncryption = smtpEncryption,
            color = color
        )
        
        return accountDao.insertAccount(account)
    }
    
    /**
     * 更新账户
     */
    suspend fun updateAccount(account: AccountEntity) {
        accountDao.updateAccount(account)
    }
    
    /**
     * 更新账户密码（AES-GCM 加密后落库，绝不明文存储）
     */
    suspend fun updateAccountPassword(accountId: Long, newPassword: String) {
        val account = accountDao.getAccountById(accountId) ?: return
        val encrypted = EncryptionUtil.encrypt(newPassword)
        accountDao.updateAccount(account.copy(encryptedPassword = encrypted))
        Log.d(TAG, "账户密码已更新: ID=$accountId")
    }
    
    /**
     * 删除账户
     */
    suspend fun deleteAccount(accountId: Long) {
        // 删除账户相关的所有邮件
        emailDao.deleteEmailsByAccount(accountId)
        // 删除账户
        accountDao.deleteAccountById(accountId)
    }
    
    /**
     * 测试账户连接（IMAP + SMTP 双通道）
     * 只有收发都通才算配置正确
     */
    suspend fun testAccountConnection(account: AccountEntity): Boolean {
        val imapOk = imapManager.testConnection(account)
        if (!imapOk) {
            Log.w(TAG, "IMAP连接测试失败: ${account.email}")
            return false
        }
        val smtpOk = smtpManager.testConnection(account)
        if (!smtpOk) {
            Log.w(TAG, "SMTP连接测试失败: ${account.email}")
        }
        return smtpOk
    }
    
    /**
     * 获取单封邮件（Flow，供详情页观察）
     */
    fun getEmailByIdFlow(emailId: Long): Flow<EmailEntity?> {
        return emailDao.getEmailByIdFlow(emailId)
    }
    
    /**
     * 获取单封邮件
     */
    suspend fun getEmailById(emailId: Long): EmailEntity? {
        return emailDao.getEmailById(emailId)
    }
    
    /**
     * 设置每账户通知开关
     */
    suspend fun setAccountNotificationEnabled(accountId: Long, enabled: Boolean) {
        accountDao.updateNotificationEnabled(accountId, enabled)
    }
    
    /**
     * 设置每账户启用状态
     */
    suspend fun setAccountEnabled(accountId: Long, enabled: Boolean) {
        accountDao.updateAccountEnabled(accountId, enabled)
    }
    
    // ==================== 邮件同步 ====================
    
    /**
     * 获取所有邮件（Flow，按时间倒序）
     */
    fun getAllEmailsFlow(): Flow<List<EmailEntity>> {
        return emailDao.getAllEmailsFlow()
    }
    
    /**
     * 获取指定账户的邮件（Flow）
     */
    fun getEmailsByAccountFlow(accountId: Long): Flow<List<EmailEntity>> {
        return emailDao.getEmailsByAccountFlow(accountId)
    }
    
    /**
     * 同步所有账户的邮件
     * @return Map<账户ID, 同步结果>
     */
    suspend fun syncAllAccounts(): Map<Long, SyncResult> {
        val results = mutableMapOf<Long, SyncResult>()
        val accounts = try {
            accountDao.getEnabledAccounts()
        } catch (t: Throwable) {
            Log.e(TAG, "读取账户列表失败", t)
            return results
        }
        
        for (account in accounts) {
            try {
                val result = syncAccount(account)
                results[account.id] = result
                
                // 更新最后同步时间
                accountDao.updateLastSyncTime(account.id, System.currentTimeMillis())
            } catch (t: Throwable) {
                // 用 Throwable 兜底：单个账户出问题不能让整体同步闪退
                Log.e(TAG, "同步账户失败: ${account.email}", t)
                results[account.id] = SyncResult.Error(t.message ?: t.javaClass.simpleName)
            }
        }
        
        // 清理过多的缓存
        try {
            cleanupCache()
        } catch (t: Throwable) {
            Log.w(TAG, "缓存清理失败（不影响同步结果）", t)
        }
        
        return results
    }
    
    /**
     * 同步单个账户的邮件
     * 精确区分"新增"与"已存在"，仅新增邮件用于通知
     */
    suspend fun syncAccount(account: AccountEntity): SyncResult {
        // 获取最新的UID用于增量同步
        val latestUid = emailDao.getLatestUid(account.id)
        
        return when (val result = imapManager.syncEmails(account, latestUid)) {
            is ImapSyncResult.Success -> {
                if (result.emails.isNotEmpty()) {
                    // 精确计算真正新增的邮件（REPLACE 会覆盖旧记录，不能直接用 size 计数）
                    val fetchedUids = result.emails.map { it.uid }
                    // ⚠ 空列表时 Room 会生成 "IN ()" 造成 SQL 语法错误，必须先判空
                    val existingUids = if (fetchedUids.isEmpty()) {
                        emptySet()
                    } else {
                        emailDao.getExistingUids(account.id, fetchedUids).toSet()
                    }
                    val newEmails = result.emails.filter { it.uid !in existingUids }
                    
                    // 插入/更新邮件元数据
                    emailDao.insertEmails(result.emails)
                    
                    Log.d(TAG, "同步成功: ${account.email}, 拉取${result.emails.size}封, 新增${newEmails.size}封")
                    SyncResult.Success(newEmails.size, newEmails)
                } else {
                    SyncResult.Success(0, emptyList())
                }
            }
            is ImapSyncResult.Error -> {
                Log.e(TAG, "同步失败: ${account.email} - ${result.message}")
                SyncResult.Error(result.message)
            }
        }
    }
    
    /**
     * 下载邮件正文
     */
    suspend fun downloadEmailBody(emailId: Long): Boolean {
        val email = emailDao.getEmailById(emailId) ?: return false
        val account = accountDao.getAccountById(email.accountId) ?: return false
        
        return when (val result = imapManager.downloadBody(account, email.uid)) {
            is BodyDownloadResult.Success -> {
                // 保存正文
                val body = EmailBodyEntity(
                    emailId = emailId,
                    bodyText = result.bodyText,
                    bodyHtml = result.bodyHtml
                )
                emailBodyDao.insertBody(body)
                
                // 更新邮件的正文下载状态
                emailDao.updateBodyDownloaded(emailId, true)
                
                // 清理过多的正文缓存
                cleanupBodyCache()
                
                true
            }
            is BodyDownloadResult.Error -> {
                Log.e(TAG, "下载正文失败: $emailId - ${result.message}")
                false
            }
        }
    }
    
    /**
     * 获取邮件正文（读取时刷新 LRU 时间戳）
     */
    suspend fun getEmailBody(emailId: Long): EmailBodyEntity? {
        val body = emailBodyDao.getBodyByEmailId(emailId)
        if (body != null) {
            // LRU 触达：刷新时间戳，避免被淘汰
            emailBodyDao.touchBody(emailId)
        }
        return body
    }
    
    /**
     * 获取单封邮件时刷新 LRU 时间戳
     */
    suspend fun getEmailByIdAndTouch(emailId: Long): EmailEntity? {
        val email = emailDao.getEmailById(emailId)
        if (email != null) {
            emailDao.touchCachedAt(emailId)
        }
        return email
    }
    
    /**
     * 标记邮件为已读
     */
    suspend fun markEmailAsRead(emailId: Long, isRead: Boolean = true): Boolean {
        val email = emailDao.getEmailById(emailId) ?: return false
        val account = accountDao.getAccountById(email.accountId) ?: return false
        
        // 本地更新
        emailDao.markAsRead(emailId, isRead)
        
        // 远程同步
        return imapManager.markAsRead(account, email.uid, isRead)
    }
    
    /**
     * 删除邮件
     */
    suspend fun deleteEmail(emailId: Long): Boolean {
        val email = emailDao.getEmailById(emailId) ?: return false
        val account = accountDao.getAccountById(email.accountId) ?: return false
        
        // 远程删除
        val remoteDeleted = imapManager.deleteEmail(account, email.uid)
        
        // 本地删除（无论远程是否成功都删除本地）
        emailDao.deleteEmailById(emailId)
        
        return remoteDeleted
    }
    
    // ==================== 邮件发送 ====================
    
    /**
     * 发送邮件
     */
    suspend fun sendEmail(
        accountId: Long,
        toAddress: String,
        subject: String,
        body: String
    ): SmtpSendResult {
        val account = accountDao.getAccountById(accountId)
            ?: return SmtpSendResult.Error("账户不存在")
        
        return smtpManager.sendEmail(account, toAddress, subject, body)
    }
    
    /**
     * 保存草稿
     */
    suspend fun saveDraft(
        accountId: Long,
        toAddress: String,
        subject: String,
        body: String
    ): Long {
        val draft = DraftEntity(
            accountId = accountId,
            toAddress = toAddress,
            subject = subject,
            body = body
        )
        return draftDao.insertDraft(draft)
    }
    
    /**
     * 获取所有草稿
     */
    fun getAllDraftsFlow(): Flow<List<DraftEntity>> {
        return draftDao.getAllDraftsFlow()
    }
    
    /**
     * 删除草稿
     */
    suspend fun deleteDraft(draftId: Long) {
        draftDao.deleteDraftById(draftId)
    }
    
    /**
     * 发送待发送的草稿
     */
    suspend fun sendPendingDrafts(): Int {
        val pendingDrafts = draftDao.getPendingDrafts()
        var sentCount = 0
        
        for (draft in pendingDrafts) {
            try {
                // 标记为发送中
                draftDao.updateSendingStatus(draft.id, true)
                
                val result = sendEmail(
                    draft.accountId,
                    draft.toAddress,
                    draft.subject,
                    draft.body
                )
                
                when (result) {
                    is SmtpSendResult.Success -> {
                        // 发送成功，删除草稿
                        draftDao.deleteDraft(draft)
                        sentCount++
                    }
                    is SmtpSendResult.Error -> {
                        // 发送失败，更新失败信息
                        draftDao.updateFailInfo(
                            draft.id,
                            draft.failCount + 1,
                            result.message
                        )
                        draftDao.updateSendingStatus(draft.id, false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "发送草稿失败: ${draft.id}", e)
                draftDao.updateFailInfo(draft.id, draft.failCount + 1, e.message)
                draftDao.updateSendingStatus(draft.id, false)
            }
        }
        
        return sentCount
    }
    
    // ==================== 搜索 ====================
    
    /**
     * 搜索邮件
     */
    fun searchEmails(query: String): Flow<List<EmailEntity>> {
        return emailDao.searchEmailsFlow(query)
    }
    
    // ==================== 统计 ====================
    
    /**
     * 获取未读邮件数量
     */
    suspend fun getUnreadCount(): Int {
        return emailDao.getUnreadCount()
    }
    
    /**
     * 获取指定账户的未读邮件数量
     */
    suspend fun getUnreadCountByAccount(accountId: Long): Int {
        return emailDao.getUnreadCountByAccount(accountId)
    }
    
    // ==================== 缓存管理 ====================
    
    /**
     * 清理过多的缓存
     */
    private suspend fun cleanupCache() {
        val emailCount = emailDao.getEmailCount()
        if (emailCount > MAX_EMAILS_CACHE) {
            val deleteCount = emailCount - MAX_EMAILS_CACHE + LRU_CLEANUP_COUNT
            emailDao.deleteOldestEmails(deleteCount)
            Log.d(TAG, "清理邮件缓存: 删除${deleteCount}封")
        }
    }
    
    /**
     * 清理过多的正文缓存
     */
    private suspend fun cleanupBodyCache() {
        val bodyCount = emailBodyDao.getBodyCount()
        if (bodyCount > MAX_BODIES_CACHE) {
            val deleteCount = bodyCount - MAX_BODIES_CACHE + 10
            emailBodyDao.deleteOldestBodies(deleteCount)
            Log.d(TAG, "清理正文缓存: 删除${deleteCount}个")
        }
    }
}

/**
 * 同步结果
 * @param newCount 本次真正新增的邮件数量
 * @param newEmails 新增的邮件列表（用于推送通知）
 */
sealed class SyncResult {
    data class Success(
        val newCount: Int,
        val newEmails: List<EmailEntity> = emptyList()
    ) : SyncResult()
    data class Error(val message: String) : SyncResult()
}
