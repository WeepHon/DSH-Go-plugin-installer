package com.dshgo.plugininstaller

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Step(val label: String, val ok: Boolean, val detail: String = "")

data class InstallResult(val ok: Boolean, val steps: List<Step>, val message: String) {
    fun text(): String {
        val sb = StringBuilder()
        for (s in steps) {
            sb.append(if (s.ok) "✓ " else "✗ ").append(s.label)
            if (s.detail.isNotBlank()) sb.append("：").append(s.detail)
            sb.append('\n')
        }
        if (message.isNotBlank()) sb.append(message)
        return sb.toString().trim()
    }
}

data class RepairResult(val removed: List<String>, val notes: List<String>) {
    fun text(): String {
        if (notes.isEmpty() && removed.isEmpty()) return "没有发现需要修复的内容。"
        val sb = StringBuilder()
        for (n in notes) sb.append("· ").append(n).append('\n')
        if (sb.isEmpty()) sb.append("已检查，未发现失效链接。")
        return sb.toString().trim()
    }
}

/** 在 proot 内执行命令的封装。 */
object Proot {
    private const val FALLBACK_DIR = "/data/local/tmp/dsh-plugin-installer"
    private const val TMP_DIR = "$FALLBACK_DIR/tmp"

    @Volatile private var usableExe: String? = null
    @Volatile private var usableLibDir: String? = null
    @Volatile private var usableRootfs: String? = null
    @Volatile private var variant: Int = 0
    @Volatile private var extraBinds: List<String> = emptyList()
    @Volatile private var ensureTried = false

    private fun libDirOf(exe: String): String = exe.substringBeforeLast('/')

    /**
     * dsh-Go 以 {PROOT_LOADER, PROOT_L2S_DIR, PROOT_TMP_DIR, LD_LIBRARY_PATH} 这几个环境变量启动 proot；
     * 缺 PROOT_LOADER 时 guest 里任何动态链接的程序 execve 都会报 ENOENT（实测 /usr/bin/env not found）。
     */
    private fun prootEnv(env: DshEnv, libDir: String): String {
        val l2s = env.rootfs + "/root/.l2s"
        return "PATH=/system/bin:/system/xbin ANDROID_ROOT=/system ANDROID_DATA=/data" +
            " LD_LIBRARY_PATH=" + RootShell.q(libDir) +
            " PROOT_LOADER=" + RootShell.q("$libDir/libproot-loader.so") +
            " PROOT_L2S_DIR=" + RootShell.q(l2s) +
            " PROOT_TMP_DIR=" + RootShell.q(TMP_DIR) +
            " PROOT_NO_SECCOMP=1 TMPDIR=" + RootShell.q(TMP_DIR)
    }

    private fun envPairs(env: DshEnv): List<Pair<String, String>> = listOf(
        "HOME" to "/root", "USER" to "root", "LOGNAME" to "root", "SHELL" to "/bin/bash",
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "LANG" to "C.UTF-8", "TERM" to "dumb", "TMPDIR" to "/tmp",
        "DSH_HOME" to env.dshHome,
        "XDG_DATA_DIRS" to "/usr/local/share:/usr/share:/system",
        "pnpm_config_registry" to "https://registry.npmmirror.com/",
        "UV_USE_IO_URING" to "0", "NARB_DISABLE_NATIVE_CACHE" to "1",
        "NODE_OPTIONS" to "--max-old-space-size=2048",
        "TZ" to "Asia/Shanghai", "UV_THREADPOOL_SIZE" to "4",
        "DSH_GO_RUNTIME_ROOT" to env.rootfs
    )

    /** 0 = 用 /usr/bin/env -i 注入环境；1 = 用 /bin/sh 内建 export（rootfs 里没有 env 时用）；2 = 不注入。 */
    private fun wrap(env: DshEnv, mode: Int, program: List<String>): List<String> {
        val pairs = envPairs(env)
        return when (mode) {
            0 -> {
                val l = ArrayList<String>(pairs.size * 2 + 3)
                l.add("/usr/bin/env"); l.add("-i")
                for ((k, v) in pairs) l.add("$k=$v")
                l.addAll(program); l
            }
            1 -> {
                val exports = pairs.joinToString(" ") { (k, v) -> "$k=$v" }
                listOf("/bin/sh", "-c", "export $exports; exec \"$@\"", "dshpi") + program
            }
            else -> program
        }
    }

    private fun prootPrefix(env: DshEnv, v: Int): List<String> {
        val rootfs = "--rootfs=" + env.rootfs
        return when (v) {
            0, 1 -> ArrayList<String>().apply {
                addAll(listOf(
                    "--kill-on-exit", "--link2symlink", "--sysvipc", "-L", "-0", rootfs, "--cwd=/root",
                    "-b", "/dev", "-b", "/dev/urandom:/dev/random",
                    "-b", "/proc", "-b", "/proc/self/fd:/dev/fd",
                    "-b", "/proc/self/fd/0:/dev/stdin",
                    "-b", "/proc/self/fd/1:/dev/stdout",
                    "-b", "/proc/self/fd/2:/dev/stderr",
                    "-b", "/sys"
                ))
                addAll(extraBinds)
                addAll(listOf("-b", "/system", "-b", "/apex", "-b", "/storage/emulated/0:/sdcard", "-b", "/storage"))
            }
            2, 3 -> listOf("-0", rootfs, "--cwd=/root")
            4 -> listOf("-0", rootfs, "--cwd=/root", "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", "/storage/emulated/0:/sdcard")
            5 -> listOf("-L", "-0", rootfs, "--cwd=/root")
            else -> listOf("-0", rootfs)
        }
    }

    private fun variantWrap(v: Int): Int = when (v) {
        0 -> 0
        1 -> 2
        2 -> 1
        3 -> 0
        4 -> 1
        else -> 2
    }

    private fun buildCmd(env: DshEnv, exe: String, libDir: String, v: Int, program: List<String>): String {
        val prefix = prootPrefix(env, v)
        val full = ArrayList<String>(prefix.size + program.size + 3)
        full.addAll(prefix)
        full.addAll(wrap(env, variantWrap(v), program))
        return prootEnv(env, libDir) + " " + RootShell.q(exe) + " " + full.joinToString(" ") { RootShell.q(it) }
    }

    private fun testOk(r: ExecResult): Boolean = r.out.contains("PROOT_OK")

