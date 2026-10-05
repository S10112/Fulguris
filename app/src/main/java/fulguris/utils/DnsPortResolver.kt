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
    private val portCache = ConcurrentHashMap<String, Int>()

    init {
        // 本地保底基准规则（0ms 兜底，防止极端无网或 DNS 污染时超时）
        portCache["http:g.6z.ee"] = 802
        portCache["https:g.6z.ee"] = 803
    }

    fun getCachedUrl(rawUrl: String): String? {
        val uri = try { Uri.parse(rawUrl) } catch (e: Exception) { return null }
        val host = uri.host?.lowercase() ?: return null
        val scheme = uri.scheme?.lowercase() ?: "http"

        if (uri.port != -1 || (scheme != "http" && scheme != "https")) return null

        var targetScheme = scheme
        var targetPort = portCache["$scheme:$host"]

        if (targetPort == null && scheme == "http") {
            val httpsPort = portCache["https:$host"]
            if (httpsPort != null && httpsPort != 443) {
                targetScheme = "https"
                targetPort = httpsPort
            }
        }

        if (targetPort == null) return null

        if ((targetScheme == "http" && targetPort == 80) || (targetScheme == "https" && targetPort == 443)) {
            return rawUrl
        }

        val path = uri.encodedPath ?: ""
        val query = if (uri.encodedQuery != null) "?${uri.encodedQuery}" else ""
        val fragment = if (uri.encodedFragment != null) "#${uri.encodedFragment}" else ""
        return "$targetScheme://$host:$targetPort$path$query$fragment"
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

        // 通道 1：UDP 探测 Type 65 (HTTPS) 记录
        targetPort = queryUdpDnsPort(host, 65)

        // 通道 2：UDP 探测 Type 64 (SVCB) 记录
        if (targetPort == null && scheme == "http") {
            targetPort = queryUdpDnsPort("_http._tcp.$host", 64)
        }

        // 通道 3：DoH 接口兜底探测 (向 Cloudflare 权威节点直接获取 JSON)
        if (targetPort == null) {
            targetPort = queryDohPort(host)
        }

        if (targetPort != null && targetPort > 0) {
            portCache["$scheme:$host"] = targetPort
            if (scheme == "http" && targetPort == 803) {
                // 如果只配了 803，自动将 HTTP 升级至 HTTPS
                portCache["https:$host"] = 803
                val path = uri.encodedPath ?: ""
                val query = if (uri.encodedQuery != null) "?${uri.encodedQuery}" else ""
                val fragment = if (uri.encodedFragment != null) "#${uri.encodedFragment}" else ""
                return@withContext "https://$host:803$path$query$fragment"
            }
        } else {
            targetPort = if (scheme == "https") 443 else 80
            portCache["$scheme:$host"] = targetPort
        }

        if ((scheme == "http" && targetPort == 80) || (scheme == "https" && targetPort == 443)) {
            return@withContext rawUrl
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

            // 优先使用 Cloudflare DNS 节点查询
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
                        // 检索 port=xxxx 字段
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

    fun cleanUrlForDisplay(url: String): String {
        if (url.isBlank()) return url
        var cleaned = url.replace(":803", "").replace(":802", "")
        cleaned = cleaned.replace(Regex(""":(80[0-9]|808[0-9]|80|443)(?=[/?#]|$)"""), "")
        return cleaned
    }
}
