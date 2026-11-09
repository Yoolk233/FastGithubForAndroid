package fastgithub.android

import java.io.File

/** 上次优选结果的本地缓存：服务启动时先套用，避免每次都要等测速 */
object MappingCache {
    private fun file() = File(Config.filesDir, "mapping.txt")

    fun load(): Map<String, String> = runCatching {
        val f = file()
        if (!f.exists()) return@runCatching emptyMap<String, String>()
        f.readLines().mapNotNull { line ->
            val i = line.indexOf(' ')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.filter { it.second.isNotEmpty() && Net.validIpv4(it.second) }
            .filter { Config.isGithubHost(it.first) }
            .toMap()
    }.getOrDefault(emptyMap())

    fun save(m: Map<String, String>) {
        runCatching {
            val target = file()
            val tmp = File(target.parentFile, "mapping.tmp")
            // 先写临时文件再改名：进程被杀时不会留下半截文件导致下次读空
            tmp.writeText(m.entries.joinToString("\n") { "${it.key} ${it.value}" })
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }
    }
}