    /** 找到一套真正能把命令跑起来的 proot 调用方式（不同构建对 env/绑定/参数支持不一样）。 */
    fun ensureUsable(env: DshEnv): String? {
        if (!env.hasProot) return null
        val cached = usableExe
        if (cached != null && usableRootfs == env.rootfs) return cached
        val l2s = env.rootfs + "/root/.l2s"
        RootShell.run(
            "mkdir -p " + RootShell.q(TMP_DIR) + " " + RootShell.q(l2s) +
                " && chmod 755 " + RootShell.q(FALLBACK_DIR) + " " + RootShell.q(TMP_DIR) +
                " && chmod 700 " + RootShell.q(l2s) +
                " && chown " + env.appUid + ":" + env.appGid + " " + RootShell.q(l2s) + " 2>/dev/null; true",
            30_000
        )
        for (cand in listOf(env.proot, "$FALLBACK_DIR/lib/libproot.so")) {
            val libDir = libDirOf(cand)
            if (cand != env.proot) {
                if (!RootShell.exists(cand)) {
                    val src = libDirOf(env.proot)
                    val cp = RootShell.run(
                        "mkdir -p " + RootShell.q(libDir) + " && cp -f " + RootShell.q(src) + "/*.so " +
                            RootShell.q(libDir) + "/ 2>/dev/null; chmod 755 " + RootShell.q(libDir) + "/*.so 2>/dev/null; true",
                        180_000
                    )
                    Logx.i("复制 proot 依赖到 $libDir：" + cp.out.trim().take(160))
                }
                if (!RootShell.exists(cand)) continue
            }
            computeExtraBinds(env)
            if (!RootShell.exists("$libDir/libproot-loader.so")) {
                Logx.i("注意：$libDir 下没有 libproot-loader.so（dsh-Go 用它加载 guest 程序）")
            }
            val test = listOf("/bin/sh", "-c", "echo PROOT_OK")
            for (v in 0..5) {
                val cmd = buildCmd(env, cand, libDir, v, test)
                val r = RootShell.run(cmd, 120_000)
                Logx.i("proot 尝试 v$v（$cand）→ code=${r.code} " + r.out.trim().take(200).replace("\n", " | "))
                if (testOk(r)) {
                    usableExe = cand
                    usableLibDir = libDir
                    usableRootfs = env.rootfs
                    variant = v
                    Logx.i("使用 proot：$cand（v$v）")
                    return cand
                }
            }
        }
        Logx.i("proot 所有调用方式均失败，无法调用 DSH CLI")
        return null
    }

    private fun computeExtraBinds(env: DshEnv) {
        val list = ArrayList<String>()
        val sel = env.rootfs + "/var/lib/dsh-app/empty"
        val shm = env.rootfs + "/var/lib/dsh-app/shm"
        if (RootShell.exists(sel)) { list.add("-b"); list.add("$sel:/sys/fs/selinux") }
        if (RootShell.exists(shm)) { list.add("-b"); list.add("$shm:/dev/shm") }
        extraBinds = list
    }

    fun exec(env: DshEnv, program: List<String>, timeoutMs: Long = 300_000L): ExecResult {
        if (program.isEmpty()) return ExecResult(127, "空命令")
        if (!env.hasProot) return ExecResult(127, "未找到 proot（libproot.so），无法调用 DSH 运行时")
        val exe = ensureUsable(env)
            ?: return ExecResult(126, "proot 无法执行（见日志里的 proot 尝试输出）")
        val libDir = usableLibDir ?: libDirOf(exe)
        val cmd = buildCmd(env, exe, libDir, variant, program)
        Logx.i("proot 执行：" + program.take(6).joinToString(" "))
        return RootShell.run(cmd, timeoutMs)
    }
}

object Compat {
    private fun tmpHost(env: DshEnv) = env.hostPath("${env.dshHome}/.plugin-installer-tmp")

    fun preflight(env: DshEnv, pkg: PluginPkg, scriptsDir: File, cacheDir: File, exemptions: JSONObject?): PreflightResult {
        if (!env.hasProot) return PreflightResult(false, null, null, null, "未找到 proot")
        val tmp = tmpHost(env)
        return try {
            RootShell.run("mkdir -p " + RootShell.q(tmp) + " && rm -rf " + RootShell.q("$tmp/*") + " 2>/dev/null; true", 30_000)
            if (!RootShell.writeText("$tmp/manifest.json", pkg.manifestText, cacheDir)) {
                return PreflightResult(false, null, null, null, "写入预检文件失败")
            }
            val exemptionsText = exemptions?.toString(2) ?: "{}"
            RootShell.writeText("$tmp/exemptions.json", exemptionsText, cacheDir)
            val mjs = File(scriptsDir, "preflight.mjs")
            if (!mjs.isFile) return PreflightResult(false, null, null, null, "缺少 preflight.mjs")
            RootShell.run("cp -f " + RootShell.q(mjs.absolutePath) + " " + RootShell.q("$tmp/preflight.mjs"), 30_000)

            val node = "/opt/node/bin/node"
            val r = Proot.exec(
                env,
                listOf(node, "${env.dshHome}/.plugin-installer-tmp/preflight.mjs",
                    "${env.dshHome}/.plugin-installer-tmp/manifest.json",
                    "${env.dshHome}/.plugin-installer-tmp/exemptions.json"),
                180_000
            )
            val json = extractJson(r.out)
            if (json == null) {
                PreflightResult(false, null, null, null, "预检无输出：" + r.out.take(300).trim())
            } else {
                val o = JSONObject(json)
                val ok = o.optBoolean("ok", false)
                val runtime = o.optString("runtime", "").takeIf { it.isNotBlank() && it != "null" }
                if (!ok) {
                    PreflightResult(false, runtime, null, null, o.optString("error", "未知错误"))
                } else {
                    val issue = o.opt("issue")
                    val compatible = o.optBoolean("compatible", true)
                    val reqText = if (issue is JSONObject) {
                        issue.optJSONObject("requirements")?.let { req ->
                            val sb = StringBuilder()
                            for (k in req.keys()) sb.append(k).append(' ').append(req.optString(k)).append("；")
                            sb.toString().trimEnd('；')
                        }
                    } else null
                    PreflightResult(true, runtime, compatible, reqText, null)
                }
            }
        } catch (e: Exception) {
            PreflightResult(false, null, null, null, "预检异常：" + (e.message ?: ""))
        } finally {
            RootShell.run("rm -rf " + RootShell.q(tmp), 30_000)
        }
    }

    private fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }
}

object Installer {
    private const val BOOT_INDEX = "/opt/dsh/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js"
    private const val UNLOCK_MARK = "dsh-plugin-installer: [compat-gate]"
    private const val TMP = ".plugin-installer-tmp"

