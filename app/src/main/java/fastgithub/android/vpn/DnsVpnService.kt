package fastgithub.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import fastgithub.android.AppState
import fastgithub.android.Config
import fastgithub.android.Engine
import fastgithub.android.MainActivity
import fastgithub.android.MappingCache
import fastgithub.android.Net
import fastgithub.android.R
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class DnsVpnService : VpnService() {

    companion object {
        const val ACTION_START = "fastgithub.START"
        const val ACTION_STOP = "fastgithub.STOP"
        const val ACTION_PROBE = "fastgithub.PROBE"
        private const val CHANNEL_ID = "fastgithub"
        private const val NOTIFY_ID = 1001
        const val DNS_IP = "10.111.0.1"

        private val FALLBACK_RESOLVERS = listOf("223.5.5.5", "119.29.29.29", "114.114.114.114")

        /** 供 UI 判断服务是否仍在运行（重开 App 时同步状态用） */
        @Volatile
        var isActive = false
    }

    private var tun: ParcelFileDescriptor? = null
    private var readThread: Thread? = null
    private var probeThread: Thread? = null
    private val workers = Executors.newFixedThreadPool(6)
    private val cache = DnsCache()

    /** DoH 熔断时间戳：公共 DoH 被网络屏蔽时不要每条查询都白等 */
    @Volatile
    private var dohDownUntil = 0L

    @Volatile
    private var running = false

    @Volatile
    private var resolvers: List<String> = FALLBACK_RESOLVERS

    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /** 同一时刻只跑一次测速；手动测速与周期测速共用一个闸门 */
    private val probeLock = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 转发用的 socket 按线程复用，避免每个查询都新建；统一登记以便停止时关闭 */
    private val allSockets = CopyOnWriteArrayList<DatagramSocket>()
    private val sockets = object : ThreadLocal<HashMap<String, DatagramSocket>>() {
        override fun initialValue(): HashMap<String, DatagramSocket> = HashMap()
    }

    override fun onCreate() {
        super.onCreate()
        Config.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVpn()
            ACTION_PROBE -> if (running) {
                requestProbe()
            } else {
                AppState.log("加速未开启，忽略重新测速请求")
                stopSelf()
            }
            else -> startVpn()
        }
        return START_STICKY
    }

    // ---------- 开启 ----------

    private fun startVpn() {
        if (running) return

        AppState.phase.value = AppState.Phase.CONNECTING
        AppState.status.value = "正在建立加速隧道"
        AppState.detail.value = "首次开启时，系统会弹出 VPN 连接请求，允许即可"

        createChannel()
        startForegroundCompat("正在建立 DNS 隧道…")

        // 必须在 establish 之前取：VPN 起来之后 activeNetwork 会变成我们自己的隧道
        resolvers = Net.systemResolvers(this).ifEmpty { FALLBACK_RESOLVERS }.take(4)

        val builder = Builder()
            .setSession("FastGithub")
            .setMtu(1500)
            .addAddress("10.111.0.2", 30)
            .addRoute("10.111.0.0", 30)
            .addDnsServer(DNS_IP)
        runCatching { builder.addDisallowedApplication(packageName) }

        val pfd = runCatching { builder.establish() }.getOrNull()
        if (pfd == null) {
            AppState.log("建立 VPN 失败：可能已有其它 VPN 在运行")
            AppState.markError("建立 VPN 失败：手机上同一时间只能有一个 VPN，请先关掉其它 VPN / 代理应用")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        tun = pfd
        running = true
        isActive = true
        AppState.running.value = true
        AppState.phase.value = AppState.Phase.PROBING
        AppState.status.value = "加速中（正在优选节点）"

        val cached = MappingCache.load()
        if (cached.isNotEmpty()) {
            AppState.mapping.value = cached
            cached.forEach { (h, ip) -> AppState.upsertNode(fastgithub.android.Node(h, ip).apply { state = "CACHE" }) }
            AppState.detail.value = "已接管 ${cached.size} 个域名（沿用上次结果），正在校验…"
            AppState.log("沿用上次优选结果：${cached.size} 个域名")
        } else {
            AppState.detail.value = "正在优选节点，几秒后生效"
        }
        AppState.log("DNS 隧道已建立，上游 ${resolvers.joinToString("/")}")

        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)

        readThread = Thread {
            val buf = ByteArray(4096)
            while (running) {
                val n = try {
                    input.read(buf)
                } catch (e: Exception) {
                    if (!running) break
                    Thread.sleep(50)
                    continue
                }
                if (n <= 0) {
                    if (!running) break
                    continue
                }
                val copy = buf.copyOf(n)
                runCatching { workers.execute { handlePacket(copy, n, output) } }
            }
        }.also { it.isDaemon = true; it.start() }

        registerNetCallback()
        startProbe()
    }

    // ---------- 探针 ----------

    /**
     * 用户在界面上点「重新测速」。
     * 不能走 startProbe：周期线程跑完一轮后会睡 10 分钟，线程还是 alive 的，
     * 原来的 startProbe 会直接 return，手动测速就完全没反应。
     */
    private fun requestProbe() {
        if (!running) return
        runCatching { workers.execute { probeOnce() } }
    }

    private fun startProbe() {
        if (probeThread?.isAlive == true) return
        probeThread = Thread {
            try {
                if (AppState.mapping.value.isEmpty()) {
                    AppState.busy.value = true
                    val quick = runCatching {
                        Engine.quickMapAll(8) { n -> AppState.upsertNode(n) }
                    }.getOrDefault(emptyMap())
                    if (running && quick.isNotEmpty()) {
                        // 快速结果已经过 TLS+HTTP 校验，直接替换掉可能已失效的上次缓存
                        AppState.mapping.value = quick
                        AppState.detail.value =
                            "已接管 ${quick.size}/${Config.domains.size} 个域名（快速模式），正在精确优选…"
                        AppState.log("快速解析完成：${quick.size} 个域名已生效")
                    }
                }
                probeOnce()
                while (running) {
                    // 原来睡 10 分钟，且只在"mapping 没填满"时才复测——填满之后就永不检查，
                    // 死节点会一直留在应答里。改成固定周期做健康巡检，失效即整轮重测。
                    Thread.sleep(3 * 60 * 1000L)
                    if (!running) break
                    val bad = runCatching { Engine.healthCheck(AppState.mapping.value) }
                        .getOrDefault(emptyList())
                    if (bad.isNotEmpty()) {
                        AppState.log("巡检发现 " + bad.size + " 个核心域名失效（" + bad.joinToString("、") + "），重新优选…")
                        probeOnce()
                    }
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                AppState.log("探针线程异常：" + (e.message ?: e.javaClass.simpleName))
            } finally {
                AppState.busy.value = false
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun probeOnce() {
        if (!probeLock.compareAndSet(false, true)) {
            AppState.log("已有测速任务在进行，忽略这次重复请求")
            return
        }
        AppState.busy.value = true
        AppState.log("开始探测 ${Config.domains.size} 个域名…")
        try {
            val map = runCatching {
                Engine.probeAll(6) { n -> AppState.upsertNode(n) }
            }.getOrDefault(emptyMap())

            val usable = map.filterValues { it.acceptable && it.ip.isNotEmpty() }.mapValues { it.value.ip }
            if (!running) return

            // 以本轮结果为准，不再和上一轮做加法：
            // 探测失败的域名必须从 mapping 里移除、回退给真实 DNS，
            // 否则死 IP 会一直留在应答里，界面写着"已加速"、网页却打不开。
            AppState.mapping.value = usable
            if (usable.isNotEmpty()) MappingCache.save(usable)

            val count = usable.size
            AppState.detail.value = if (count > 0) {
                "已接管 $count/${Config.domains.size} 个域名，浏览器可直接打开 GitHub"
            } else {
                "没有探测到可用节点：检查网络后点「重新测速」"
            }
            AppState.status.value = if (count > 0) "加速已生效" else "加速中（暂无可用节点）"
            AppState.log("优选完成：$count/${Config.domains.size} 可用，${cache.stats()}")
            AppState.markReady(count)
            if (count == 0) AppState.phase.value = AppState.Phase.ERROR
            runCatching { updateNotification(count) }
        } finally {
            AppState.busy.value = false
            probeLock.set(false)
        }
    }

    // ---------- DNS 处理 ----------

    private fun handlePacket(packet: ByteArray, len: Int, output: FileOutputStream) {
        val udp = DnsPacket.parseUdp(packet, len, DNS_IP) ?: return
        val payload = try {
            packet.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)
        } catch (e: Exception) {
            return
        }
        val q = DnsPacket.parseQuery(payload, payload.size) ?: return

        val mapped = AppState.mapping.value[q.name]
        val resp: ByteArray = when {
            // 被接管的域名：A 记录直接回优选 IP
            q.qtype == 1 && mapped != null -> DnsPacket.buildAResponse(payload, payload.size, q, mapped)

            // AAAA 必须挡掉：否则浏览器拿真实 IPv6 走 Happy Eyeballs，绕过加速甚至更慢
            q.qtype == 28 && (mapped != null || Config.isGithubHost(q.name)) ->
                DnsPacket.buildNoData(payload, payload.size, q)

            else -> {
                val hit = cache.get(q.name, q.qtype)
                if (hit != null) {
                    DnsPacket.withId(hit, q.id)
                } else {
                    // 非 GitHub 域名：DoH 优先，明文 53 只做兜底。
                    // 原来是"明文优先、DoH 兜底"，等于替开着「私人 DNS」的用户把保护拆掉——
                    // 上游 DNS 被篡改时（家用路由器 / 机场客户端），其它网站的解析就会被喂成广告页。
                    val built = dohPrefer(payload, payload.size, q)
                        ?: forward(payload)
                        ?: dohFallback(payload, payload.size, q)
                    if (built != null) {
                        cache.put(q.name, q.qtype, built)
                        built
                    } else {
                        DnsPacket.buildServFail(payload, payload.size, q)
                    }
                }
            }
        }
        if (resp.isEmpty()) return

        val reply = DnsPacket.buildUdpReply(
            srcIp = byteArrayOf(10, 111, 0, 1),
            dstIp = udp.srcIp,
            srcPort = 53,
            dstPort = udp.srcPort,
            payload = resp
        )
        synchronized(output) {
            runCatching {
                output.write(reply)
                output.flush()
            }
        }
    }

    /**
     * 非 GitHub 域名优先走 DoH（阿里 / 腾讯），把"明文 53 被上游投毒"这条路堵死。
     * 若公共 DoH 被网络屏蔽，熔断 5 分钟再回到明文 53，避免每条查询都白等超时。
     */
    private fun dohPrefer(payload: ByteArray, len: Int, q: DnsPacket.Query): ByteArray? {
        if (q.qtype != 1) return null
        if (System.currentTimeMillis() < dohDownUntil) return null
        val ip = Net.dohFirstIp(q.name, 1200)
        if (ip.isEmpty()) {
            dohDownUntil = System.currentTimeMillis() + 5 * 60 * 1000L
            return null
        }
        return DnsPacket.buildAResponse(payload, len, q, ip)
    }

    /** 依次试当前网络的系统 DNS，而不是硬编码一个 223.5.5.5 */
    private fun forward(payload: ByteArray): ByteArray? {
        for (ip in resolvers.take(3)) {
            val r = queryUdp(ip, payload)
            if (r != null) return r
        }
        return null
    }

    private fun queryUdp(ip: String, payload: ByteArray): ByteArray? {
        val map = sockets.get() ?: return null
        var s = map[ip]
        if (s == null) {
            val created = runCatching {
                DatagramSocket().also {
                    runCatching { protect(it) }
                    it.soTimeout = 1500
                    it.connect(InetAddress.getByName(ip), 53)
                }
            }.getOrNull() ?: return null
            allSockets.add(created)
            map[ip] = created
            s = created
        }
        return try {
            s.send(DatagramPacket(payload, payload.size))
            val buf = ByteArray(1500)
            val dp = DatagramPacket(buf, buf.size)
            s.receive(dp)
            val out = buf.copyOf(dp.length)
            // 事务 ID 必须与查询一致，防止串包
            if (out.size < 12 || out[0] != payload[0] || out[1] != payload[1]) null else out
        } catch (e: Exception) {
            runCatching { s.close() }
            allSockets.remove(s)
            map.remove(ip)
            null
        }
    }

    /** 明文 53 被运营商/校园网屏蔽时，用 DoH 兜底，避免"开了加速别的网站全打不开" */
    private fun dohFallback(payload: ByteArray, len: Int, q: DnsPacket.Query): ByteArray? {
        if (q.qtype != 1) return null
        val ip = Net.dohFirstIp(q.name, 4000)
        if (ip.isEmpty()) return null
        return DnsPacket.buildAResponse(payload, len, q, ip)
    }

    // ---------- 网络变化 ----------

    private fun registerNetCallback() {
        if (netCallback != null) return
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = applyUnderlying(network)
                override fun onLost(network: Network) {
                    runCatching { setUnderlyingNetworks(null) }
                }
            }
            cm.registerNetworkCallback(req, cb)
            netCallback = cb
        }
    }

    private fun applyUnderlying(network: Network) {
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            val lp = cm.getLinkProperties(network)
            val ips = lp?.dnsServers?.filterIsInstance<Inet4Address>()?.mapNotNull { it.hostAddress }.orEmpty()
            if (ips.isNotEmpty()) resolvers = ips.take(4)
            setUnderlyingNetworks(arrayOf(network))
            AppState.log("网络已切换，上游 DNS ${resolvers.joinToString("/")}")
        }
        if (running && AppState.mapping.value.size < Config.domains.size) startProbe()
    }

    private fun unregisterNetCallback() {
        val cb = netCallback ?: return
        netCallback = null
        runCatching {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
        }
    }

    private fun closeSockets() {
        allSockets.forEach { runCatching { it.close() } }
        allSockets.clear()
        runCatching { sockets.get()?.clear() }
    }

    // ---------- 停止 ----------

    private fun stopVpn(revoked: Boolean = false) {
        running = false
        isActive = false
        unregisterNetCallback()
        runCatching { readThread?.interrupt() }
        runCatching { probeThread?.interrupt() }
        closeSockets()
        runCatching { tun?.close() }
        tun = null
        readThread = null
        probeThread = null
        cache.clear()

        AppState.running.value = false
        AppState.busy.value = false
        AppState.phase.value = AppState.Phase.IDLE
        AppState.status.value = "未加速"
        AppState.detail.value =
            if (revoked) "系统撤销了 VPN 授权，重新开启需要再次授权" else AppState.DefaultDetail
        AppState.mapping.value = emptyMap()
        if (AppState.nodes.value.any { it.state != "--" }) AppState.staleNodes.value = true
        AppState.log(if (revoked) "系统撤销 VPN 授权，加速已停止" else "已停止加速")

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stopVpn(revoked = true)
        super.onRevoke()
    }

    override fun onDestroy() {
        running = false
        isActive = false
        unregisterNetCallback()
        closeSockets()
        runCatching { tun?.close() }
        runCatching { workers.shutdownNow() }
        AppState.syncWithService(false)
        super.onDestroy()
    }

    // ---------- 通知 ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "FastGithub 加速状态", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, DnsVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b
            .setContentTitle("FastGithub 加速中")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notify)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .addAction(R.drawable.ic_notify, "停止加速", stopPi)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFY_ID, n)
        }
    }

    private fun updateNotification(count: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFY_ID, buildNotification("已接管 $count/${Config.domains.size} 个 GitHub 域名"))
    }
}
