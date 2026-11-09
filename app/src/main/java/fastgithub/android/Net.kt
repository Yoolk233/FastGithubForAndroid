package fastgithub.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object Net {

    private fun readLimited(input: InputStream, maxBytes: Int): String {
        val buf = ByteArray(maxBytes)
        var off = 0
        while (off < maxBytes) {
            val n = input.read(buf, off, maxBytes - off)
            if (n <= 0) break
            off += n
        }
        return String(buf, 0, off, StandardCharsets.UTF_8)
    }

    /**
     * 只读前 maxBytes 个字节。
     * 原来的实现用 readBytes() 读完整包体，镜像测速碰到真实附件时会整包下载几百 MB。
     */
    private fun httpGet(url: String, timeoutMs: Int = 12000, maxBytes: Int = 64 * 1024): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", Config.UA)
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { readLimited(it, maxBytes) } ?: ""
            code to body
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    fun validIpv4(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            val v = p.toIntOrNull() ?: return@all false
            v in 0..255 && (p.length == 1 || !p.startsWith("0"))
        }
    }

    fun tcpDelay(ip: String, timeoutMs: Int): Long {
        val t0 = System.nanoTime()
        var s: Socket? = null
        return try {
            s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(ip, 443), timeoutMs)
            (System.nanoTime() - t0) / 1_000_000
        } catch (e: Exception) {
            -1
        } finally {
            runCatching { s?.close() }
        }
    }

    fun verify(host: String, ip: String, timeoutMs: Int): Node {
        val n = Node(host, ip)
        val t0 = System.nanoTime()
        var plain: Socket? = null
        var ssl: SSLSocket? = null
        try {
            plain = Socket()
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(ip, 443), timeoutMs)
            // 读超时不能比连接超时大太多，否则单次校验最坏要等 4s+8s+8s
            plain.soTimeout = timeoutMs.coerceAtLeast(4000)

            val f = SSLSocketFactory.getDefault() as SSLSocketFactory
            ssl = f.createSocket(plain, host, 443, true) as SSLSocket
            val params = ssl.sslParameters
            params.serverNames = listOf(SNIHostName(host))
            ssl.sslParameters = params
            ssl.soTimeout = timeoutMs.coerceAtLeast(4000)
            ssl.startHandshake()

            val req = "GET / HTTP/1.1\r\n" +
                "Host: $host\r\n" +
                "User-Agent: ${Config.UA}\r\n" +
                "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8\r\n" +
                "Accept-Language: zh-CN,zh;q=0.9,en;q=0.8\r\n" +
                "Connection: close\r\n\r\n"
            ssl.outputStream.write(req.toByteArray(StandardCharsets.US_ASCII))
            ssl.outputStream.flush()

            val rd = BufferedReader(InputStreamReader(ssl.inputStream, StandardCharsets.ISO_8859_1))
            val line = rd.readLine() ?: ""
            val code = Regex("HTTP/\\S+\\s+(\\d{3})").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            n.code = code
            n.ms = (System.nanoTime() - t0) / 1_000_000
            n.state = when {
                code in 200..399 -> "GOOD"
                code == 403 || code == 404 -> "WEAK"
                else -> "BAD"
            }
        } catch (e: Exception) {
            n.state = "FAIL"
            n.ms = (System.nanoTime() - t0) / 1_000_000
            n.code = 0
        } finally {
            runCatching { ssl?.close() }
            runCatching { plain?.close() }
        }
        return n
    }

    /** DoH 解析 A 记录，返回空格分隔的 IPv4 列表 */
    fun doH(domain: String, timeoutMs: Int = 10000): String {
        val eps = listOf(
            "https://dns.alidns.com/resolve?name=%s&type=A",
            "https://doh.pub/dns-query?name=%s&type=A"
        )
        val sb = StringBuilder()
        for (ep in eps) {
            try {
                val (code, body) = httpGet(ep.format(domain), timeoutMs, 32 * 1024)
                if (code in 200..299) {
                    val re = Regex("\"data\":\"((?:[0-9]{1,3}\\.){3}[0-9]{1,3})\"")
                    re.findAll(body).forEach { m ->
                        val ip = m.groupValues[1]
                        if (validIpv4(ip) && !sb.contains(ip)) {
                            if (sb.isNotEmpty()) sb.append(' ')
                            sb.append(ip)
                        }
                    }
                }
            } catch (_: Exception) {
            }
            if (sb.length > 60) break
        }
        return sb.toString().trim()
    }

    fun dohFirstIp(host: String, timeoutMs: Int = 6000): String {
        val ips = doH(host, timeoutMs).split(' ').map { it.trim() }.filter { validIpv4(it) }
        return ips.firstOrNull() ?: ""
    }

    /**
     * 镜像测速：只读 2KB，避免把真实附件整包拉下来。
     */
    fun probeMirror(prefix: String, sampleUrl: String): String {
        val t0 = System.nanoTime()
        return try {
            val url = MirrorStore.normalize(prefix) + sampleUrl
            val (code, _) = httpGet(url, 12000, 2048)
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (code in 200..399) "$code|$ms" else "ERR|HTTP $code|$ms"
        } catch (e: Exception) {
            val msg = e.message ?: "连接失败"
            "ERR|${msg.take(60)}|${(System.nanoTime() - t0) / 1_000_000}"
        }
    }

    /**
     * 取当前非 VPN 网络的系统 DNS。
     * 原实现把所有未知域名硬转发到 223.5.5.5，一旦该地址被网络屏蔽，
     * 开加速后除 21 个 GitHub 域名外全部解析失败。
     */
    @Suppress("DEPRECATION")
    fun systemResolvers(context: Context): List<String> {
        val out = LinkedHashSet<String>()
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                val lp = cm.getLinkProperties(n) ?: continue
                for (d in lp.dnsServers) {
                    if (d is Inet4Address && validIpv4(d.hostAddress ?: "")) out.add(d.hostAddress!!)
                }
            }
        } catch (_: Exception) {
        }
        return out.toList()
    }
}
