package com.glassbox.app.core

import java.net.Inet4Address
import java.net.NetworkInterface

object DeviceNet {
    /** 优先返回非 loopback 的 IPv4，用于局域网推送提示。 */
    fun localIpv4(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        }.getOrNull()
    }
}
