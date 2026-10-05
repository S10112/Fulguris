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
     * 判断当前 URL 携带的端口是否属于 DNS 下发的“默认专属端口”
     */
    fun isCachedAsDefaultPort(rawUrl: String): Boolean {
        val uri = try { Uri.parse(rawUrl) } catch (e: Exception) { return false }
        val port = uri.port
        if (port == -1) return false
        val host = uri.host?.lowercase() ?: return false
        
        return portCache["http:$host"] == port || portCache["https:$host"] == port
    }

    /**
     * 静默验证并写入缓存：
     * 当遇到带有端口的链接（如从历史记录恢复、或手动带端口访问）时，后台验证该端口是否为 DNS 配置的默认端口
     */
    suspend fun verifyAndCachePort(rawUrl: String): Boolean = withContext(Dispatchers.IO) {
        val uri = try { Uri.parse(rawUrl) } catch (e: Exception) { return@withContext false }
        val host = uri.host?.lowercase() ?: return@withContext false
        val scheme = uri.scheme?.lowercase() ?: "http"
        val port = uri.port
        
        if (port == -1) return@withContext false
        if (isCachedAsDefaultPort(rawUrl)) return@withContext true
        
        if (scheme == "https") {
            val targetPort = queryUdpDnsPort(host, 65) ?: queryDohPort(host)
            if (targetPort != null && targetPort == port) {
                portCache["https:$host"] = port
                return@withContext true
            }
        } else {
            val targetPort = queryUdpDnsPort("_http._tcp.$host", 64)
            if (targetPort == null) {
                val httpsPort = queryUdpDnsPort(host, 65) ?: queryDohPort(host)
                if (httpsPort != null && httpsPort == port) {
                    portCache["https:$host"] = port
                    return@withContext true
                }
            }
            if (targetPort != null && targetPort == port) {
                portCache["http:$host"] = port
                return@withContext true
            }
        }
        return@withContext false
    }

    fun getCachedUrl(rawUrl: String): String? {
        val uri = try { Uri.parse(rawUrl) } catch (e: Exception) { return null }
        val host = uri.host?.lowercase() ?: return null
        val scheme = uri.scheme?.lowercase() ?: "http"

        if (uri.port != -1 || (scheme != "http" && scheme != "https")) return null

        val targetPort = portCache["$scheme:$host"] ?: return null

        if ((scheme == "http" && targetPort == 80) || (scheme == "https" && targetPort == 443)) {
            return null
        }

        if (scheme == "https" && targetPort == 802) {
            return null
        }

        val path = uri.encodedPath ?: ""
        val query = if (uri.encodedQuery != null) "?${uri.encodedQuery}" else ""
        val fragment = if (uri.encodedFragment != null) "#${uri.encodedFragment}" else ""
        return "$scheme://$host:$targetPort$path$query$fragment"
    }

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
            targetPort = queryUdpDnsPort(host, 65) ?: queryDohPort(host)
            if (targetPort != null && targetPort in 1..65535 && targetPort != 443) {
                portCache["https:$host"] = targetPort
            } else {
                portCache["https:$host"] = 443
                return@withContext rawUrl
            }
        } else {
            targetPort = queryUdpDnsPort("_http._tcp.$host", 64)
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
                portCache["http:$host"] = 80
                return@withContext rawUrl
            }
        }

        val path = uri.encodedPath ?: ""
        val query = if (uri.encodedQuery != null) "?${uri.encodedQuery}" else ""
        val fragment = if (uri.encodedFragment != null) "#${uri.encodedFragment}" else ""
        return@withContext "$scheme://$host:$targetPort$path$query$fragment"
    }

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
     * ==============================================
     * 地址栏净化过滤（绝对智能版）：
     * 1. 隐藏绝对标准端口 (80/443)
     * 2. 隐藏 DNS 解析出来的“专属默认端口”（如 8641）
     * 3. 用户手动输入的无关端口，保留原样显示
     * ==============================================
     */
    fun cleanUrlForDisplay(url: String): String {
        if (url.isBlank()) return url
        val uri = try { Uri.parse(url) } catch (e: Exception) { return url }
        val port = uri.port
        
        // 没有自带端口的直接放行
        if (port == -1) return url
        
        val host = uri.host?.lowercase() ?: return url
        val scheme = uri.scheme?.lowercase() ?: ""
        
        // 1. 如果是业界绝对标准的 80 或 443，一律隐藏
        if ((scheme == "http" && port == 80) || (scheme == "https" && port == 443)) {
            // 使用严格的 Regex 正则替换语法
            return url.replaceFirst(Regex(""":$port(?=[/?#]|$)"""), "")
        }

        // 2. 只要当前端口是我们通过 DNS 动态解析出来的“接管端口”，视同为新默认端口，一律隐藏
        if (isCachedAsDefaultPort(url)) {
            // 使用严格的 Regex 正则替换语法
            return url.replaceFirst(Regex(""":$port(?=[/?#]|$)"""), "")
        }

        // 3. 其他情况（用户手动输入的无关非标端口），保留原样显示
        return url
    }
}
