package com.koyo.screenwarden

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL

/**
 * 局域网连接层：UDP 广播发现 + HTTP 健康探活。
 *
 * 只负责"发现 + 建立链路 + 健康探活"，不含任何设备业务语义——
 * 查询串、端口、响应前缀、探活路径都由调用方（驱动描述）给出。
 *
 * 从 CarFragment 抽出，原逻辑原样保留：
 * - 部分 Android 热点会丢弃 255.255.255.255，因此同时发定向广播 + 全地址广播；
 * - mDNS / VPN fake-IP 的坑由调用方在候选地址里规避，这里只管 UDP。
 */
object LanConnector {

    /**
     * UDP 广播查询，等待并返回首个匹配响应。
     *
     * @param udpQuery       查询串，如 "KOYO_DISCOVER"
     * @param udpPort        监听端口，如 4210
     * @param responsePrefix 响应前缀，用于默认解析（去除前缀后作为地址返回）
     * @param parse          自定义解析；默认去掉 responsePrefix。网关这类需要拼端口的走这里
     */
    fun discover(
        udpQuery: String,
        udpPort: Int,
        responsePrefix: String,
        timeoutMs: Int = 2200,
        parse: (text: String, from: InetAddress) -> String? = { text, _ ->
            if (text.startsWith(responsePrefix)) text.removePrefix(responsePrefix) else null
        }
    ): String? {
        return try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = timeoutMs
                val query = udpQuery.toByteArray(Charsets.US_ASCII)

                // 部分 Android 热点会丢弃 255.255.255.255，却允许当前网段的
                // 定向广播（例如 10.113.208.255），因此两种地址都发送。
                val broadcastAddresses = linkedSetOf<InetAddress>()
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    if (!networkInterface.isUp || networkInterface.isLoopback) continue
                    for (interfaceAddress in networkInterface.interfaceAddresses) {
                        interfaceAddress.broadcast?.let { broadcastAddresses.add(it) }
                    }
                }
                broadcastAddresses.add(InetAddress.getByName("255.255.255.255"))
                for (address in broadcastAddresses) {
                    socket.send(DatagramPacket(query, query.size, address, udpPort))
                }

                val buffer = ByteArray(128)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                val text = String(response.data, 0, response.length, Charsets.US_ASCII)
                parse(text, response.address)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** HTTP 健康探活：GET 指定 path，2xx 视为存活。 */
    fun probe(baseUrl: String, path: String, timeoutMs: Int = 1800): Boolean {
        return try {
            val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
            }
            val ok = connection.responseCode in 200..299
            if (ok) connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            ok
        } catch (_: Exception) {
            false
        }
    }
}
