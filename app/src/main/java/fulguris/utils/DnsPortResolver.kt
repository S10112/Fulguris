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
    // 专属默认端口映射表：将 DNS 解析出的非标端口直接注册为该域名的“系统默认端口”
    // 格式: "scheme:host" -> 默认端口号 (例如 "https:j.6z.ee" -> 8641)
    val defaultPortMap = ConcurrentHashMap<String, Int>()

    /**
     * 获取指定域名在特定协议下的默认端口：
     * - 若 DNS 解析出了专属端口，该端口即等同于 80/443 的默认端口；
     * - 未配置时回退到原生标准端口（HTTP = 80, HTTPS = 443）。
     */
    fun getDefaultPort(scheme: String, host: String): Int {
        return defaultPortMap["$scheme:$host"] ?: if (scheme == "https") 443 else 80
    }

    /**
     * 判断当前端口是否属于该域名下的“默认端口”（像 80 / 443 一样）
     */
    fun isDefaultPort(scheme: String, host: String, port: Int): Boolean {
        if (port == -1) return true
        return port == getDefaultPort(scheme, host)
    }

    fun isDefaultPort(rawUrl: String): Boolean {
        val uri = try { Uri.parse(rawUrl) } catch (e: Exception) { return true }
        val port = uri.port
        if (port == -1) return true

        val host = uri.host?.lowercase() ?: return true
        val scheme = uri.scheme?.lowercase() ?: "http"
        return isDefaultPort(scheme, host, port)
    }

    fun isCachedAsDefaultPort(rawUrl: String): Boolean {
        return isDefaultPort(rawUrl)
    }

    fun setDefaultPort(scheme: String, host: String, port: Int) {
        defaultPortMap["$scheme:$host"] = port
    }

    /**
     * 静默验证：对历史记录或直接带端口进入的链接，后台核验并补全默认端口映射
     */
    suspend fun verifyAndCachePort(rawUrl: String): Boolean {
        val uri = try { Uri.parse(rawUrl) } catch (e: Exception) { return false }
        val host = uri.host?.lowercase() ?: return false
        val scheme = uri.scheme?.lowercase() ?: "http"
        val port = uri.port

        if (port == -1) return false
        if (isDefaultPort(rawUrl)) return true

        val matched = withContext(Dispatchers.IO) {
            if (scheme == "https") {
                val targetPort = queryUdpDnsPort(host, 65) ?: queryDohPort(host)
                targetPort != null && targetPort == port
            } else {
                val targetPort = queryUdpDnsPort("_http._tcp.$host", 64)
                targetPort != null && targetPort == port
            }
        }

        if (matched) {
            setDefaultPort(scheme, host, port)
            return true
        }
        return false
    }

    /**
     * 同步获取缓存的目标网络请求 URL
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
     * 全动态解析入口：优先探测 HTTPS 记录直连，防止 HTTP 转 HTTPS 时的端口错配
     */
    suspend fun resolveTargetUrl(rawUrl: String): String {
        val uri = try { Uri.parse(rawUrl) } catch (e: Exception) { return rawUrl }
        val host = uri.host?.lowercase() ?: return rawUrl
        val scheme = uri.scheme?.lowercase() ?: "http"

        if (uri.port != -1 || (scheme != "http" && scheme != "https")) {
            return rawUrl
        }

        val cached = getCachedUrl(rawUrl)
        if (cached != null) {
            return cached
        }

        return withContext(Dispatchers.IO) {
            // 优先检查 HTTPS Type 65 记录
            val httpsPort = queryUdpDnsPort(host, 65) ?: queryDohPort(host)
            if (httpsPort != null && httpsPort in 1..65535 && httpsPort != 443) {
                setDefaultPort("https", host, httpsPort)
                val path = uri.encodedPath ?: ""
                val query = if (uri.encodedQuery != null) "?${uri.encodedQuery}" else ""
                val fragment = if (uri.encodedFragment != null) "#${uri.encodedFragment}" else ""
                "https://$host:$httpsPort$path$query$fragment"
            } else if (scheme == "https") {
                setDefaultPort("https", host, 443)
                rawUrl
            } else {
                // HTTP 探测 Type 64 记录
                val httpPort = queryUdpDnsPort("_http._tcp.$host", 64)
                if (httpPort != null && httpPort in 1..65535 && httpPort != 80) {
                    setDefaultPort("http", host, httpPort)
                    val path = uri.encodedPath ?: ""
                    val query = if (uri.encodedQuery != null) "?${uri.encodedQuery}" else ""
                    val fragment = if (uri.encodedFragment != null) "#${uri.encodedFragment}" else ""
                    "http://$host:$httpPort$path$query$fragment"
                } else {
                    setDefaultPort("http", host, 80)
                    rawUrl
                }
            }
        }
    }

    /**
     * 双通道原生 UDP 探测：优先国内低延迟 DNS（223.5.5.5），备选 1.1.1.1
     */
    private fun queryUdpDnsPort(domain: String, qtype: Int): Int? {
        val servers = listOf("223.5.5.5", "1.1.1.1")
        for (serverIp in servers) {
            val port = queryUdpDnsPortFromServer(domain, qtype, serverIp)
            if (port != null) return port
        }
        return null
    }

    private fun queryUdpDnsPortFromServer(domain: String, qtype: Int, serverIp: String): Int? {
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
            socket.soTimeout = 700

            val dnsServer = InetAddress.getByName(serverIp)
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
        val dohUrls = listOf(
            "https://dns.alidns.com/resolve?name=$domain&type=HTTPS",
            "https://1.1.1.1/dns-query?name=$domain&type=HTTPS"
        )
        for (dohUrl in dohUrls) {
            val port = queryDohPortFromUrl(dohUrl)
            if (port != null) return port
        }
        return null
    }

    private fun queryDohPortFromUrl(requestUrl: String): Int? {
        return try {
            val url = URL(requestUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/dns-json")
            conn.connectTimeout = 800
            conn.readTimeout = 800

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
     * 地址栏展示净化：
     * 只要端口属于该域名注册的“默认端口”，等同于 80 / 443，直接不在地址栏展示该端口。
     */
    fun cleanUrlForDisplay(url: String): String {
        if (url.isBlank()) return url
        val uri = try { Uri.parse(url) } catch (e: Exception) { return url }
        val port = uri.port
        if (port == -1) return url

        val host = uri.host?.lowercase() ?: return url
        val scheme = uri.scheme?.lowercase() ?: "http"

        if (isDefaultPort(scheme, host, port)) {
            val portPart = ":$port"
            val index = url.indexOf(portPart)
            if (index != -1) {
                return url.substring(0, index) + url.substring(index + portPart.length)
            }
        }
        return url
    }
}
