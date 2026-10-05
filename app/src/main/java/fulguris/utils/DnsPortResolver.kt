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
     * 极速同步获取缓存中已重写的 URL（已缓存则 0ms 返回，未缓存返回 null）
     */
    fun getCachedUrl(rawUrl: String): String? {
        val uri = try { Uri.parse(rawUrl) } catch (e: Exception) { return null }
        val host = uri.host?.lowercase() ?: return null
        val scheme = uri.scheme?.lowercase() ?: "http"

        // 已经带有端口或非 http/https 协议，直接放行
        if (uri.port != -1 || (scheme != "http" && scheme != "https")) return null

        val targetPort = portCache["$scheme:$host"] ?: return null

        // 标准 80 / 443 原样放行
        if ((scheme == "http" && targetPort == 80) || (scheme == "https" && targetPort == 443)) {
            return rawUrl
        }

        // 绝对防火墙：严禁 https 挂 802
        if (scheme == "https" && targetPort == 802) {
            val safePort = portCache["https:$host"]?.takeIf { it != 802 } ?: 803
            val path = uri.encodedPath ?: ""
            val query = if (uri.encodedQuery != null) "?${uri.encodedQuery}" else ""
            val fragment = if (uri.encodedFragment != null) "#${uri.encodedFragment}" else ""
            return "https://$host:$safePort$path$query$fragment"
        }

        val path = uri.encodedPath ?: ""
        val query = if (uri.encodedQuery != null) "?${uri.encodedQuery}" else ""
        val fragment = if (uri.encodedFragment != null) "#${uri.encodedFragment}" else ""
        return "$scheme://$host:$targetPort$path$query$fragment"
    }

    /**
     * 核心全动态解析入口：零硬编码，适用于任意配置了 RFC 9460 记录的域名
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
            // HTTPS 只允许查 Type 65 (RFC 9460 HTTPS 记录)
            targetPort = queryUdpDnsPort(host, 65) ?: queryDohPort(host)
            
            // 兜底安全校验：HTTPS 绝不允许被赋予 802
            if (targetPort == null || targetPort == 802) {
                targetPort = 803
            }
            portCache["https:$host"] = targetPort
        } else {
            // HTTP 查询 Type 64 (RFC 9460 SVCB 记录)
            targetPort = queryUdpDnsPort("_http._tcp.$host", 64)

            // 如果 HTTP 探测未配，检查该域名是否仅配置了 HTTPS 端口记录
            if (targetPort == null) {
                val httpsPort = queryUdpDnsPort(host, 65) ?: queryDohPort(host)
                if (httpsPort != null && httpsPort > 0 && httpsPort != 443 && httpsPort != 802) {
                    portCache["https:$host"] = httpsPort
                    val path = uri.encodedPath ?: ""
                    val query = if (uri.encodedQuery != null) "?${uri.encodedQuery}" else ""
                    val fragment = if (uri.encodedFragment != null) "#${uri.encodedFragment}" else ""
                    return@withContext "https://$host:$httpsPort$path$query$fragment"
                }
                targetPort = 802 // 默认非标 HTTP 端口
            }
            portCache["http:$host"] = targetPort
        }

        // 普通网站默认 80/443 直接原样放行
        if ((scheme == "http" && targetPort == 80) || (scheme == "https" && targetPort == 443)) {
            return@withContext rawUrl
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
                            return match.groupValues[1].toInt()
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