    private fun tail(r: ExecResult, lines: Int = 6): String {
        val text = r.out.trim()
        if (text.isEmpty()) return "退出码 ${r.code}"
        val arr = text.split("\n")
        return arr.takeLast(lines).joinToString(" / ").take(600)
    }

    private fun profilePath(env: DshEnv, profile: String) =
        env.hostPath("${env.dshHome}/profiles/$profile/package.json")

    private fun readProfile(env: DshEnv, profile: String): JSONObject? {
        val text = RootShell.readFile(profilePath(env, profile)) ?: return null
        return try { JSONObject(text) } catch (e: Exception) { null }
    }


    private fun writeProfile(env: DshEnv, profile: String, obj: JSONObject, cacheDir: File): Boolean =
        RootShell.writeText(profilePath(env, profile), obj.toString(2) + "\n", cacheDir)

    /** 把运行时里被 root 写入的文件归还给 dsh-Go 的 uid，避免引擎之后无法改写。 */
    fun fixOwnership(env: DshEnv, relPaths: List<String>) {
        if (env.appUid <= 0) return
        val sb = StringBuilder()
        for (rel in relPaths) {
            sb.append("chown -R ").append(env.appUid).append(':').append(env.appGid).append(' ')
                .append(RootShell.q(env.hostPath(rel))).append(" 2>/dev/null; ")
        }
        sb.append("true")
        RootShell.run(sb.toString(), 120_000)
    }

    /** 修复目录权限：proot 里的引擎以 App uid 运行，root 写入的目录若不可穿越会导致 realpath EACCES。 */
    fun fixPerms(env: DshEnv, relPaths: List<String>) {
        if (relPaths.isEmpty()) return
        val sb = StringBuilder()
        for (rel in relPaths) {
            val p = RootShell.q(env.hostPath(rel))
            sb.append("chmod -R a+rX ").append(p).append(" 2>/dev/null; ")
            if (env.appUid > 0) {
                sb.append("chown -R ").append(env.appUid).append(':').append(env.appGid).append(' ')
                    .append(p).append(" 2>/dev/null; ")
            }
        }
        sb.append("true")
        RootShell.run(sb.toString(), 180_000)
    }

    /**
     * DSH CLI 用 root 身份跑（我们没法以 dsh-Go 的 uid 执行 proot），pnpm 写入的
     * package.json / node_modules 会归 root 所有，引擎（app uid）读的时候会 EACCES。
     * 每次官方命令跑完都把 $DSH_HOME 归还给 dsh-Go 的 uid，并保证可读可穿越。
     */
    fun normalizeDshHome(env: DshEnv) {
        if (env.appUid <= 0) return
        val home = env.hostPath(env.dshHome)
        val r = RootShell.run(
            "chown -R " + env.appUid + ":" + env.appGid + " " + RootShell.q(home) +
                " && chmod -R a+rX " + RootShell.q(home) +
                " && find " + RootShell.q(home) + " -maxdepth 3 -name '.credentials.yaml' -exec chmod 600 {} + 2>/dev/null; true",
            300_000
        )
        Logx.i("已把 $home 的属主归还 dsh-Go（uid=${env.appUid}）→ code=${r.code}")
    }

    /** 从备份目录回滚 profile 的 package.json。 */
    fun restoreProfile(env: DshEnv, profile: String, backupRel: String): Boolean {
        val src = env.hostPath("$backupRel/package.json")
        val dst = profilePath(env, profile)
        return RootShell.run("cp -f " + RootShell.q(src) + " " + RootShell.q(dst) + " && chmod 644 " + RootShell.q(dst), 30_000).ok
    }

    /** 直接改 compatibility.json 登记版本豁免（不经过 CLI）。 */
    fun directExemption(env: DshEnv, profile: String, name: String, version: String, dshVersion: String, cacheDir: File): Boolean {
        val path = env.hostPath("${env.dshHome}/profiles/$profile/compatibility.json")
        val existing = RootShell.readFile(path)
        val obj = try { if (existing.isNullOrBlank()) JSONObject() else JSONObject(existing) } catch (e: Exception) { JSONObject() }
        val key = "$name@$version"
        val arr = obj.optJSONArray(key) ?: JSONArray()
        val list = arr.toStringList().toMutableList()
        if (!list.contains(dshVersion)) list.add(dshVersion)
        obj.put(key, JSONArray(list))
        val ok = RootShell.writeText(path, obj.toString(2) + "\n", cacheDir)
        if (ok) fixOwnership(env, listOf("${env.dshHome}/profiles/$profile"))
        return ok
    }

    /** 不经过 DSH CLI 的安装：写 dependencies + bundles，并把插件复制进 node_modules（不用软链，避免 proot realpath 权限问题）。 */
    fun directAdd(env: DshEnv, pkg: PluginPkg, profile: String, cacheDir: File): InstallResult {
        val steps = ArrayList<Step>()
        val manifest = readProfile(env, profile)
            ?: return InstallResult(false, steps, "读不到 profile 配置")
        val backupRel = backupProfile(env, profile)
            ?: return InstallResult(false, steps, "无法备份 profile 配置，已中止")
        steps.add(Step("已备份原配置", true, backupRel))

        val targetInside = pkg.targetInside(env)
        val targetHost = env.hostPath(targetInside)
        val deps = manifest.optJSONObject("dependencies") ?: JSONObject()
        deps.put(pkg.name, "link:$targetInside")
        manifest.put("dependencies", deps)
        if (pkg.kind != "npm") {
            val dsh = manifest.optJSONObject("dsh") ?: JSONObject()
            val prof = dsh.optJSONObject("profile") ?: JSONObject()
            val arr = prof.optJSONArray("bundles") ?: JSONArray()
            val names = arr.toStringList().toMutableList()
            if (!names.contains(pkg.name)) names.add(pkg.name)
            prof.put("bundles", JSONArray(names))
            dsh.put("profile", prof)
            manifest.put("dsh", dsh)
        }
        if (!writeProfile(env, profile, manifest, cacheDir)) {
            restoreProfile(env, profile, backupRel)
            return InstallResult(false, steps, "写入 profile 配置失败，已回滚")
        }
        steps.add(Step("写入 profile 配置", true, "dependencies += link:$targetInside"))

        if (!RootShell.exists("$targetHost/package.json")) {
            restoreProfile(env, profile, backupRel)
            return InstallResult(false, steps, "插件目录不完整（缺少 package.json），已回滚")
        }

        val nmRel = "${env.dshHome}/profiles/$profile/node_modules"
        val entryHost = env.hostPath("$nmRel/${pkg.name}")
        val parent = entryHost.substringBeforeLast('/')
        val cp = RootShell.run(
            "mkdir -p " + RootShell.q(parent) + " && rm -rf " + RootShell.q(entryHost) +
                " && cp -a " + RootShell.q(targetHost) + " " + RootShell.q(entryHost) + " 2>&1; true",
            180_000
        )
        fixPerms(env, listOf(targetInside, "$nmRel/${pkg.name}", nmRel, "${env.dshHome}/local-plugins"))
        val verify = RootShell.run(
            "test -f " + RootShell.q("$entryHost/package.json") + " && test -r " + RootShell.q(entryHost) + " && echo OK",
            30_000
        )
        if (!verify.out.contains("OK")) {
            RootShell.run("rm -rf " + RootShell.q(entryHost), 60_000)
            restoreProfile(env, profile, backupRel)
            return InstallResult(false, steps, "复制到 node_modules 失败，已回滚：" + cp.out.take(160))
        }
        steps.add(Step("写入 node_modules", true, pkg.name + "（实际目录副本）"))
        fixPerms(env, listOf("${env.dshHome}/profiles/$profile"))
        return InstallResult(true, steps, "已用直接写入方式安装。重启 dsh-Go 后生效；若启动异常请点「修复启动问题」。")
    }

