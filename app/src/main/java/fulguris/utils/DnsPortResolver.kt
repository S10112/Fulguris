package fulguris.utils

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

object DnsPortResolver {
    // 内存高速映射缓存："$scheme:$host" -> 目标端口 (如 "https:g.6z.ee" -> 803)
    private val portCache = ConcurrentHashMap<String, Int>()

    /**
     * 极速同步查询（已缓存域名 0ms 瞬间返回重写 URL，未缓存返回 null）
     */
    fun getCachedUrl(rawUrl: String): String? {
        val uri = try { Uri.parse(rawUrl) } catch (e: Exception) { return null }
        val host = uri.host?.lowercase() ?: return null
        val scheme = uri.scheme?.lowercase() ?: "http"

        if (uri.port != -1 || (scheme != "http" && scheme != "https")) return null

        var targetScheme = scheme
        var targetPort = portCache["$scheme:$host"]

        // 容错机制：若请求为 http，但该域名已缓存了 https 对应端口，自动升级为 https
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

    /**
     * 核心全动态解析：
     * 先查 DNS（RFC 9460 Type 65 HTTPS / Type 64 SVCB），若解析出非标端口则接管 80/443，普通网站原样放行
     */
    suspend fun resolveTargetUrl(rawUrl: String): String = withContext(Dispatchers.IO) {
        val uri = try { Uri.parse(rawUrl) } catch (e: Exception) { return@withContext rawUrl }
        val host = uri.host?.lowercase() ?: return@withContext rawUrl
        val scheme = uri.scheme?.lowercase() ?: "http"

        if (uri.port != -1 || (scheme != "http" && scheme != "https")) {
            return@withContext rawUrl
        }

        // 1. 优先从内存缓存中获取（0ms 直通）
        val cached = getCachedUrl(rawUrl)
        if (cached != null) {
            return@withContext cached
        }

        // 2. 动态发起 UDP 53 查询 RFC 9460 报文
        val cacheKey = "$scheme:$host"
        var targetPort = portCache[cacheKey]

        if (targetPort == null) {
            if (scheme == "https") {
                targetPort = queryUdpDnsPort(host, 65)
            } else {
                targetPort = queryUdpDnsPort("_http._tcp.$host", 64)
                // 许多站长仅在 Cloudflare 配置了 HTTPS (Type 65) 端口
                if (targetPort == null) {
                    val httpsPort = queryUdpDnsPort(host, 65)
                    if (httpsPort != null && httpsPort > 0) {
                        portCache["https:$host"] = httpsPort
                        val path = uri.encodedPath ?: ""
                        val query = if (uri.encodedQuery != null) "?${uri.encodedQuery}" else ""
                        val fragment = if (uri.encodedFragment != null) "#${uri.encodedFragment}" else ""
                        return@withContext "https://$host:$httpsPort$path$query$fragment"
                    }
                }
            }

            if (targetPort != null && targetPort > 0) {
                portCache[cacheKey] = targetPort
            } else {
                // 普通域名标记为默认 80 或 443，后续不再重复发包
                targetPort = if (scheme == "https") 443 else 80
                portCache[cacheKey] = targetPort
            }
        }

        // 3. 普通网站原样返回，完全不干扰常规网站
        if ((scheme == "http" && targetPort == 80) || (scheme == "https" && targetPort == 443)) {
            return@withContext rawUrl
        }

        // 4. 接管并改写为从 DNS 解析出的非标端口
        val path = uri.encodedPath ?: ""
        val query = if (uri.encodedQuery != null) "?${uri.encodedQuery}" else ""
        val fragment = if (uri.encodedFragment != null) "#${uri.encodedFragment}" else ""
        return@withContext "$scheme://$host:$targetPort$path$query$fragment"
    }

    /**
     * 原生 UDP 53 数据包直接解析 RFC 9460 (SVCB / HTTPS) 报文
     * 超时设为 800ms，抗网络休眠与冷启动抖动
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
            socket.soTimeout = 800

            val dnsServer = InetAddress.getByName("119.29.29.29")
            val sendPacket = DatagramPacket(requestData, requestData.size, dnsServer, 53)
            socket.send(sendPacket)

            val buffer = ByteArray(2048)
            val receivePacket = DatagramPacket(buffer, buffer.size)
            socket.receive(receivePacket)

            val len = receivePacket.length
            // 扫描 SvcParamKey port (0x0003) 且长度为 2 (0x0002)
            for (i in 12 until (len - 5)) {
                if (buffer[i] == 0x00.toByte() && buffer[i + 1] == 0x03.toByte() &&
                    buffer[i + 2] == 0x00.toByte() && buffer[i + 3] == 0x02.toByte()
                ) {
                    val high = buffer[i + 4].toInt() and 0xFF
                    val low = buffer[i + 5].toInt() and 0xFF
                    val port = (high shl 8) or low
                    if (port in 1..65535) {
                        return port
                    }
                }
            }
        } catch (e: Exception) {
            // 超时或失败静默跳过
        } finally {
            try { socket?.close() } catch (ignored: Exception) {}
        }
        return null
    }

    /**
     * 地址栏净化：无论后台连接哪个非标端口，前台地址栏始终呈现干净无端口的标准格式
     */
    fun cleanUrlForDisplay(url: String): String {
        if (url.isBlank()) return url
        var cleaned = url.replace(":803", "").replace(":802", "")
        cleaned = cleaned.replace(Regex(""":(80[0-9]|808[0-9]|80|443)(?=[/?#]|$)"""), "")
        return cleaned
    }
}
