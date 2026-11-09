package fastgithub.android.vpn

object DnsPacket {

    private const val TYPE_A = 1

    data class Query(
        val id: Int,
        val name: String,
        val qtype: Int,
        val questionEnd: Int
    )

    /** 解析 DNS 查询报文，只关心第一个 question */
    fun parseQuery(buf: ByteArray, len: Int): Query? {
        if (len < 12) return null
        // QR=1 是应答报文，不是新查询（隧道里偶尔会读到它，直接忽略）
        if ((buf[2].toInt() and 0x80) != 0) return null
        val qd = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
        if (qd < 1) return null

        var p = 12
        val sb = StringBuilder()
        while (p < len) {
            val l = buf[p].toInt() and 0xFF
            if (l == 0) {
                p += 1
                break
            }
            if ((l and 0xC0) != 0) return null
            if (p + 1 + l > len) return null
            sb.append(String(buf, p + 1, l, Charsets.US_ASCII))
            sb.append('.')
            p += 1 + l
        }
        if (p + 4 > len) return null
        val qtype = ((buf[p].toInt() and 0xFF) shl 8) or (buf[p + 1].toInt() and 0xFF)
        val name = sb.toString().trimEnd('.').lowercase()
        if (name.isEmpty()) return null
        return Query(
            id = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF),
            name = name,
            qtype = qtype,
            questionEnd = p + 4
        )
    }

    /** 构造一条 A 记录应答（question 段原样复制，answer 用 0xC00C 指针指回域名） */
    fun buildAResponse(queryBuf: ByteArray, queryLen: Int, q: Query, ip: String): ByteArray {
        val questionLen = q.questionEnd - 12
        if (questionLen <= 0 || q.questionEnd > queryLen) return ByteArray(0)

        val out = ByteArray(12 + questionLen + 16)
        out[0] = (q.id shr 8).toByte()
        out[1] = (q.id and 0xFF).toByte()
        out[2] = 0x81.toByte()
        out[3] = 0x80.toByte()
        out[4] = 0
        out[5] = 1
        out[6] = 0
        out[7] = 1
        // 8..11 NSCOUNT / ARCOUNT = 0
        System.arraycopy(queryBuf, 12, out, 12, questionLen)

        var p = 12 + questionLen
        out[p++] = 0xC0.toByte()
        out[p++] = 0x0C.toByte()
        out[p++] = 0
        out[p++] = TYPE_A.toByte()
        out[p++] = 0
        out[p++] = 1
        out[p++] = 0
        out[p++] = 0
        out[p++] = 0
        out[p++] = 60 // TTL 60s
        out[p++] = 0
        out[p++] = 4
        for (b in ipToBytes(ip)) out[p++] = b
        return out
    }

    /** 构造 NODATA（域名存在但没有该类型记录）：用于把 AAAA 查询挡掉，强制走 A 记录 */
    fun buildNoData(queryBuf: ByteArray, queryLen: Int, q: Query): ByteArray {
        val questionLen = q.questionEnd - 12
        if (questionLen <= 0 || q.questionEnd > queryLen) return ByteArray(0)
        val out = ByteArray(12 + questionLen)
        out[0] = (q.id shr 8).toByte()
        out[1] = (q.id and 0xFF).toByte()
        out[2] = 0x81.toByte()
        out[3] = 0x80.toByte()
        out[4] = 0
        out[5] = 1
        out[6] = 0
        out[7] = 0
        System.arraycopy(queryBuf, 12, out, 12, questionLen)
        return out
    }

    /** 缓存命中时把报文的事务 ID 改回当前查询的 ID */
    fun withId(payload: ByteArray, id: Int): ByteArray {
        if (payload.size < 12) return payload
        val c = payload.copyOf()
        c[0] = (id shr 8).toByte()
        c[1] = (id and 0xFF).toByte()
        return c
    }

    /** 构造 SERVFAIL（上游超时/失败时） */
    fun buildServFail(queryBuf: ByteArray, queryLen: Int, q: Query): ByteArray {
        val questionLen = q.questionEnd - 12
        if (questionLen <= 0 || q.questionEnd > queryLen) return ByteArray(0)
        val out = ByteArray(12 + questionLen)
        out[0] = (q.id shr 8).toByte()
        out[1] = (q.id and 0xFF).toByte()
        out[2] = 0x81.toByte()
        out[3] = 0x82.toByte() // SERVFAIL
        out[4] = 0
        out[5] = 1
        System.arraycopy(queryBuf, 12, out, 12, questionLen)
        return out
    }

    private fun ipToBytes(ip: String): ByteArray {
        val parts = ip.split('.')
        val b = ByteArray(4)
        for (i in 0 until 4) b[i] = (parts.getOrNull(i)?.toIntOrNull() ?: 0).toByte()
        return b
    }

    /** 从 tun 读到的 IPv4 包中取出 UDP + payload；返回 null 表示不是发给本地 DNS 的包 */
    class UdpInbound(
        val srcIp: ByteArray,
        val srcPort: Int,
        val dstIp: ByteArray,
        val dstPort: Int,
        val payloadOffset: Int,
        val payloadLength: Int
    )

    fun parseUdp(packet: ByteArray, len: Int, localIp: String): UdpInbound? {
        if (len < 20) return null
        val version = (packet[0].toInt() and 0xF0) shr 4
        if (version != 4) return null
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (len < ihl + 8) return null
        val proto = packet[9].toInt() and 0xFF
        if (proto != 17) return null

        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        if (dstIp.joinToString(".") { (it.toInt() and 0xFF).toString() } != localIp) return null

        val sp = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dp = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        val udpLen = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)
        val payloadOff = ihl + 8
        var payloadLen = udpLen - 8
        if (payloadLen <= 0) return null
        if (payloadOff + payloadLen > len) payloadLen = len - payloadOff
        return UdpInbound(srcIp, sp, dstIp, dp, payloadOff, payloadLen)
    }

    /** 构造从 localIp:53 回给客户端的 UDP 包 */
    fun buildUdpReply(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val total = 20 + 8 + payload.size
        val out = ByteArray(total)
        out[0] = 0x45
        out[1] = 0
        out[2] = (total shr 8).toByte()
        out[3] = (total and 0xFF).toByte()
        out[4] = 0
        out[5] = 0
        out[6] = 0x40
        out[7] = 0
        out[8] = 64
        out[9] = 17
        // 10..11 checksum 稍后填
        System.arraycopy(srcIp, 0, out, 12, 4)
        System.arraycopy(dstIp, 0, out, 16, 4)

        out[20] = (srcPort shr 8).toByte()
        out[21] = (srcPort and 0xFF).toByte()
        out[22] = (dstPort shr 8).toByte()
        out[23] = (dstPort and 0xFF).toByte()
        val udpLen = 8 + payload.size
        out[24] = (udpLen shr 8).toByte()
        out[25] = (udpLen and 0xFF).toByte()
        out[26] = 0
        out[27] = 0 // UDP checksum 0 = 不校验（IPv4 允许）
        System.arraycopy(payload, 0, out, 28, payload.size)

        val sum = ipChecksum(out, 0, 20)
        out[10] = ((sum shr 8) and 0xFF).toByte()
        out[11] = (sum and 0xFF).toByte()
        return out
    }

    private fun ipChecksum(buf: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len) {
            val hi = buf[i].toInt() and 0xFF
            val lo = if (i + 1 < off + len) buf[i + 1].toInt() and 0xFF else 0
            sum += ((hi shl 8) or lo).toLong()
            i += 2
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}
