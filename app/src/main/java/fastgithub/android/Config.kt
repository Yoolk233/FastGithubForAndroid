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
    const val Version = "1.2.1"
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    const val Mirror = "https://ghfast.top/"

    val mirrorPresets = listOf(
        "https://ghfast.top/",
        "https://ghproxy.net/",
        "https://gh-proxy.com/",
        "https://mirror.ghproxy.com/",
        "https://gh.llkk.cc/",
        "https://ghp.ci/"
    )

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