    /** 扫描并清理失效的本地 link 依赖（dsh 启动时 realpath 报错会让整个引擎退出）。 */
    /**
     * 官方 CLI 用 `link:` 安装时会在 node_modules 里放一个软链，而 dsh 0.2.2-rc.1 的
     * linkedProfileRoots() 会对它做 realpath，proot 下这类软链会 EACCES 直接把引擎带崩。
     * 这里把软链换成真实目录副本（package.json 里的依赖声明保持不变）。
     */
    fun materializeEntry(env: DshEnv, profile: String, name: String): Boolean {
        val entry = env.hostPath("${env.dshHome}/profiles/$profile/node_modules/$name")
        val isLink = RootShell.run("test -L " + RootShell.q(entry) + " && echo YES", 20_000).out.contains("YES")
        if (!isLink) return true
        val target = RootShell.run("readlink " + RootShell.q(entry), 20_000).out.trim()
        if (target.isEmpty()) return false
        val targetHost = if (target.startsWith("/")) env.hostPath(target)
        else entry.substringBeforeLast('/') + "/" + target
        val r = RootShell.run(
            "test -f " + RootShell.q("$targetHost/package.json") + " && rm -f " + RootShell.q(entry) +
                " && cp -a " + RootShell.q(targetHost) + " " + RootShell.q(entry) + " && echo DONE",
            300_000
        )
        if (!r.out.contains("DONE")) {
            Logx.i("把 $name 的软链接换成真实目录失败：" + r.out.trim().take(200))
            return false
        }
        fixPerms(env, listOf("${env.dshHome}/profiles/$profile/node_modules/$name"))
        Logx.i("已把 node_modules/$name 的软链接换成真实目录（避开引擎 realpath EACCES）")
        return true
    }

    private fun isInternalPlugin(name: String): Boolean =
        name.startsWith("@deepseek-ai/dsh") || name.startsWith("dsh-profile-")

    /**
     * node_modules 顶层里不属于任何依赖的可疑条目：软链指向本地插件副本，或本身已无法解析。
     * 这类条目会让 dsh-app-boot 的 linkedProfileRoots() realpath 失败，直接把引擎带崩。
     */
    private fun strayEntries(env: DshEnv, profile: String, deps: JSONObject): List<Pair<String, String>> {
        val nm = env.hostPath("${env.dshHome}/profiles/$profile/node_modules")
        if (!RootShell.exists(nm)) return emptyList()
        val ls = RootShell.run("ls -1 " + RootShell.q(nm) + " 2>/dev/null", 30_000)
        val out = ArrayList<Pair<String, String>>()
        for (line in ls.out.lines()) {
            val n = line.trim()
            if (n.isEmpty() || n.startsWith(".") || n.startsWith("@")) continue
            if (deps.has(n)) continue
            val p = "$nm/$n"
            val target = RootShell.run("readlink " + RootShell.q(p) + " 2>/dev/null", 20_000).out.trim()
            val exists = RootShell.run("test -e " + RootShell.q(p) + " && echo YES", 20_000).out.contains("YES")
            val pointsToCopy = target.contains("/local-plugins/") || target.contains("/dsh-go/plugins/")
            if (pointsToCopy || !exists) out.add(n to target)
        }
        return out
    }

    data class RestoreResult(val restored: List<String>, val kept: List<String>) {
        fun text(): String = buildString {
            if (restored.isEmpty() && kept.isEmpty()) append("没有发现被安全模式挪走的文件。")
            if (restored.isNotEmpty()) {
                append("已恢复：\n")
                restored.forEach { append("· ").append(it).append('\n') }
            }
            if (kept.isNotEmpty()) {
                append("同名文件已存在，未覆盖（保留为备份）：\n")
                kept.forEach { append("· ").append(it).append('\n') }
            }
        }
    }

    /**
     * dsh-Go 进安全模式时会把 $DSH_HOME/cordis.patch.yml、.env 改名成 *.off-<时间> 挪走，
     * 退出安全模式后不会自动放回——全局补丁（比如 MCP 注册）就这样"消失"了。
     */
    fun restoreSafeModeFiles(env: DshEnv): RestoreResult {
        val restored = ArrayList<String>()
        val kept = ArrayList<String>()
        val dirs = listOf(env.hostPath(env.dshHome), env.hostPath("/root")).distinct()
        for (dir in dirs) {
            if (!RootShell.exists(dir)) continue
            val ls = RootShell.run(
                "find " + RootShell.q(dir) + " -maxdepth 1 -type f -name '*.off-*' 2>/dev/null", 30_000
            )
            for (line in ls.out.lines()) {
                val full = line.trim()
                if (full.isEmpty() || !full.contains(".off-")) continue
                val name = full.substringAfterLast('/')
                val base = name.substringBefore(".off-")
                if (base.isEmpty()) continue
                if (!(base.endsWith(".yml") || base.endsWith(".yaml") || base == ".env")) continue
                val basePath = dir + "/" + base
                val shown = full.removePrefix(env.rootfs).let { if (it.startsWith("/")) it else "/$it" }
                if (RootShell.exists(basePath)) { kept.add(shown); continue }
                val r = RootShell.run("mv " + RootShell.q(full) + " " + RootShell.q(basePath) + " && echo DONE", 30_000)
                if (r.out.contains("DONE")) restored.add(shown) else kept.add(shown)
            }
        }
        if (restored.isNotEmpty()) normalizeDshHome(env)
        return RestoreResult(restored, kept)
    }

