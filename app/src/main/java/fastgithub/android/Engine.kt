package fastgithub.android

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object MirrorStore {
    private fun savePath() = File(Config.filesDir, "mirror.txt")

    fun load(): String {
        return runCatching {
            val p = savePath()
            if (p.exists()) {
                val s = p.readText().trim()
                if (s.isNotEmpty()) return normalize(s)
            }
            normalize(Config.Mirror)
        }.getOrDefault(normalize(Config.Mirror))
    }

    fun save(prefix: String) {
        runCatching { savePath().writeText(normalize(prefix)) }
    }

    fun normalize(prefix: String?): String {
        var s = (prefix ?: "").trim()
        if (s.isEmpty()) return Config.Mirror
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
        if (!s.endsWith("/")) s += "/"
        return s
    }

    fun apply(prefix: String, url: String): String {
        var u = strip(url)
        if (u.isEmpty()) return u
        // 用户直接粘 "github.com/xxx" 这种没写协议的地址时补上 https://，否则拼出来不成链接
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
        return normalize(prefix) + u
    }

    private fun looksLikeGithubUrlPrefix(host: String): Boolean {
        val h = host.lowercase()
        return h.endsWith("github.com") || h.endsWith("githubusercontent.com") || h.endsWith("github.io")
    }

    private val schemeRe = Regex("https?://", RegexOption.IGNORE_CASE)
    private val hostRe = Regex("^https?://([^/]+)/", RegexOption.IGNORE_CASE)

    /**
     * 剥掉镜像前缀，回到原始 GitHub 链接。
     * 原实现只认 6 个预设前缀 + 只认 github.com：自定义前缀配 raw / api / codeload
     * 这类链接时剥不干净，再点一次"转换"就会套两层前缀。
     */
    fun strip(url: String?): String {
        var u = (url ?: "").trim()
        if (u.isEmpty()) return u

        // 1) 命中的预设前缀（可能叠了多层）
        var guard = 0
        while (guard++ < 4) {
            val hit = Config.mirrorPresets.firstOrNull { u.startsWith(it, true) }
            if (hit == null) break
            val rest = u.substring(hit.length)
            if (rest.isEmpty()) break
            u = rest
        }

        // 2) 任意自定义前缀：https://任意前缀/https://<github 系域名>/...
        guard = 0
        while (guard++ < 4) {
            val m = schemeRe.find(u, 1) ?: break
            val outer = u.substring(0, m.range.first)
            if (outer.any { it.isWhitespace() }) break
            val rest = u.substring(m.range.first)
            val host = hostRe.find(rest)?.groupValues?.getOrNull(1) ?: break
            if (!looksLikeGithubUrlPrefix(host)) break
            u = rest
        }

        // 3) 兜底：链接里直接出现 github.com 的绝对地址
        val i1 = u.indexOf("https://github.com/", 0, true)
        if (i1 > 0) return u.substring(i1)
        val i2 = u.indexOf("http://github.com/", 0, true)
        if (i2 > 0) return u.substring(i2)
        return u
    }
}

object Engine {
    fun resolveOne(host: String): Node {
        val cands = mutableListOf<String>()
        cands.addAll(Config.poolOf(host))
        val doh = Net.doH(host)
        for (p in doh.split(' ')) {
            val s = p.trim()
            if (s.length > 6 && !cands.contains(s)) cands.add(s)
        }

        cands.removeAll { Config.ipBlacklist.contains(it) }

        // 候选 IP 的 TCP 初筛并行走，别一个城一个地串（最坏 11 个候选 × 1.5s 会拖到十几秒）
        val fast = mutableListOf<Pair<String, Long>>()
        val pool = Executors.newFixedThreadPool(cands.size.coerceIn(1, 12))
        val futures = cands.map { ip ->
            pool.submit(java.util.concurrent.Callable { ip to Net.tcpDelay(ip, 1500) })
        }
        for (f in futures) {
            val pair = runCatching { f.get(3, TimeUnit.SECONDS) }.getOrNull() ?: continue
            if (pair.second > 0) fast.add(pair)
        }
        pool.shutdownNow()
        fast.sortBy { it.second }

        val limit = minOf(fast.size, 6)
        var best: Node? = null
        for (i in 0 until limit) {
            val n = Net.verify(host, fast[i].first, 4000)
            // 网页型域名只认 200~399：WEAK(403/404) 既不能进 DNS，也不能算"加速成功"
            if (n.acceptable) {
                if (best == null) best = n
                else if (n.state == "GOOD" && best.state != "GOOD") best = n
                else if (n.state == best.state && n.ms < best.ms) best = n
            }
            if (n.state == "GOOD" && n.ms < 600) return n
        }
        if (best == null && fast.isNotEmpty()) {
            // 兜底也只认通过校验的结果；硬写一个没验过的 IP 就是"已加速但打不开"
            val fb = Net.verify(host, fast[0].first, 5000)
            if (fb.acceptable) return fb
        }
        if (best == null) {
            val nf = Node(host)
            nf.state = "FAIL"
            return nf
        }
        return best
    }

