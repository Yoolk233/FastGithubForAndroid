package fastgithub.android.vpn

/**
 * 转发类 DNS 应答的小缓存。
 * 原来每个查询都新建 DatagramSocket 并回源，浏览器一次页面加载会打十几个重复查询，
 * 既慢又容易撞上上游限流。这里按 (name, qtype) 缓存 60 秒，容量固定，超了淘汰最旧的。
 */
class DnsCache(private val maxEntries: Int = 256, private val ttlMs: Long = 60_000L) {

    private class Entry(val payload: ByteArray, val at: Long)

    private val map = LinkedHashMap<String, Entry>()
    private var hits = 0
    private var misses = 0

    private fun key(name: String, type: Int) = "$type|$name"

    @Synchronized
    fun get(name: String, type: Int): ByteArray? {
        val k = key(name, type)
        val e = map[k]
        if (e == null) {
            misses++
            return null
        }
        if (System.currentTimeMillis() - e.at > ttlMs) {
            map.remove(k)
            misses++
            return null
        }
        hits++
        return e.payload
    }

    @Synchronized
    fun put(name: String, type: Int, payload: ByteArray) {
        if (payload.size > 1400) return
        map[key(name, type)] = Entry(payload.copyOf(), System.currentTimeMillis())
        while (map.size > maxEntries) {
            val it = map.entries.iterator()
            if (!it.hasNext()) break
            it.next()
            it.remove()
        }
    }

    @Synchronized
    fun stats(): String = "缓存 $hits 命中 / $misses 回源"

    @Synchronized
    fun clear() {
        map.clear()
        hits = 0
        misses = 0
    }
}
