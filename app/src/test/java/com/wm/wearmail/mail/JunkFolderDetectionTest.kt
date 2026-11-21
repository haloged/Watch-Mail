package com.wm.wearmail.mail

import com.wm.wearmail.model.MailFolder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 垃圾邮件文件夹识别（纯函数）。
 *
 * 各服务商命名与 SPECIAL-USE 属性不一致，识别错会直接导致「垃圾邮件箱是空的」。
 */
class JunkFolderDetectionTest {

    @Test
    fun `SPECIAL-USE 属性优先识别`() {
        assertTrue(MailFolder(name = "Junk", attributes = listOf("\\Junk")).isJunkFolder())
        assertTrue(MailFolder(name = "Spam", attributes = listOf("\\Spam")).isJunkFolder())
        // 名称不像垃圾文件夹，但属性表明是
        assertTrue(MailFolder(name = "不明文件夹", attributes = listOf("\\Junk")).isJunkFolder())
    }

    @Test
    fun `按常见名称识别`() {
        assertTrue(MailFolder(name = "Spam").isJunkFolder())
        assertTrue(MailFolder(name = "Junk Email").isJunkFolder())
        assertTrue(MailFolder(name = "垃圾邮件").isJunkFolder())
        assertTrue(MailFolder(name = "广告邮件").isJunkFolder())
    }

    @Test
    fun `普通文件夹不误判`() {
        assertFalse(MailFolder(name = "INBOX").isJunkFolder())
        assertFalse(MailFolder(name = "Archive").isJunkFolder())
        assertFalse(MailFolder(name = "Sent Items").isJunkFolder())
        assertFalse(MailFolder(name = "已发送").isJunkFolder())
    }
}
