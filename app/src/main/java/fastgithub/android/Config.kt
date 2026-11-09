package fastgithub.android

import android.content.Context
import java.io.File

data class Node(
    val host: String,
    var ip: String = "",
    var ms: Long = 0,
    var code: Int = 0,
    var state: String = "--"
) {
    val usable: Boolean get() = state == "GOOD" || state == "WEAK" || state == "QUICK" || state == "CACHE"

    /**
     * 能否写进 DNS 应答。
     * 网页型域名（github.com 等）必须真的返回过 200~399；QUICK / CACHE 这种"没验过 HTTP"
     * 的状态一律不许直接拿去应答，否则就是"界面显示已加速、网页 404"。
     */
    val acceptable: Boolean
        get() = when {
            Config.isStrict(host) -> state == "GOOD"
            else -> state == "GOOD" || state == "WEAK"
        }
    val stateText: String
        get() = when (state) {
            "GOOD" -> "可用 · $ms ms"
            "WEAK" -> "受限 · $ms ms"
            "QUICK" -> "临时 · $ms ms"
            "CACHE" -> "上次结果"
            "BAD" -> "不可用 · $code"
            "FAIL" -> "失败"
            else -> "待测速"
        }
    val shortState: String
        get() = when (state) {
            "GOOD" -> "可用"
            "WEAK" -> "受限"
            "QUICK" -> "临时"
            "CACHE" -> "缓存"
            "BAD" -> "不可用"
            "FAIL" -> "失败"
            else -> "待测"
        }
    val msText: String get() = if (ms > 0) "$ms ms" else "-"
}

object Config {
    const val AppName = "FastGithub"
    const val Version = "1.2.2Criticalissuefix"
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /**
     * 默认镜像前缀：空。
     * 内置的第三方 ghproxy 域名在 1.2.2 已全部移除——实测 5 个已超时/502/域名失效，
     * 这类死链点开就是广告页甚至博彩跳转页，不能再随程序分发。需要镜像由用户自己填。
     */
    const val Mirror = ""

    val mirrorPresets = emptyList<String>()

    /**
     * 这两个边缘 IP 对国内线路时好时坏（常见 504 / 直接打不开），
     * 桌面版已实测剔除，手机版统一在所有域名的候选池里排除。
     */
    val ipBlacklist = setOf("20.205.243.166", "20.205.243.167")

    val domains = listOf(
        "github.com",
        "api.github.com",
        "raw.githubusercontent.com",
        "gist.github.com",
        "gist.githubusercontent.com",
        "codeload.github.com",
        "objects.githubusercontent.com",
        "avatars.githubusercontent.com",
        "avatars0.githubusercontent.com",
        "avatars1.githubusercontent.com",
        "camo.githubusercontent.com",
        "user-images.githubusercontent.com",
        "private-user-images.githubusercontent.com",
        "cloud.githubusercontent.com",
        "media.githubusercontent.com",
        "raw.github.com",
        "github.githubassets.com",
        "github.io",
        "pages.github.com",
        "education.github.com",
        "uploads.github.com"
    )

    fun isGithubHost(host: String): Boolean = domains.contains(host.lowercase())

    /**
     * 网页型域名：hosts / DNS 里指错一个 IP，浏览器就是整站 404 或打不开。
     * 这些域名只认 200~399，403/404 一律不算可用。
     */
    fun isStrict(host: String): Boolean = when (host.lowercase()) {
        "github.com", "api.github.com", "gist.github.com", "github.io" -> true
        else -> false
    }

    /** 健康巡检只看这几个核心域名，保证一轮不会太慢 */
    val coreDomains = listOf(
        "github.com",
        "api.github.com",
        "codeload.github.com",
        "raw.githubusercontent.com",
        "github.githubassets.com"
    )

    fun poolOf(host: String): List<String> = when (host) {
        "github.com" -> listOf(
            "20.27.177.113", "20.27.177.115", "20.27.177.116", "20.233.83.145",
            "140.82.112.3", "140.82.113.3", "140.82.114.3", "4.237.22.38",
            "20.200.245.247", "20.207.73.82", "20.26.156.215"
        )
        "api.github.com" -> listOf("20.205.243.168", "20.27.177.116", "140.82.112.6", "140.82.113.6", "140.82.114.6", "140.82.116.6")
        "codeload.github.com" -> listOf("20.205.243.165", "20.27.177.115", "140.82.112.9", "140.82.113.9", "140.82.114.9")
        "gist.github.com" -> listOf("20.205.243.166", "20.205.243.167", "20.27.177.113", "140.82.112.3", "140.82.113.3", "140.82.114.3", "20.200.245.247")
        "education.github.com" -> listOf("140.82.114.22", "140.82.113.21")
        "uploads.github.com" -> listOf("20.205.243.161")
        "github.githubassets.com" -> listOf("185.199.108.215", "185.199.109.215", "185.199.110.215", "185.199.111.215")
        "github.io", "pages.github.com" -> listOf("185.199.108.153", "185.199.109.153", "185.199.110.153", "185.199.111.153")
        else -> listOf("185.199.108.133", "185.199.109.133", "185.199.110.133", "185.199.111.133")
    }

    private var appCtx: Context? = null

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    val context: Context
        get() = appCtx ?: error("Config.init 尚未调用")

    val filesDir: File
        get() = File(context.filesDir, AppName).apply { runCatching { mkdirs() } }
}
