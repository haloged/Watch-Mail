package com.wm.wearmail.mail

import com.wm.wearmail.model.MailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MimeAddressSupport] 单元测试（纯 JVM）。
 *
 * 该工具同时被协议层（头部解析）与数据层（`EmailMeta.to/cc` 的入库序列化）使用，
 * 因此「解析宽容度」与「序列化可逆性」都必须覆盖。
 */
class MimeAddressSupportTest {

    // ---------------- 解析 ----------------

    @Test
    fun `解析带引号显示名`() {
        val list = MimeAddressSupport.parseAddressList("\"张三\" <a@b.com>")
        assertEquals(1, list.size)
        assertEquals("a@b.com", list[0].address)
        assertEquals("张三", list[0].name)
    }

    @Test
    fun `解析不带引号的显示名`() {
        val list = MimeAddressSupport.parseAddressList("张三 <a@b.com>")
        assertEquals(MailAddress("a@b.com", "张三"), list[0])

        // 显示名与尖括号之间没有空格也要能解析
        assertEquals(
            MailAddress("a@b.com", "张三"),
            MimeAddressSupport.parseAddressList("张三<a@b.com>")[0],
        )
    }

    @Test
    fun `解析裸地址时显示名为空`() {
        val list = MimeAddressSupport.parseAddressList("c@d.com")
        assertEquals(1, list.size)
        assertEquals("c@d.com", list[0].address)
        assertNull(list[0].name)
    }

    @Test
    fun `解析尖括号地址`() {
        assertEquals(MailAddress("a@b.com", null), MimeAddressSupport.parseAddressList("<a@b.com>")[0])
    }

    @Test
    fun `解析逗号分隔的多个地址`() {
        val list = MimeAddressSupport.parseAddressList("\"张三\" <a@b.com>, c@d.com")
        assertEquals(2, list.size)
        assertEquals(MailAddress("a@b.com", "张三"), list[0])
        assertEquals(MailAddress("c@d.com", null), list[1])
    }

    @Test
    fun `引号内的逗号不会拆分地址`() {
        val list = MimeAddressSupport.parseAddressList("\"张,三\" <a@b.com>, c@d.com")
        assertEquals(2, list.size)
        assertEquals("张,三", list[0].name)
        assertEquals("c@d.com", list[1].address)
    }

    @Test
    fun `显示名中的转义引号被还原`() {
        val list = MimeAddressSupport.parseAddressList("\"张\\\"三\" <a@b.com>")
        assertEquals(1, list.size)
        assertEquals("张\"三", list[0].name)
    }

    @Test
    fun `注释形式的显示名被识别`() {
        assertEquals(
            MailAddress("a@b.com", "张三"),
            MimeAddressSupport.parseAddressList("a@b.com (张三)")[0],
        )
    }

    @Test
    fun `组语法被展开`() {
        val list = MimeAddressSupport.parseAddressList("同事: a@b.com, c@d.com;")
        assertEquals(2, list.size)
        assertEquals(MailAddress("a@b.com", null), list[0])
        assertEquals(MailAddress("c@d.com", null), list[1])
    }

    @Test
    fun `RFC2047 B 编码显示名被解码`() {
        val list = MimeAddressSupport.parseAddressList("=?UTF-8?B?5byg5LiJ?= <a@b.com>")
        assertEquals("张三", list[0].name)
        assertEquals("a@b.com", list[0].address)
    }

    @Test
    fun `RFC2047 Q 编码显示名被解码`() {
        val list = MimeAddressSupport.parseAddressList("=?utf-8?Q?=E5=BC=A0=E4=B8=89?= <a@b.com>")
        assertEquals("张三", list[0].name)
    }

    @Test
    fun `多余空白与空项被忽略`() {
        val list = MimeAddressSupport.parseAddressList("  a@b.com ,  , c@d.com  ")
        assertEquals(2, list.size)
        assertEquals("a@b.com", list[0].address)
        assertEquals("c@d.com", list[1].address)
    }

    // ---------------- 异常输入 ----------------

    @Test
    fun `空输入返回空列表`() {
        assertTrue(MimeAddressSupport.parseAddressList(null).isEmpty())
        assertTrue(MimeAddressSupport.parseAddressList("").isEmpty())
        assertTrue(MimeAddressSupport.parseAddressList("   ").isEmpty())
        assertTrue(MimeAddressSupport.parseAddressList(",,, ;;").isEmpty())
    }

    @Test
    fun `非法地址被跳过`() {
        assertTrue(MimeAddressSupport.parseAddressList("a@, @b.com, 这不是地址, <no-at-sign>").isEmpty())
    }

    @Test
    fun `解析失败返回 UNKNOWN`() {
        assertEquals(MailAddress.UNKNOWN, MimeAddressSupport.firstAddress(null))
        assertEquals(MailAddress.UNKNOWN, MimeAddressSupport.firstAddress(""))
        assertEquals(MailAddress.UNKNOWN, MimeAddressSupport.firstAddress("这不是地址"))
        assertEquals(MailAddress.UNKNOWN, MimeAddressSupport.parseSingle(null))
        assertEquals(MailAddress.UNKNOWN, MimeAddressSupport.parseSingle(""))
        assertEquals(MailAddress.UNKNOWN, MimeAddressSupport.parseSingle("a@"))
    }

    @Test
    fun `firstAddress 只取第一个`() {
        assertEquals(
            MailAddress("a@b.com", "张三"),
            MimeAddressSupport.firstAddress("张三 <a@b.com>, c@d.com"),
        )
    }

    @Test
    fun `parseSingle 支持显示名形式`() {
        assertEquals(MailAddress("a@b.com", "张三"), MimeAddressSupport.parseSingle("张三 <a@b.com>"))
        assertEquals(MailAddress("a@b.com", null), MimeAddressSupport.parseSingle("a@b.com"))
    }

    // ---------------- 序列化与往返 ----------------

    @Test
    fun `序列化格式符合预期`() {
        val text = MimeAddressSupport.formatAddressList(
            listOf(
                MailAddress("a@b.com", "张三"),
                MailAddress("c@d.com", null),
                MailAddress("e@f.com", "张,三 四"),
            ),
        )
        assertEquals("张三 <a@b.com>, c@d.com, \"张,三 四\" <e@f.com>", text)
    }

    @Test
    fun `序列化与解析往返一致`() {
        val origin = listOf(
            MailAddress("a@b.com", "张三"),
            MailAddress("c@d.com", null),
            MailAddress("e@f.com", "张,三 四"),
            MailAddress("g@h.com", "带\"引号\"的名字"),
            MailAddress("i@j.com", "分号;名字"),
        )
        val text = MimeAddressSupport.formatAddressList(origin)
        assertEquals(origin, MimeAddressSupport.parseAddressList(text))
    }

    @Test
    fun `空列表序列化为空串且可反向解析`() {
        assertEquals("", MimeAddressSupport.formatAddressList(emptyList()))
        assertTrue(MimeAddressSupport.parseAddressList("").isEmpty())
    }

    @Test
    fun `序列化跳过空地址项`() {
        val text = MimeAddressSupport.formatAddressList(
            listOf(MailAddress("   ", "空地址"), MailAddress("a@b.com", null)),
        )
        assertEquals("a@b.com", text)
    }

    @Test
    fun `UNKNOWN 占位地址往返后仍为 UNKNOWN`() {
        val text = MimeAddressSupport.formatAddressList(listOf(MailAddress.UNKNOWN))
        assertEquals(MailAddress.UNKNOWN, MimeAddressSupport.parseSingle(text))
    }
}
