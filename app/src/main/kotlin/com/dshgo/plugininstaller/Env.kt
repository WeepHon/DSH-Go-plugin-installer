package com.dshgo.plugininstaller

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

fun JSONArray.toStringList(): List<String> {
    val list = ArrayList<String>(length())
    for (i in 0 until length()) list.add(optString(i))
    return list
}

/** 一个 DSH profile（配置）的本地状态。 */
class ProfileState(val name: String, val manifest: JSONObject, val compatibility: JSONObject?) {
    val dependencies: List<Pair<String, String>> by lazy {
        val arr = ArrayList<Pair<String, String>>()
        val deps = manifest.optJSONObject("dependencies") ?: return@lazy arr
        for (key in deps.keys()) arr.add(key to deps.optString(key))
        arr
    }

    val bundles: List<String> by lazy {
        manifest.optJSONObject("dsh")?.optJSONObject("profile")?.optJSONArray("bundles")?.toStringList() ?: emptyList()
    }

    fun isEnabled(name: String): Boolean = bundles.contains(name)
}

/** 探测到的手机端 DSH-Go 运行环境。 */
class DshEnv(
    val rootfs: String,
    val pkg: String,
    val proot: String,
    val dshHome: String,
    val dshVersion: String,
    val nodeVersion: String,
    val pnpmVersion: String,
    val runtimeVersion: String,
    val profiles: List<ProfileState>,
    val dshGoPlugins: List<String>,
    val engineUp: Boolean,
    val appUid: Int = 0,
    val appGid: Int = 0,
    val userId: Int = 0
) {
    fun hostPath(inside: String): String = if (rootfs == "/") inside else rootfs + inside
    fun insidePath(host: String): String = if (rootfs == "/") host else host.removePrefix(rootfs)
    fun profile(name: String): ProfileState? = profiles.firstOrNull { it.name == name }
    val hasProot: Boolean get() = proot.isNotEmpty()
    val enginePort: Int get() = 3080

    fun summary(): String {
        val sb = StringBuilder()
        sb.append("DSH Go ").append(dshVersion.ifEmpty { "?" })
        if (nodeVersion.isNotEmpty()) sb.append(" · Node ").append(nodeVersion)
        if (pnpmVersion.isNotEmpty()) sb.append(" · pnpm ").append(pnpmVersion)
        return sb.toString()
    }
}

object EnvProbe {
    private const val TAG = "EnvProbe"

