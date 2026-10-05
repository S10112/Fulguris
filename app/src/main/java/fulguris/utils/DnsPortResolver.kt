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
    // 严格按 "scheme:host" 映射专属默认端口，协议绝对隔离
    private val defaultPortMap = ConcurrentHashMap<String, Int>()

    /**
     * 判断当前 URL 携带的端口是否属于该域名下的“默认端口”（包括原生 80/443 以及 DNS 专属默认端口）
     */
    fun isDefaultPort(rawUrl: String): Boolean {
        val uri = try { Uri.parse(rawUrl) } catch (e: Exception) { return true }
        val port = uri.port
        if (port == -1) return true

        val host = uri.host?.lowercase() ?: return true
        val scheme = uri.scheme?.lowercase() ?: "http"

        if (scheme == "http" && port == 80) return true
        if (scheme == "https" && port == 443) return true

        val mappedPort = defaultPortMap["$scheme:$host"]
        return mappedPort != null && mappedPort == port
    }

    /**
     * 静默验证并记录默认端口
     */
    suspend fun verifyAndCachePort(rawUrl: String): Boolean = withContext(Dispatchers.IO) {
        val uri = try { Uri.parse(rawUrl) } catch (e: Exception) { return@withContext false }
        val host = uri.host?.lowercase() ?: return@withContext false
        val scheme = uri.scheme?.lowercase() ?: "http"
        val port = uri.port

        if (port == -1) return@withContext false
        if (isDefaultPort(rawUrl)) return@withContext true

        if (scheme == "https") {
            val targetPort = queryUdpDnsPort(host, 65) ?: queryDohPort(host)
            if (targetPort != null && targetPort == port) {
                defaultPortMap["https:$host"] = port
                return@withContext true
            }
        } else {
            val targetPort = queryUdpDnsPort("_http._tcp.$host", 64)
            if (targetPort != null && targetPort == port) {
                defaultPortMap["http:$host"] = port
                return@withContext true
            }
        }
        return@withContext false
    }

    /**
     * 同步获取缓存的目标请求 URL
     */
    fun getCachedUrl(rawUrl: String): String? {
        val uri = try { Uri.parse(rawUrl) } catch (e: Exception) { return null }
        val host = uri.host?.lowercase() ?: return null
        val scheme = uri.scheme?.lowercase() ?: "http"

        if (uri.port != -1 || (scheme != "http" && scheme != "https")) return null

        val targetPort = defaultPortMap["$scheme:$host"] ?: return null

        if ((scheme == "http" && targetPort == 80) || (scheme == "https" && targetPort == 443)) {
            return null
        }

        val path = uri.encodedPath ?: ""
        val query = if (uri.encodedQuery != null) "?${uri.encodedQuery}" else ""
        val fragment = if (uri.encodedFragment != null) "#${uri.encodedFragment}" else ""
        return "$scheme://$host:$targetPort$path$query$fragment"
    }

    /**
     * 核心全动态解析：严格区分 HTTP 与 HTTPS
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
            // HTTPS 仅查 Type 65
            targetPort = queryUdpDnsPort(host, 65) ?: queryDohPort(host)
            if (targetPort != null && targetPort in 1..65535 && targetPort != 443) {
                defaultPortMap["https:$host"] = targetPort
            } else {
                defaultPortMap["https:$host"] = 443
                return@withContext rawUrl
            }
        } else {
            // HTTP 查询 Type 64
            targetPort = queryUdpDnsPort("_http._tcp.$host", 64)

            // 若 HTTP 无配置，检查是否仅配置了 HTTPS
            if (targetPort == null) {
                val httpsPort = queryUdpDnsPort(host, 65) ?: queryDohPort(host)
                if (httpsPort != null && httpsPort in 1..65535 && httpsPort != 443) {
                    defaultPortMap["https:$host"] = httpsPort
                    val path = uri.encodedPath ?: ""
                    val query = if (uri.encodedQuery != null) "?${uri.encodedQuery}" else ""
                    val fragment = if (uri.encodedFragment != null) "#${uri.encodedFragment}" else ""
                    return@withContext "https://$host:$httpsPort$path$query$fragment"
                }
            }

            if (targetPort != null && targetPort in 1..65535 && targetPort != 80) {
                defaultPortMap["http:$host"] = targetPort
            } else {
                defaultPortMap["http:$host"] = 80
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
     * 地址栏净化：
     * 只要端口是该域名下的“默认端口”（包括传统 80/443 以及解析出的专属端口），一律不在地址栏显示端口。
     */
    fun cleanUrlForDisplay(url: String): String {
        if (url.isBlank()) return url
        val uri = try { Uri.parse(url) } catch (e: Exception) { return url }
        val port = uri.port
        if (port == -1) return url

        val host = uri.host?.lowercase() ?: return url
        val scheme = uri.scheme?.lowercase() ?: "http"

        val defaultPort = defaultPortMap["$scheme:$host"] ?: if (scheme == "https") 443 else 80

        if (port == defaultPort) {
            val portPart = ":$port"
            val index = url.indexOf(portPart)
            if (index != -1) {
                return url.substring(0, index) + url.substring(index + portPart.length)
            }
        }
        return url
    }
}
