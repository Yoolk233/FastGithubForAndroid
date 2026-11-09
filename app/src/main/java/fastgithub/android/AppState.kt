package fastgithub.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object AppState {

    /** 界面阶段：用来驱动首页的状态卡（文案 / 颜色 / 进度） */
    enum class Phase { IDLE, CONNECTING, PROBING, READY, ERROR }

    val running = MutableStateFlow(false)
    val busy = MutableStateFlow(false)

    /** 始终按 Config.domains 的顺序给出 21 行，未测速时 state = "--" */
    val nodes = MutableStateFlow(Config.domains.map { Node(it) })
    val mapping = MutableStateFlow<Map<String, String>>(emptyMap())
    val logs = MutableStateFlow<List<String>>(emptyList())
    val status = MutableStateFlow("未加速")
    val detail = MutableStateFlow(DefaultDetail)
    val phase = MutableStateFlow(Phase.IDLE)
    val lastProbeAt = MutableStateFlow(0L)

    /** 停止加速后节点表还留着上次结果，标记出来避免和"已接管 0 个"对不上 */
    val staleNodes = MutableStateFlow(false)

    const val DefaultDetail = "打开开关后，浏览器与下载器的 GitHub 请求自动走优选节点"

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun log(msg: String) {
        // 原来的 SimpleDateFormat 是多线程共享的，探针线程和 UI 线程同时写会出乱码时间
        val line = LocalTime.now().format(timeFmt) + "  " + msg
        logs.update { (it + line).takeLast(120) }
    }

    /** 原子 upsert：并发探针同时回写时不再互相覆盖（原来 filter + 赋值的写法会丢行） */
    fun upsertNode(n: Node) {
        nodes.update { cur -> sortNodes(cur.filter { it.host != n.host } + n) }
    }

    fun sortNodes(list: List<Node>): List<Node> {
        val map = list.associateBy { it.host }
        val known = Config.domains.mapNotNull { map[it] }
        val extra = list.filter { !Config.domains.contains(it.host) }.sortedBy { it.host }
        return known + extra
    }

    fun markReady(usable: Int) {
        phase.value = if (usable > 0) Phase.READY else Phase.ERROR
        busy.value = false
        lastProbeAt.value = System.currentTimeMillis()
        staleNodes.value = false
    }

    fun markError(msg: String) {
        phase.value = Phase.ERROR
        busy.value = false
        status.value = "未加速"
        detail.value = msg
    }

    /** 与服务真实状态对齐：服务已经不在时，界面不能还显示"加速中" */
    fun syncWithService(active: Boolean) {
        if (!active) {
            if (running.value || phase.value == Phase.CONNECTING || phase.value == Phase.PROBING) {
                running.value = false
                busy.value = false
                phase.value = Phase.IDLE
                status.value = "未加速"
                detail.value = DefaultDetail
                mapping.value = emptyMap()
                if (nodes.value.any { it.state != "--" }) staleNodes.value = true
            }
        } else {
            running.value = true
            // 从后台/通知栏回来时，服务在跑但界面可能还停在 IDLE 或上一次的 ERROR
            if (phase.value == Phase.IDLE || phase.value == Phase.ERROR) {
                phase.value = if (mapping.value.isNotEmpty()) Phase.READY else Phase.PROBING
                status.value = if (mapping.value.isNotEmpty()) "加速已生效" else "加速中（正在优选节点）"
            }
        }
    }
}