    /**
     * 巡检当前 mapping 是否还能用：核心域名只要有一个打不开，就要整轮重测。
     * 原实现只在「mapping 没填满」时才复测，填满之后（哪怕全是死 IP）就永远不再检查，
     * 于是界面一直写着"加速已生效"，实际 GitHub 早就打不开了。
     */
    fun healthCheck(mapping: Map<String, String>): List<String> {
        val bad = ArrayList<String>()
        for (h in Config.coreDomains) {
            val ip = mapping[h]
            if (ip.isNullOrEmpty()) {
                bad.add(h)
                continue
            }
            val n = Net.verify(h, ip, 3000)
            if (!n.acceptable) bad.add(h)
        }
        return bad
    }

    /** 快速路径：DoH + 并行 TCP 初筛，几百毫秒级给出一个能用 IP，保证开启后立刻生效 */
    fun quickPick(host: String): Node {
        val cands = LinkedHashSet<String>()
        cands.addAll(Config.poolOf(host))
        for (p in Net.doH(host).split(' ')) if (p.isNotBlank()) cands.add(p)
        cands.removeAll { Config.ipBlacklist.contains(it) }
        if (cands.isEmpty()) return Node(host).apply { state = "FAIL" }

        val pool = Executors.newFixedThreadPool(cands.size.coerceAtMost(12))
        val futures = cands.map { ip -> pool.submit(java.util.concurrent.Callable { ip to Net.tcpDelay(ip, 1800) }) }
        var bestIp = ""
        var bestMs = Long.MAX_VALUE
        for (f in futures) {
            // 单个探测卡住不能拖死整批：原来 f.get() 没有超时
            val pair = runCatching { f.get(3, TimeUnit.SECONDS) }.getOrNull() ?: continue
            val (ip, d) = pair
            if (d > 0 && d < bestMs) {
                bestMs = d
                bestIp = ip
            }
        }
        pool.shutdownNow()
        if (bestIp.isEmpty()) {
            return Node(host).apply { state = "FAIL" }
        }
        // TCP 能连 ≠ 页面能开：最快的那一个必须再过一次 TLS + HTTP 才算数，
        // 否则 QUICK 状态会被拿去应答，出现"加速已生效、GitHub 404"。
        val verified = Net.verify(host, bestIp, 2500)
        return if (verified.acceptable) verified else Node(host).apply { state = "FAIL" }
    }

    fun quickMapAll(degree: Int, onNode: (Node) -> Unit): Map<String, String> {
        val res = ConcurrentHashMap<String, String>()
        val pool = Executors.newFixedThreadPool(degree.coerceAtLeast(1))
        val latch = CountDownLatch(Config.domains.size)
        for (h in Config.domains) {
            pool.execute {
                try {
                    val n = quickPick(h)
                    if (n.acceptable && n.ip.isNotEmpty()) res[h] = n.ip
                    runCatching { onNode(n) }
                } catch (_: Exception) {
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(60, TimeUnit.SECONDS)
        pool.shutdownNow()
        pool.awaitTermination(3, TimeUnit.SECONDS)
        return res
    }

    fun probeAll(degree: Int, onNode: (Node) -> Unit): Map<String, Node> {
        val map = ConcurrentHashMap<String, Node>()
        val pool = Executors.newFixedThreadPool(degree.coerceAtLeast(1))
        val latch = CountDownLatch(Config.domains.size)
        for (h in Config.domains) {
            pool.execute {
                try {
                    val n = resolveOne(h)
                    map[h] = n
                    runCatching { onNode(n) }
                } catch (_: Exception) {
                } finally {
                    latch.countDown()
                }
            }
        }
        // 90s 低于最坏耗时（21 域名 × 最多 6 个候选 × 4s）；放宽到 120s，超时也能保留已完成的结果
        latch.await(120, TimeUnit.SECONDS)
        pool.shutdownNow()
        pool.awaitTermination(3, TimeUnit.SECONDS)
        return map
    }
}
