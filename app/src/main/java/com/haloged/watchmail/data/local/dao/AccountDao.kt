package com.haloged.watchmail.data.local.dao

import androidx.room.*
import com.haloged.watchmail.data.local.entity.AccountEntity
import kotlinx.coroutines.flow.Flow

/**
 * 账户数据访问对象
 */
@Dao
interface AccountDao {
    
    /**
     * 获取所有账户（实时观察）
     */
    @Query("SELECT * FROM accounts ORDER BY createdAt DESC")
    fun getAllAccountsFlow(): Flow<List<AccountEntity>>
    
    /**
     * 获取所有启用的账户
     */
    @Query("SELECT * FROM accounts WHERE isEnabled = 1 ORDER BY alias ASC")
    suspend fun getEnabledAccounts(): List<AccountEntity>
    
    /**
     * 根据ID获取账户
     */
    @Query("SELECT * FROM accounts WHERE id = :accountId")
    suspend fun getAccountById(accountId: Long): AccountEntity?
    
    /**
     * 根据邮箱地址获取账户
     */
    @Query("SELECT * FROM accounts WHERE email = :email LIMIT 1")
    suspend fun getAccountByEmail(email: String): AccountEntity?
    
    /**
     * 插入账户
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: AccountEntity): Long
    
    /**
     * 更新账户
     */
    @Update
    suspend fun updateAccount(account: AccountEntity)
    
    /**
     * 删除账户
     */
    @Delete
    suspend fun deleteAccount(account: AccountEntity)
    
    /**
     * 根据ID删除账户
     */
    @Query("DELETE FROM accounts WHERE id = :accountId")
    suspend fun deleteAccountById(accountId: Long)
    
    /**
     * 更新最后同步时间
     */
    @Query("UPDATE accounts SET lastSyncTime = :syncTime WHERE id = :accountId")
    suspend fun updateLastSyncTime(accountId: Long, syncTime: Long)
    
    /**
     * 更新账户启用状态
     */
    @Query("UPDATE accounts SET isEnabled = :enabled WHERE id = :accountId")
    suspend fun updateAccountEnabled(accountId: Long, enabled: Boolean)
    
    /**
     * 更新通知设置
     */
    @Query("UPDATE accounts SET notificationEnabled = :enabled WHERE id = :accountId")
    suspend fun updateNotificationEnabled(accountId: Long, enabled: Boolean)
    
    /**
     * 获取账户数量
     */
    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun getAccountCount(): Int
}