    fun findBroken(env: DshEnv): List<String> {
        val out = ArrayList<String>()
        for (p in env.profiles) {
            val manifest = readProfile(env, p.name) ?: continue
            val deps = manifest.optJSONObject("dependencies") ?: JSONObject()
            for (name in deps.keys().asSequence().toList()) {
                val spec = deps.optString(name)
                if (!spec.startsWith("link:") && !spec.startsWith("file:")) continue
                val target = spec.substringAfter(":").trim()
                val hostTarget = if (target.startsWith("/")) env.hostPath(target) else env.hostPath("${env.dshHome}/profiles/${p.name}/$target")
                val entry = env.hostPath("${env.dshHome}/profiles/${p.name}/node_modules/$name")
                val ok = RootShell.run(
                    "test -f " + RootShell.q("$hostTarget/package.json") + " && test -e " + RootShell.q(entry) + " && echo OK",
                    30_000
                ).out.contains("OK")
                if (!ok) out.add("${p.name}: $name")
            }
            val bundles = manifest.optJSONObject("dsh")?.optJSONObject("profile")
                ?.optJSONArray("bundles")?.toStringList() ?: emptyList()
            for (b in bundles) {
                if (isInternalPlugin(b) || deps.has(b)) continue
                val entry = env.hostPath("${env.dshHome}/profiles/${p.name}/node_modules/$b")
                if (!RootShell.exists(entry)) out.add("${p.name}: $b（启用列表残留）")
            }
            for ((n, t) in strayEntries(env, p.name, deps)) {
                out.add("${p.name}: node_modules/$n" + if (t.isNotBlank()) "  → $t" else "（已失效）")
            }
        }
        return out
    }

    /** 一键修复：移除失效依赖/链接、修正权限。可在 dsh-Go 起不来时直接使用（不依赖引擎）。 */
    fun repairStartup(env: DshEnv, cacheDir: File): RepairResult {
        val removed = ArrayList<String>()
        val notes = ArrayList<String>()
        for (p in env.profiles) {
            val manifest = readProfile(env, p.name)
            if (manifest == null) { notes.add("跳过 ${p.name}：读取配置失败"); continue }
            val deps = manifest.optJSONObject("dependencies") ?: JSONObject()
            val dsh = manifest.optJSONObject("dsh") ?: JSONObject()
            val prof = dsh.optJSONObject("profile") ?: JSONObject()
            val bundles = (prof.optJSONArray("bundles")?.toStringList() ?: emptyList()).toMutableList()
            var changed = false
            for (name in deps.keys().asSequence().toList()) {
                val spec = deps.optString(name)
                if (!spec.startsWith("link:") && !spec.startsWith("file:")) continue
                val target = spec.substringAfter(":").trim()
                val hostTarget = if (target.startsWith("/")) env.hostPath(target) else env.hostPath("${env.dshHome}/profiles/${p.name}/$target")
                val entry = env.hostPath("${env.dshHome}/profiles/${p.name}/node_modules/$name")
                val targetOk = RootShell.exists("$hostTarget/package.json")
                if (targetOk && materializeEntry(env, p.name, name)) {
                    notes.add("已把 $name 的软链换成真实目录")
                }
                val ok = RootShell.run(
                    "test -f " + RootShell.q("$hostTarget/package.json") + " && test -e " + RootShell.q(entry) + " && echo OK",
                    30_000
                ).out.contains("OK")
                if (ok) continue
                deps.remove(name)
                changed = true
                removed.add("${p.name}: $name")
                if (bundles.remove(name)) notes.add("从 bundles 移除 $name")
                RootShell.run("rm -rf " + RootShell.q(entry), 60_000)
                notes.add("移除失效依赖 $name（$spec）")
            }
            // 启用列表里有、依赖里没有、node_modules 里也找不到的残留启用项
            for (b in bundles.toList()) {
                if (isInternalPlugin(b) || deps.has(b)) continue
                val entry = env.hostPath("${env.dshHome}/profiles/${p.name}/node_modules/$b")
                if (RootShell.exists(entry)) continue
                bundles.remove(b)
                changed = true
                removed.add("${p.name}: $b（残留启用项）")
                notes.add("从启用列表移除 $b")
            }
            // node_modules 里不属于任何依赖的本地插件副本 / 已失效条目
            for ((n, t) in strayEntries(env, p.name, deps)) {
                val entry = env.hostPath("${env.dshHome}/profiles/${p.name}/node_modules/$n")
                val rm = RootShell.run(
                    "rm -rf " + RootShell.q(entry) + " 2>/dev/null; " +
                        "test -e " + RootShell.q(entry) + " || echo GONE", 60_000
                )
                if (!rm.out.contains("GONE")) {
                    // proot link2symlink 生成的坏条目有时删不掉 → 改名隔离，至少不让引擎扫到
                    val qdir = env.hostPath("${env.dshHome}/plugin-installer-quarantine")
                    RootShell.run(
                        "mkdir -p " + RootShell.q(qdir) + " && mv " + RootShell.q(entry) + " " +
                            RootShell.q("$qdir/${p.name}-$n-${System.currentTimeMillis()}") + " 2>/dev/null; true",
                        60_000
                    )
                    notes.add("条目 $n 无法直接删除，已改名隔离到 plugin-installer-quarantine")
                }
                bundles.remove(n)
                removed.add("${p.name}: node_modules/$n")
                notes.add("移除 node_modules 里的异常条目 $n" + (if (t.isNotBlank()) "（→ $t）" else "（已失效）"))
                changed = true
            }
            if (changed) {
                prof.put("bundles", JSONArray(bundles))
                dsh.put("profile", prof)
                manifest.put("dsh", dsh)
                manifest.put("dependencies", deps)
                if (writeProfile(env, p.name, manifest, cacheDir)) notes.add("已更新 ${p.name}/package.json")
                else notes.add("更新 ${p.name}/package.json 失败")
            }
            fixPerms(env, listOf("${env.dshHome}/profiles/${p.name}", "${env.dshHome}/local-plugins", "${env.dshHome}/dsh-go/plugins"))
        }
        normalizeDshHome(env)
        return RepairResult(removed, notes)
    }