    fun installScripts(assets: AssetManager, scriptsDir: File) {
        scriptsDir.mkdirs()
        val inner = assets.list("scripts") ?: emptyArray()
        for (name in inner) {
            val target = File(scriptsDir, name)
            try {
                assets.open("scripts/$name").use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                }
            } catch (e: Exception) {
                Logx.i("脚本释放失败 $name: ${e.message}")
            }
        }
        try {
            assets.open("preflight.mjs").use { input ->
                File(scriptsDir, "preflight.mjs").outputStream().use { out -> input.copyTo(out) }
            }
        } catch (e: Exception) {
            Logx.i("preflight.mjs 释放失败: ${e.message}")
        }
    }

    fun discover(scriptsDir: File): Result<DshEnv> {
        val script = File(scriptsDir, "discover.sh")
        if (!script.isFile) return Result.failure(IllegalStateException("缺少探测脚本"))
        val sh = "sh " + RootShell.q(script.absolutePath)
        // 逐个尝试 su 的命名空间参数；成功的那个会固定给后续所有命令使用（KernelSU 常需 -M）
        val variants = listOf(
            emptyList<String>(),
            listOf("-M"),
            listOf("--mount-master")
        )
        var best: ExecResult? = null
        var okPrefix: List<String>? = null
        for (pref in variants) {
            val r = RootShell.runArgs(pref + listOf("-c", sh), 120_000)
            if (r.out.contains("OK=1")) { best = r; okPrefix = pref; break }
            if (best == null || (!best!!.out.contains("@@DIAG") && r.out.contains("@@DIAG"))) best = r
            Logx.i("探测变体未成功（su " + (if (pref.isEmpty()) "-c" else pref.joinToString(" ")) + "，code=${r.code}）")
        }
        if (okPrefix == null) {
            // nsenter 兜底：仅用于探测，后续命令仍走普通 su
            for (ns in listOf("nsenter", "/system/bin/nsenter")) {
                val r = RootShell.runArgs(
                    listOf("-c", ns + " -t 1 -m -- /system/bin/sh " + RootShell.q(script.absolutePath)), 120_000
                )
                if (r.out.contains("OK=1")) { best = r; break }
            }
        } else {
            RootShell.setMode(okPrefix)
        }
        val result = best ?: ExecResult(1, "探测未执行")
        if (result.timedOut) return Result.failure(IllegalStateException("探测超时（root 授权弹窗未处理？）"))
        if (!result.out.contains("OK=1") && !result.out.contains("@@DIAG")) {
            return Result.failure(IllegalStateException("探测失败：退出码 ${result.code}，" + result.out.take(300)))
        }

        val kv = LinkedHashMap<String, String>()
        val profileNames = ArrayList<String>()
        val dshGoPluginNames = ArrayList<String>()
        val blocks = LinkedHashMap<String, String>()
        var current: String? = null
        val buf = StringBuilder()
        var inDiag = false
        val diag = ArrayList<String>()
        for (line in result.out.split("\n")) {
            if (line == "@@DIAG") { inDiag = true; continue }
            if (line.startsWith("@@FILE ")) {
                current = line.removePrefix("@@FILE ").trim()
                buf.setLength(0)
                continue
            }
            if (line == "@@END") {
                if (inDiag) { inDiag = false; continue }
                if (current != null) blocks[current!!] = buf.toString()
                current = null
                continue
            }
            if (inDiag) { diag.add(line); continue }
            if (current != null) {
                buf.append(line).append('\n')
            } else if (line.contains('=')) {
                val key = line.substringBefore('=')
                val value = line.substringAfter('=')
                if (key == "DSHGO_PLUGIN") {
                    if (value.isNotBlank() && !dshGoPluginNames.contains(value)) dshGoPluginNames.add(value)
                } else {
                    kv[key] = value
                }
                if (key == "PROFILE" && value.isNotBlank() && !profileNames.contains(value)) profileNames.add(value)
            }
        }

        if (kv["ERROR"] == "no_rootfs") {
            val detail = diag.joinToString("；").take(600)
            Logx.i("环境探测失败，诊断：\n" + result.out.take(1800))
            return Result.failure(IllegalStateException("未找到 DSH-Go 运行时" + if (detail.isBlank()) "" else "。诊断：$detail"))
        }
        val rootfs = kv["ROOTFS"] ?: return Result.failure(IllegalStateException("缺少 ROOTFS"))
        val dshHome = kv["DSH_HOME"] ?: "/root/.dsh"
        val runtime = blocks["/etc/dsh-app/runtime.json"]?.let {
            try { JSONObject(it) } catch (e: Exception) { null }
        }

        for ((path, _) in blocks) {
            val m = Regex(".*/profiles/([^/]+)/package\\.json$").find(path) ?: continue
            val n = m.groupValues[1]
            if (!profileNames.contains(n)) profileNames.add(n)
        }
        val profiles = ArrayList<ProfileState>()
        for (name in profileNames) {
            val manifestText = blocks["$dshHome/profiles/$name/package.json"] ?: continue
            val manifest = try { JSONObject(manifestText) } catch (e: Exception) { continue }
            val compatText = blocks["$dshHome/profiles/$name/compatibility.json"]
            val compat = if (compatText != null) try { JSONObject(compatText) } catch (e: Exception) { null } else null
            profiles.add(ProfileState(name, manifest, compat))
        }

        val dshGoPlugins = ArrayList<String>(dshGoPluginNames)
        if (blocks.isEmpty() && profiles.isEmpty()) {
            return Result.failure(IllegalStateException("探测结果为空，请重试"))
        }

        val env = DshEnv(
            rootfs = rootfs,
            pkg = kv["PKG"] ?: "",
            proot = kv["PROOT"] ?: "",
            dshHome = dshHome,
            dshVersion = runtime?.optString("dshVersion") ?: "",
            nodeVersion = runtime?.optString("nodeVersion") ?: "",
            pnpmVersion = runtime?.optString("pnpmVersion") ?: "",
            runtimeVersion = runtime?.optString("runtimeVersion") ?: "",
            profiles = profiles,
            dshGoPlugins = dshGoPlugins,
            engineUp = isPortOpen(3080),
            appUid = kv["APP_UID"]?.toIntOrNull() ?: 0,
            appGid = kv["APP_GID"]?.toIntOrNull() ?: 0,
            userId = kv["USER_ID"]?.toIntOrNull() ?: 0
        )
        Logx.i("环境探测完成：$rootfs，profile=${profiles.joinToString(",") { it.name }}，proot=${if (env.proot.isEmpty()) "缺失" else "已找到"}")
        return Result.success(env)
    }

    fun isPortOpen(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 450); true }
    } catch (e: Exception) {
        false
    }

    /** 读取已安装依赖的真实版本（node_modules/<name>/package.json）。 */
    fun readInstalledVersions(env: DshEnv, profile: String, names: List<String>): Map<String, String> {
        if (names.isEmpty()) return emptyMap()
        val base = "${env.dshHome}/profiles/$profile/node_modules"
        val sb = StringBuilder()
        for (name in names) {
            sb.append("echo '@@P ").append(name.replace("'", "")).append("'\n")
            sb.append("cat ").append(RootShell.q(env.hostPath("$base/$name/package.json")))
                .append(" 2>/dev/null | head -c 8192\n")
            sb.append("echo '@@EOF'\n")
        }
        val r = RootShell.run(sb.toString(), 45_000)
        if (!r.ok) return emptyMap()
        val out = LinkedHashMap<String, String>()
        var current: String? = null
        val buf = StringBuilder()
        for (line in r.out.split("\n")) {
            if (line.startsWith("@@P ")) {
                current = line.removePrefix("@@P ").trim()
                buf.setLength(0)
            } else if (line == "@@EOF") {
                if (current != null) out[current!!] = buf.toString()
                current = null
            } else if (current != null) {
                buf.append(line).append('\n')
            }
        }
        val versions = LinkedHashMap<String, String>()
        for ((name, text) in out) {
            try {
                versions[name] = JSONObject(text).optString("version")
            } catch (e: Exception) {
            }
        }
        return versions
    }
}
