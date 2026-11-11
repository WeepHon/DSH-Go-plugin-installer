package com.dshgo.plugininstaller

import java.io.File
import java.util.concurrent.TimeUnit

data class ExecResult(val code: Int, val out: String, val timedOut: Boolean = false) {
    val ok: Boolean get() = code == 0 && !timedOut
}

/** 以 root（Magisk / KernelSU 的 su）执行命令的统一入口。 */
object RootShell {
    private val SU_CANDIDATES = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/debug_ramdisk/su",
        "/sbin/su",
        "/data/adb/magisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su"
    )

    /** 探测阶段确认可用的 su 附加参数（KernelSU 有时必须 -M / --mount-master 才能看到其他 App 的数据）。 */
    @Volatile private var modePrefix: List<String> = emptyList()
    @Volatile private var modeUsable = false

    /** 探测成功后固定后续命令使用同一模式；prefix 为空表示普通 su -c。 */
    fun setMode(prefix: List<String>) {
        modePrefix = prefix
        modeUsable = true
        Logx.i("root 命令模式：su " + (if (prefix.isEmpty()) "-c" else prefix.joinToString(" ") + " -c"))
    }

    fun modePrefix(): List<String> = modePrefix

    @Volatile private var suPath: String? = null
    @Volatile private var probed = false
    @Volatile var lastError: String = ""

    fun q(s: String): String =
        if (s.matches(Regex("^[A-Za-z0-9_./:@%+=,-]+$"))) s else "'" + s.replace("'", "'\\''") + "'"

    fun su(): String? {
        if (probed) return suPath
        synchronized(this) {
            if (probed) return suPath
            var found: String? = null
            for (p in SU_CANDIDATES) {
                val f = File(p)
                if (f.exists() && f.canExecute()) { found = p; break }
            }
            if (found == null) {
                // 有些机型只有 PATH 里的 su
                for (dir in listOf("/system/bin", "/system/xbin", "/su/bin", "/data/adb/modules")) {
                    val f = File(dir, "su")
                    if (f.exists()) { found = f.absolutePath; break }
                }
            }
            suPath = found
            probed = true
            return found
        }
    }

    fun hasRoot(): Boolean {
        val su = su() ?: return false
        val r = exec(su, listOf("-c", "id -u"), 20_000)
        val out = r.out.trim()
        if (r.timedOut) { lastError = "su 执行超时（可能需要在 root 管理器里点允许）"; return false }
        if (out.contains("uid=0") || out == "0") return true
        if (out.isEmpty()) lastError = "su 无输出，可能被拒绝授权"
        else lastError = "su 输出异常：${out.take(120)}"
        return false
    }

    fun run(cmd: String, timeoutMs: Long = 60_000L): ExecResult {
        val su = su() ?: return ExecResult(127, "未找到 su，请确认已获取 Root")
        return exec(su, modePrefix + listOf("-c", cmd), timeoutMs)
    }

    /** 以自定义 su 参数执行（用于尝试 --mount-master / -M / nsenter 等变体）。 */
    fun runArgs(args: List<String>, timeoutMs: Long = 120_000L): ExecResult {
        val su = su() ?: return ExecResult(127, "未找到 su，请确认已获取 Root")
        Logx.i("su " + args.joinToString(" ").take(180))
        return exec(su, args, timeoutMs)
    }

    fun readFile(path: String, timeoutMs: Long = 30_000L): String? {
        val r = run("cat " + q(path), timeoutMs)
        return if (r.ok) r.out else { lastError = r.out.take(200); null }
    }

    fun exists(path: String): Boolean = run("test -e " + q(path), 20_000).ok

    fun mkdirp(path: String): Boolean = run("mkdir -p " + q(path), 20_000).ok

    fun remove(path: String): Boolean = run("rm -rf " + q(path), 30_000).ok

    /** 把内容写到 root 侧文件：先写 App 缓存，再由 root cp 过去。 */
    fun writeText(path: String, text: String, cacheDir: File): Boolean {
        val temp = File(cacheDir, "write_tmp_" + System.nanoTime())
        return try {
            temp.writeText(text)
            run("mkdir -p " + q(path.substringBeforeLast('/', "/")) + " && cp -f " + q(temp.absolutePath) +
                " " + q(path) + " && chmod 644 " + q(path), 30_000).ok
        } finally {
            temp.delete()
        }
    }

    private fun exec(bin: String, args: List<String>, timeoutMs: Long): ExecResult {
        return try {
            val pb = ProcessBuilder(listOf(bin) + args)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val sb = StringBuilder()
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(4096)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            synchronized(sb) { sb.append(buf, 0, n) }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            reader.isDaemon = true
            reader.start()
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroyForcibly()
                reader.join(800)
                return ExecResult(-1, synchronized(sb) { sb.toString() }, true)
            }
            reader.join(1500)
            val result = ExecResult(p.exitValue(), synchronized(sb) { sb.toString() })
            if (!result.ok) {
                Logx.i("命令失败(code=${result.code})：${args.lastOrNull()?.take(240) ?: bin} → ${result.out.trim().take(200)}")
            }
            result
        } catch (e: Throwable) {
            ExecResult(-2, "执行失败: ${e.message}")
        }
    }
}