    /** 不经过 DSH CLI 的卸载：删 dependencies/bundles 条目与 node_modules 链接。 */
    fun directRemove(env: DshEnv, profile: String, name: String, cacheDir: File): InstallResult {
        val steps = ArrayList<Step>()
        val manifest = readProfile(env, profile)
            ?: return InstallResult(false, steps, "读不到 profile 配置")
        manifest.optJSONObject("dependencies")?.remove(name)
        val dsh = manifest.optJSONObject("dsh")
        val prof = dsh?.optJSONObject("profile")
        if (prof != null) {
            val arr = prof.optJSONArray("bundles") ?: JSONArray()
            val names = arr.toStringList().toMutableList().filter { it != name }
            prof.put("bundles", JSONArray(names))
            dsh.put("profile", prof)
            manifest.put("dsh", dsh)
        }
        if (!writeProfile(env, profile, manifest, cacheDir)) {
            steps.add(Step("更新 profile 配置", false))
            return InstallResult(false, steps, "写入 profile 配置失败")
        }
        steps.add(Step("更新 profile 配置", true, "已移除 $name"))
        val linkHost = env.hostPath("${env.dshHome}/profiles/$profile/node_modules/$name")
        // link: 依赖既可能是软链，也可能是我们直接写入时复制的真实目录 → rm -rf。
        RootShell.run("rm -rf " + RootShell.q(linkHost), 60_000)
        fixOwnership(env, listOf("${env.dshHome}/profiles/$profile"))
        normalizeDshHome(env)
        return InstallResult(true, steps, "已用直接写入方式卸载 $name")
    }

    fun backupProfile(env: DshEnv, profile: String): String? {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val rel = "${env.dshHome}/plugin-installer-backups/$ts/$profile"
        val host = env.hostPath(rel)
        val src = env.hostPath("${env.dshHome}/profiles/$profile")
        RootShell.run("mkdir -p " + RootShell.q(host), 20_000)
        var copied = false
        for (f in listOf("package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml", "compatibility.json", "cordis.patch.yml", "cordis.yml")) {
            val r = RootShell.run("cp -f " + RootShell.q("$src/$f") + " " + RootShell.q("$host/$f") + " 2>/dev/null; true", 20_000)
            if (f == "package.json" && r.code == 0) copied = true
        }
        return if (copied) rel else null
    }

    fun install(
        env: DshEnv,
        pkg: PluginPkg,
        profile: String,
        allowIncompat: Boolean,
        opDir: File,
        scriptsDir: File,
        cacheDir: File,
        allowDirect: Boolean = true
    ): InstallResult {
        val steps = ArrayList<Step>()
        val targetInside = pkg.targetInside(env)

        val script = File(scriptsDir, "install.sh")
        if (!script.isFile) return InstallResult(false, steps, "缺少 install.sh")
        val cmd = "ROOTFS=" + RootShell.q(env.rootfs) +
            " DSH_HOME=" + RootShell.q(env.dshHome) +
            " PROFILE=" + RootShell.q(profile) +
            " PKG=" + RootShell.q(pkg.name) +
            " VER=" + RootShell.q(pkg.version) +
            " TYPE=" + RootShell.q(pkg.kind) +
            " HERE=" + RootShell.q(opDir.absolutePath) +
            " sh " + RootShell.q(script.absolutePath)
        val r1 = RootShell.run(cmd, 180_000)
        if (!r1.ok) {
            steps.add(Step("复制插件文件到运行时", false, tail(r1)))
            return InstallResult(false, steps, "复制插件文件失败")
        }
        steps.add(Step("复制插件文件到运行时", true, targetInside))

        if (allowIncompat) {
            val r2 = Proot.exec(
                env,
                listOf("/usr/local/bin/dsh", "plugin", "--profile", profile, "allow-version",
                    "${pkg.name}@${pkg.version}", "--dsh-version", env.dshVersion, "--accept-risk"),
                180_000
            )
            if (r2.ok) {
                steps.add(Step("登记版本豁免", true, "${pkg.name}@${pkg.version} → ${env.dshVersion}"))
                normalizeDshHome(env)
            } else if (directExemption(env, profile, pkg.name, pkg.version, env.dshVersion, cacheDir)) {
                steps.add(Step("登记版本豁免", true, "已直接写入 compatibility.json"))
            } else {
                steps.add(Step("登记版本豁免", false, tail(r2)))
            }
        }

        val r3 = Proot.exec(
            env,
            listOf("/usr/local/bin/dsh", "plugin", "--profile", profile, "add", targetInside),
            300_000
        )
        if (!r3.ok) {
            steps.add(Step("调用 DSH 安装命令", false, tail(r3)))
            if (!allowDirect) {
                Logx.i("安全模式：DSH CLI 不可用，已停止，不改动 profile 配置")
                return InstallResult(
                    false, steps,
                    "DSH 官方安装命令不可用，已按安全模式停止，未改动任何配置（避免再次损坏启动）。"
                )
            }
            Logx.i("DSH CLI 安装失败，改用直接写入模式：" + tail(r3))
            val direct = directAdd(env, pkg, profile, cacheDir)
            steps.addAll(direct.steps)
            if (!direct.ok) return InstallResult(false, steps, "安装失败：" + direct.message)
            val m2 = readProfile(env, profile)
            val dep2 = m2?.optJSONObject("dependencies")?.optString(pkg.name, "")
            if (dep2.isNullOrBlank()) {
                steps.add(Step("校验安装结果", false, "依赖未写入"))
                return InstallResult(false, steps, "安装失败：依赖未写入")
            }
            steps.add(Step("校验安装结果", true, dep2))
            normalizeDshHome(env)
            return InstallResult(true, steps, direct.message)
        }
        steps.add(Step("调用 DSH 安装命令", true))
        val materialized = materializeEntry(env, profile, pkg.name)
        if (materialized) steps.add(Step("固化插件目录", true, "node_modules/${pkg.name} 已换成真实目录"))
        else steps.add(Step("固化插件目录", false, "软链接未能替换，重启后可能仍失败"))
        normalizeDshHome(env)

        val manifest = readProfile(env, profile)
        if (manifest == null) {
            steps.add(Step("校验安装结果", false, "读不到 profile 的 package.json"))
            return InstallResult(false, steps, "安装命令成功，但校验失败")
        }
        val dep = manifest.optJSONObject("dependencies")?.optString(pkg.name, "")
        if (dep.isNullOrBlank()) {
            steps.add(Step("校验安装结果", false, "依赖未写入"))
            return InstallResult(false, steps, "安装命令返回成功，但依赖没有写入 profile")
        }
        val enabled = (manifest.optJSONObject("dsh")?.optJSONObject("profile")
            ?.optJSONArray("bundles")?.toStringList() ?: emptyList()).contains(pkg.name)
        if (pkg.kind != "npm" && !enabled) {
            steps.add(Step("校验安装结果", true, "已安装，但未自动启用（可在列表里手动启用）"))
        } else {
            steps.add(Step("校验安装结果", true, dep))
        }
        return InstallResult(true, steps, "安装成功。重启 dsh-Go 后插件生效。")
    }

