package fulguris.utils

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object DnsPortResolver {
    // 动态内存映射缓存：严格按 "scheme:host" 隔离，绝不交叉
    private val portCache = ConcurrentHashMap<String, Int>()

    /**
     * 极速同步获取缓存中已重写的 URL（已缓存非标端口则 0ms 返回，标准端口或未缓存返回 null）
     */
    fun getCachedUrl(rawUrl: String): String? {
        val uri = try { Uri.parse(rawUrl) } catch (e: Exception) { return null }
        val host = uri.host?.lowercase() ?: return null
        val scheme = uri.scheme?.lowercase() ?: "http"

        // 已经带有端口或非 http/https 协议，直接放行
        if (uri.port != -1 || (scheme != "http" && scheme != "https")) return null

        val targetPort = portCache["$scheme:$host"] ?: return null

        // 标准 80 / 443 放行原生请求
        if ((scheme == "http" && targetPort == 80) || (scheme == "https" && targetPort == 443)) {
            return null
        }

        // 防火墙：严禁 https 挂 802 明文端口
        if (scheme == "https" && targetPort == 802) {
            return null
        }

        val path = uri.encodedPath ?: ""
        val query = if (uri.encodedQuery != null) "?${uri.encodedQuery}" else ""
        val fragment = if (uri.encodedFragment != null) "#${uri.encodedFragment}" else ""
        return "$scheme://$host:$targetPort$path$query$fragment"
    }

    /**
     * 核心全动态解析入口：区分普通网站（80/443）与非标端口网站
     */
    suspend fun resolveTargetUrl(rawUrl: String): String = withContext(Dispatchers.IO) {
        val uri = try { Uri.parse(rawUrl) } catch (e: Exception) { return@withContext rawUrl }
        val host = uri.host?.lowercase() ?: return@withContext rawUrl
        val scheme = uri.scheme?.lowercase() ?: "http"

        if (uri.port != -1 || (scheme != "http" && scheme != "https")) {
            return@withContext rawUrl
        }

        val cached = getCachedUrl(rawUrl)
        if (cached != null) {
            return@withContext cached
        }

        var targetPort: Int? = null

        if (scheme == "https") {
            // HTTPS 查询 Type 65 (RFC 9460 HTTPS 记录)
            targetPort = queryUdpDnsPort(host, 65) ?: queryDohPort(host)

            if (targetPort != null && targetPort in 1..65535 && targetPort != 443) {
                // 仅当明确解析到有效的非标 HTTPS 端口时记录
                portCache["https:$host"] = targetPort
            } else {
                // 未配置非标端口：标准 HTTPS 网站，记录 443 并放行
                portCache["https:$host"] = 443
                return@withContext rawUrl
            }
        } else {
            // HTTP 查询 Type 64 (RFC 9460 SVCB 记录)
            targetPort = queryUdpDnsPort("_http._tcp.$host", 64)

            // 若 HTTP 未配置，探测是否配置了 HTTPS 非标端口记录
            if (targetPort == null) {
                val httpsPort = queryUdpDnsPort(host, 65) ?: queryDohPort(host)
                if (httpsPort != null && httpsPort in 1..65535 && httpsPort != 443 && httpsPort != 802) {
                    portCache["https:$host"] = httpsPort
                    val path = uri.encodedPath ?: ""
                    val query = if (uri.encodedQuery != null) "?${uri.encodedQuery}" else ""
                    val fragment = if (uri.encodedFragment != null) "#${uri.encodedFragment}" else ""
                    return@withContext "https://$host:$httpsPort$path$query$fragment"
                }
            }

            if (targetPort != null && targetPort in 1..65535 && targetPort != 80) {
                portCache["http:$host"] = targetPort
            } else {
                // 未配置非标端口：标准 HTTP 网站，记录 80 并放行
                portCache["http:$host"] = 80
                return@withContext rawUrl
            }
        }

        val path = uri.encodedPath ?: ""
        val query = if (uri.encodedQuery != null) "?${uri.encodedQuery}" else ""
        val fragment = if (uri.encodedFragment != null) "#${uri.encodedFragment}" else ""
        return@withContext "$scheme://$host:$targetPort$path$query$fragment"
    }

    /**
     * 原生 UDP 53 数据包探测
     */
    private fun queryUdpDnsPort(domain: String, qtype: Int): Int? {
        var socket: DatagramSocket? = null
        try {
            val header = byteArrayOf(
                0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
            )

            val qnameStream = ByteArrayOutputStream()
            for (part in domain.split(".")) {
                if (part.isNotEmpty()) {
                    val bytes = part.toByteArray(Charsets.US_ASCII)
                    qnameStream.write(bytes.size)
                    qnameStream.write(bytes)
                }
            }
            qnameStream.write(0x00)

            val qtail = byteArrayOf(
                (qtype shr 8).toByte(), (qtype and 0xFF).toByte(), 0x00, 0x01
            )

            val requestData = ByteArrayOutputStream().apply {
                write(header)
                write(qnameStream.toByteArray())
                write(qtail)
            }.toByteArray()

            socket = DatagramSocket()
            socket.soTimeout = 600

            val dnsServer = InetAddress.getByName("1.1.1.1")
            val sendPacket = DatagramPacket(requestData, requestData.size, dnsServer, 53)
            socket.send(sendPacket)

            val buffer = ByteArray(2048)
            val receivePacket = DatagramPacket(buffer, buffer.size)
            socket.receive(receivePacket)

            val len = receivePacket.length
            for (i in 12 until (len - 5)) {
                if (buffer[i] == 0x00.toByte() && buffer[i + 1] == 0x03.toByte() &&
                    buffer[i + 2] == 0x00.toByte() && buffer[i + 3] == 0x02.toByte()
                ) {
                    val high = buffer[i + 4].toInt() and 0xFF
                    val low = buffer[i + 5].toInt() and 0xFF
                    val port = (high shl 8) or low
                    if (port in 1..65535) return port
                }
            }
        } catch (ignored: Exception) {
        } finally {
            try { socket?.close() } catch (ignored: Exception) {}
        }
        return null
    }

    /**
     * DoH 备用通道探测
     */
    private fun queryDohPort(domain: String): Int? {
        return try {
            val url = URL("https://1.1.1.1/dns-query?name=$domain&type=HTTPS")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/dns-json")
            conn.connectTimeout = 700
            conn.readTimeout = 700

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()

                val json = JSONObject(response)
                val answers = json.optJSONArray("Answer")
                if (answers != null) {
                    for (i in 0 until answers.length()) {
                        val data = answers.getJSONObject(i).optString("data")
                        val match = Regex("""port=(\d+)""").find(data)
                        if (match != null) {
                            val port = match.groupValues[1].toInt()
                            if (port in 1..65535) return port
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 地址栏净化过滤
     */
    fun cleanUrlForDisplay(url: String): String {
        if (url.isBlank()) return url
        return url.replace(Regex(""":(80[0-9]|808[0-9]|80|443)(?=[/?#]|$)"""), "")
    }
}
