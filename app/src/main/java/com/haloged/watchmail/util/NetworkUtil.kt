package com.haloged.watchmail.util

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 网络工具类
 * 用于获取局域网 IP 地址（扫码配对场景：手表作为 Web 服务端，手机需通过局域网访问）
 */
object NetworkUtil {

    private const val TAG = "NetworkUtil"

    /**
     * 获取当前设备的局域网 IPv4 地址
     *
     * 遍历所有网络接口，返回第一个非回环的 IPv4 地址。
     * 优先返回 192.168.x.x / 10.x.x.x / 172.16-31.x.x 等私有地址，
     * 以适配家庭/办公 WiFi 场景。
     *
     * @return 局域网 IPv4 字符串；无可用接口时返回 null
     */
    fun getLocalIpAddress(): String? {
        return try {
            val candidates = mutableListOf<String>()

            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
                // 跳过未启用/回环接口
                if (!nif.isUp || nif.isLoopback) return@forEach

                nif.inetAddresses?.toList()?.forEach { addr ->
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { candidates.add(it) }
                    }
                }
            }

            // 优先私有网段（WiFi 常见），再退回其他地址
            val preferred = candidates.firstOrNull { isPrivateLan(it) }
            val result = preferred ?: candidates.firstOrNull()

            Log.d(TAG, "本机 IP 候选: $candidates, 选中: $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "获取本机 IP 失败", e)
            null
        }
    }

    /**
     * 判断是否为私有局域网地址
     * 10.0.0.0/8 · 172.16.0.0/12 · 192.168.0.0/16 · 169.254.0.0/16
     */
    private fun isPrivateLan(ip: String): Boolean {
        return ip.startsWith("192.168.") ||
                ip.startsWith("10.") ||
                ip.startsWith("169.254.") ||
                Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(ip)
    }

    /**
     * 检查是否已连接到局域网（能否拿到可用 IP）
     */
    fun isLanAvailable(): Boolean = getLocalIpAddress() != null
}