    fun uninstall(env: DshEnv, profile: String, name: String, kind: String, scriptsDir: File): InstallResult {
        val steps = ArrayList<Step>()

        // 1) 官方命令：插件已经损坏时它可能自己都起不来，所以失败也继续往下强制清理。
        val r = Proot.exec(env, listOf("/usr/local/bin/dsh", "plugin", "--profile", profile, "remove", name), 240_000)
        if (r.ok) {
            steps.add(Step("调用 DSH 卸载命令", true))
        } else {
            steps.add(Step("调用 DSH 卸载命令", false, "失败，改用强制清理：" + tail(r)))
        }

        // 2) 删除 App 管理的本地插件副本（local-plugins / dsh-go/plugins）。
        val script = File(scriptsDir, "remove.sh")
        if (script.isFile) {
            val cmd = "ROOTFS=" + RootShell.q(env.rootfs) +
                " DSH_HOME=" + RootShell.q(env.dshHome) +
                " PKG=" + RootShell.q(name) +
                " TYPE=" + RootShell.q(kind) +
                " CLEAN=1 sh " + RootShell.q(script.absolutePath)
            val r2 = RootShell.run(cmd, 60_000)
            steps.add(Step("删除本地插件副本", r2.ok, if (r2.ok) "" else tail(r2)))
        }

        // 3) 清掉版本豁免。
        pruneExemptions(env, profile, name)

        // 4) 配置级强制清理（幂等：依赖、bundles、node_modules 一起处理）。
        val direct = directRemove(env, profile, name, File("/data/local/tmp"))
        steps.addAll(direct.steps)

        // 5) 校验：依赖和 bundles 里都必须不再出现。
        val after = readProfile(env, profile)
        val stillDep = after?.optJSONObject("dependencies")?.has(name) == true
        val stillBundle = (after?.optJSONObject("dsh")?.optJSONObject("profile")
            ?.optJSONArray("bundles")?.toStringList() ?: emptyList()).contains(name)
        if (stillDep || stillBundle) {
            normalizeDshHome(env)
            return InstallResult(false, steps, "卸载后配置里仍残留 $name（依赖=" + stillDep + "，启用=" + stillBundle + "）")
        }
        steps.add(Step("校验卸载结果", true, "配置中已没有 $name"))
        normalizeDshHome(env)
        return InstallResult(true, steps, "已卸载 $name，重启 dsh-Go 后生效")
    }

    private fun pruneExemptions(env: DshEnv, profile: String, name: String) {
        val path = env.hostPath("${env.dshHome}/profiles/$profile/compatibility.json")
        val text = RootShell.readFile(path) ?: return
        try {
            val obj = JSONObject(text)
            val keys = obj.keys().asSequence().toList()
            var changed = false
            for (k in keys) {
                if (k == name || k.startsWith("$name@")) { obj.remove(k); changed = true }
            }
            if (changed) {
                val dir = File(env.hostPath("${env.dshHome}/profiles/$profile"), ".")
                // 直接覆盖写回
                val cache = File("/data/local/tmp")
                RootShell.writeText(path, obj.toString(2) + "\n", cache)
                Logx.i("已清理 compatibility.json 中的版本豁免：$name")
            }
        } catch (e: Exception) {
            Logx.i("清理 compatibility.json 失败：${e.message}")
        }
    }

    /**
     * 按名字强制清理一个插件：配置依赖、启用列表、node_modules 条目、本地副本、版本豁免全清。
     * 每一步失败都继续，用于“插件坏了、列表里又看不到”的救急场景。
     */
    fun forceClean(env: DshEnv, name: String, cacheDir: File): String {
        val notes = ArrayList<String>()
        for (p in env.profiles) {
            val manifest = readProfile(env, p.name) ?: continue
            var changed = false
            val deps = manifest.optJSONObject("dependencies")
            if (deps != null && deps.has(name)) {
                deps.remove(name); manifest.put("dependencies", deps); changed = true
            }
            val dsh = manifest.optJSONObject("dsh")
            val prof = dsh?.optJSONObject("profile")
            if (prof != null) {
                val names = (prof.optJSONArray("bundles")?.toStringList() ?: emptyList()).toMutableList()
                if (names.remove(name)) {
                    prof.put("bundles", JSONArray(names)); dsh.put("profile", prof)
                    manifest.put("dsh", dsh); changed = true
                }
            }
            if (changed) {
                backupProfile(env, p.name)
                if (writeProfile(env, p.name, manifest, cacheDir)) notes.add("${p.name}: 已从配置移除 $name")
                else notes.add("${p.name}: 写入配置失败")
            }
            val entry = env.hostPath("${env.dshHome}/profiles/${p.name}/node_modules/$name")
            val rm = RootShell.run("rm -rf " + RootShell.q(entry) + " 2>/dev/null; true", 60_000)
            if (!rm.ok) notes.add("${p.name}: 删除 node_modules/$name 时命令返回 ${rm.code}")
            pruneExemptions(env, p.name, name)
        }
        val lp = env.hostPath("${env.dshHome}/local-plugins")
        val gp = env.hostPath("${env.dshHome}/dsh-go/plugins/$name")
        RootShell.run(
            "rm -rf " + RootShell.q(gp) + "; for d in " + RootShell.q("$lp/$name") + "-*; do rm -rf \"\$d\" 2>/dev/null; done; true",
            60_000
        )
        notes.add("已清理本地插件副本（local-plugins / dsh-go/plugins）")
        normalizeDshHome(env)
        notes.add("已把 DSH_HOME 属主与权限归还给 dsh-Go")
        return notes.joinToString("\n")
    }

    fun setBundleEnabled(env: DshEnv, profile: String, name: String, enabled: Boolean, cacheDir: File): InstallResult {
        val steps = ArrayList<Step>()
        val manifest = readProfile(env, profile)
            ?: return InstallResult(false, steps, "读不到 profile 配置")
        val dsh = manifest.optJSONObject("dsh") ?: JSONObject()
        val prof = dsh.optJSONObject("profile") ?: JSONObject()
        val bundles = prof.optJSONArray("bundles") ?: JSONArray()
        val names = bundles.toStringList().toMutableList()
        if (enabled) {
            if (!names.contains(name)) names.add(name)
        } else {
            names.removeAll { it == name }
        }
        prof.put("bundles", JSONArray(names))
        dsh.put("profile", prof)
        manifest.put("dsh", dsh)
        val bk = backupProfile(env, profile)
        val ok = RootShell.writeText(profilePath(env, profile), manifest.toString(2) + "\n", cacheDir)
        if (!ok) return InstallResult(false, steps, "写入 profile 配置失败")
        fixOwnership(env, listOf("${env.dshHome}/profiles/$profile"))
        normalizeDshHome(env)
        steps.add(Step(if (enabled) "启用插件" else "停用插件", true))
        if (bk != null) steps.add(Step("已备份原配置", true, bk))
        return InstallResult(true, steps, "已" + (if (enabled) "启用" else "停用") + " $name。重启 dsh-Go 后生效。")
    }

    // ---------- 解除版本限制 ----------

    fun unlockStatus(env: DshEnv): Boolean {
        val host = env.hostPath(BOOT_INDEX)
        val r = RootShell.run("grep -c " + RootShell.q(UNLOCK_MARK) + " " + RootShell.q(host) + " 2>/dev/null; true", 20_000)
        return r.out.trim().toIntOrNull()?.let { it > 0 } ?: false
    }

    fun unlockApply(env: DshEnv, cacheDir: File): InstallResult {
        val steps = ArrayList<Step>()
        val host = env.hostPath(BOOT_INDEX)
        val text = RootShell.readFile(host) ?: return InstallResult(false, steps, "读不到 DSH 启动模块：$host")
        if (text.contains(UNLOCK_MARK)) return InstallResult(true, steps, "已经处于解除限制状态")
        val re = Regex("(?m)^function evaluatePluginCompatibility\\([^\\n]*\\)\\s*\\{")
        val m = re.find(text) ?: return InstallResult(false, steps, "没有找到兼容性判定函数（版本结构可能已变）")
        val insertAt = m.range.last + 1
        val marker = "\n\treturn void 0; // $UNLOCK_MARK disabled"
        val patched = text.substring(0, insertAt) + marker + text.substring(insertAt)

        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val backupRel = "${env.dshHome}/plugin-installer-backups/unlock-$ts/index.js.orig"
        if (!RootShell.writeText(env.hostPath(backupRel), text, cacheDir)) {
            return InstallResult(false, steps, "备份原文件失败，已取消")
        }
        steps.add(Step("备份原文件", true, backupRel))

        if (!RootShell.writeText(host, patched, cacheDir)) {
            return InstallResult(false, steps, "写入失败")
        }
        steps.add(Step("写入补丁", true))

        val check = Proot.exec(env, listOf("/opt/node/bin/node", "--check", BOOT_INDEX), 120_000)
        if (!check.ok) {
            RootShell.writeText(host, text, cacheDir)
            steps.add(Step("语法自检", false, tail(check)))
            return InstallResult(false, steps, "补丁后语法自检失败，已自动还原")
        }
        steps.add(Step("语法自检", true))
        fixOwnership(env, listOf(BOOT_INDEX))
        if (!unlockStatus(env)) {
            RootShell.writeText(host, text, cacheDir)
            return InstallResult(false, steps, "补丁未生效，已自动还原")
        }
        return InstallResult(true, steps, "已解除版本限制。重启 dsh-Go 后，所有插件不再被版本门拦截。")
    }

    fun unlockRestore(env: DshEnv, cacheDir: File): InstallResult {
        val steps = ArrayList<Step>()
        val host = env.hostPath(BOOT_INDEX)
        val text = RootShell.readFile(host) ?: return InstallResult(false, steps, "读不到 DSH 启动模块")
        if (!text.contains(UNLOCK_MARK)) return InstallResult(true, steps, "当前是官方版本判定，无需还原")
        val clean = text.replace(
            Regex("(?m)^[ \\t]*return void 0; // " + Regex.escape(UNLOCK_MARK) + "[^\\n]*\\n?"), "")
        if (!RootShell.writeText(host, clean, cacheDir)) return InstallResult(false, steps, "写入失败")
        steps.add(Step("移除补丁", true))
        val check = Proot.exec(env, listOf("/opt/node/bin/node", "--check", BOOT_INDEX), 120_000)
        if (!check.ok) {
            RootShell.writeText(host, text, cacheDir)
            steps.add(Step("语法自检", false, tail(check)))
            return InstallResult(false, steps, "还原后语法自检失败，已回滚")
        }
        steps.add(Step("语法自检", true))
        fixOwnership(env, listOf(BOOT_INDEX))
        return InstallResult(true, steps, "已还原官方的版本兼容判定。重启 dsh-Go 后生效。")
    }

    // ---------- 备份 ----------

    fun listBackups(env: DshEnv): List<String> {
        val dir = env.hostPath("${env.dshHome}/plugin-installer-backups")
        val r = RootShell.run("ls -1 " + RootShell.q(dir) + " 2>/dev/null; true", 20_000)
        return r.out.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.sortedDescending()
    }

    fun restoreLatest(env: DshEnv, profile: String): InstallResult {
        val steps = ArrayList<Step>()
        val all = listBackups(env)
        val candidates = all.filter { it.matches(Regex("\\d{8}-\\d{6}")) }
        if (candidates.isEmpty()) return InstallResult(false, steps, "还没有可用的配置备份")
        for (ts in candidates) {
            val dir = env.hostPath("${env.dshHome}/plugin-installer-backups/$ts/$profile")
            if (!RootShell.exists("$dir/package.json")) continue
            val target = env.hostPath("${env.dshHome}/profiles/$profile/package.json")
            val r = RootShell.run(
                "cp -f " + RootShell.q("$dir/package.json") + " " + RootShell.q(target) + " && chmod 644 " + RootShell.q(target), 30_000)
            if (!r.ok) return InstallResult(false, steps, "恢复失败：${tail(r)}")
            steps.add(Step("恢复备份", true, ts))
            for (f in listOf("pnpm-lock.yaml", "compatibility.json", "cordis.patch.yml")) {
                if (RootShell.exists("$dir/$f")) {
                    RootShell.run("cp -f " + RootShell.q("$dir/$f") + " " + RootShell.q(env.hostPath("${env.dshHome}/profiles/$profile/$f")) + " 2>/dev/null; true", 30_000)
                }
            }
            return InstallResult(true, steps, "已从 $ts 备份恢复 profile 配置。重启 dsh-Go 后生效。")
        }
        return InstallResult(false, steps, "备份里没有 $profile 的配置")
    }

    fun restartDshGo(env: DshEnv): ExecResult {
        val pkg = if (env.pkg.isNotEmpty()) env.pkg else "io.github.ccch1mneyyy.dsh_app"
        val user = env.userId
        return RootShell.run(
            "am force-stop --user " + user + " " + RootShell.q(pkg) + "; sleep 1; " +
                "am start --user " + user +
                " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p " + RootShell.q(pkg) +
                " >/dev/null 2>&1; true",
            60_000
        )
    }
}
