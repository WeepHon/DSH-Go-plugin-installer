package com.dshgo.plugininstaller

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** 一条体检结论。bad=true 表示这类问题会直接让 dsh-Go 起不来。 */
data class PluginNote(val bad: Boolean, val text: String)

/** 一个已安装插件的汇总视图（跨所有 profile 合并）。 */
data class InstalledPlugin(
    val name: String,
    val version: String,
    val kind: String,
    val specs: Map<String, String>,
    val enabledIn: List<String>,
    val notes: List<PluginNote>,
    val sizeText: String
) {
    val problems: Int get() = notes.count { it.bad }
    val enabled: Boolean get() = enabledIn.isNotEmpty()
    val stateText: String
        get() = when {
            problems > 0 -> "有 $problems 个会崩的问题"
            enabled -> if (enabledIn.size > 1) "已启用（${enabledIn.joinToString("、")}）" else "已启用"
            else -> "已停用"
        }
}

data class PurgeReport(val steps: List<Step>, val leftovers: List<String>) {
    val ok: Boolean get() = leftovers.isEmpty()

    fun text(): String = buildString {
        for (s in steps) {
            append(if (s.ok) "✓ " else "✗ ").append(s.label)
            if (s.detail.isNotBlank()) append("（").append(s.detail).append('）')
            append('\n')
        }
        if (leftovers.isEmpty()) {
            append("\n残留检查：干净，没有发现遗留项。")
        } else {
            append("\n残留检查：还有 ").append(leftovers.size).append(" 项没清掉：\n")
            for (l in leftovers) append("· ").append(l).append('\n')
        }
    }
}

/**
 * 插件体检与彻底清理。
 * 目标场景：装完插件后 dsh-Go 打不开 / 连不上 AI agent，需要在不依赖引擎的情况下把插件连根拔掉。
 */
object PluginManager {

    private fun profileDir(env: DshEnv, profile: String) = env.hostPath("${env.dshHome}/profiles/$profile")

    private fun nodeModules(env: DshEnv, profile: String) = "${profileDir(env, profile)}/node_modules"

    private fun localPluginsDir(env: DshEnv) = env.hostPath("${env.dshHome}/local-plugins")

    private fun dshGoPluginsDir(env: DshEnv) = env.hostPath("${env.dshHome}/dsh-go/plugins")

    fun isInternal(name: String): Boolean =
        name.startsWith("@deepseek-ai/dsh") || name.startsWith("dsh-profile-")

    // ---------------- 扫描 ----------------

    fun scan(env: DshEnv, versions: Map<String, Map<String, String>>): List<InstalledPlugin> {
        val profilesOf = LinkedHashMap<String, MutableList<String>>()
        val specsOf = HashMap<String, MutableMap<String, String>>()
        val enabledOf = HashMap<String, MutableList<String>>()
        for (p in env.profiles) {
            for ((name, spec) in p.dependencies) {
                if (isInternal(name)) continue
                profilesOf.getOrPut(name) { ArrayList() }.add(p.name)
                specsOf.getOrPut(name) { LinkedHashMap() }[p.name] = spec
                if (p.isEnabled(name)) enabledOf.getOrPut(name) { ArrayList() }.add(p.name)
            }
        }
        val out = ArrayList<InstalledPlugin>(profilesOf.size)
        for ((name, profs) in profilesOf) {
            val specs = specsOf[name] ?: emptyMap()
            val enabledIn = enabledOf[name] ?: emptyList()
            val version = profs.mapNotNull { versions[it]?.get(name) }
                .firstOrNull { it.isNotBlank() }
                ?: specs.values.firstOrNull { it.isNotBlank() }
                ?: "—"
            out.add(
                InstalledPlugin(
                    name = name,
                    version = version,
                    kind = kindOf(env, name, specs),
                    specs = specs,
                    enabledIn = enabledIn,
                    notes = diagnose(env, name, specs, enabledIn),
                    sizeText = sizeOf(env, name)
                )
            )
        }
        out.sortBy { it.name }
        return out
    }

    private fun kindOf(env: DshEnv, name: String, specs: Map<String, String>): String {
        if (env.dshGoPlugins.contains(name)) return "dsh-go 原生"
        val spec = specs.values.firstOrNull() ?: ""
        return when {
            spec.startsWith("file:") -> "本地文件夹"
            spec.startsWith("link:") -> "本地链接"
            spec.contains("://") || spec.startsWith("git") -> "远程包"
            else -> "npm 包"
        }
    }

    /** 体检：把"会让引擎崩"和"只是提示"两类情况分开返回。 */
    fun diagnose(
        env: DshEnv,
        name: String,
        specs: Map<String, String>,
        enabledIn: List<String>
    ): List<PluginNote> {
        val notes = ArrayList<PluginNote>()
        if (env.dshGoPlugins.contains(name)) notes.add(PluginNote(false, "放在 dsh-go/plugins（原生插件目录）"))

        for ((profile, spec) in specs) {
            val entry = "${nodeModules(env, profile)}/$name"
            val exists = RootShell.exists(entry)
            val link = RootShell.run("readlink " + RootShell.q(entry) + " 2>/dev/null", 15_000).out.trim()
            if (spec.startsWith("file:") || spec.startsWith("link:")) {
                val target = spec.substringAfter(':').trim()
                val host = if (target.startsWith("/")) env.hostPath(target) else "${profileDir(env, profile)}/$target"
                if (!RootShell.exists("$host/package.json")) {
                    notes.add(PluginNote(true, "$profile：依赖指向的目录已不存在（$target）"))
                }
            }
            if (!exists) {
                notes.add(PluginNote(true, "$profile：node_modules 里没有这个包，引擎会报「找不到模块」"))
            } else if (link.isNotEmpty()) {
                notes.add(PluginNote(false, "$profile：node_modules 条目是软链 → $link（部分机型 realpath 会失败，建议改成真实目录）"))
            }
            if (enabledIn.contains(profile) && !exists) {
                notes.add(PluginNote(true, "$profile：显示「已启用」但文件缺失，这是打不开的头号原因"))
            }
        }

        val patchRefs = ArrayList<String>()
        for (p in env.profiles) {
            val text = RootShell.readFile("${profileDir(env, p.name)}/cordis.patch.yml") ?: continue
            if (referencesName(text, name)) patchRefs.add(p.name)
        }
        if (patchRefs.isNotEmpty()) {
            notes.add(PluginNote(false, "配置补丁 cordis.patch.yml 里有引用（${patchRefs.joinToString("、")}），彻底卸载会一并清掉"))
        }

        val exemptIn = ArrayList<String>()
        for (p in env.profiles) {
            val text = RootShell.readFile("${profileDir(env, p.name)}/compatibility.json") ?: continue
            try {
                val obj = JSONObject(text)
                val hit = obj.keys().asSequence().any { it == name || it.startsWith("$name@") }
                if (hit) exemptIn.add(p.name)
            } catch (_: Exception) {
            }
        }
        if (exemptIn.isNotEmpty()) notes.add(PluginNote(false, "有版本豁免记录（${exemptIn.joinToString("、")}）"))

        val size = sizeOf(env, name)
        if (size.isNotEmpty()) notes.add(PluginNote(false, "本地副本占 $size，彻底卸载会一起删掉"))
        return notes
    }

    private fun sizeOf(env: DshEnv, name: String): String {
        val cmd = "s=0; for d in " + RootShell.q("${localPluginsDir(env)}/$name") + "-* " +
            RootShell.q("${localPluginsDir(env)}/$name") + " " +
            RootShell.q("${dshGoPluginsDir(env)}/$name") + "; do " +
            "if [ -d \"\$d\" ]; then k=\$(du -sk \"\$d\" 2>/dev/null | cut -f1); s=\$((s + \${k:-0})); fi; done; echo \"SIZE:\$s\""
        val r = RootShell.run(cmd, 25_000)
        val kb = r.out.lines().firstOrNull { it.trim().startsWith("SIZE:") }
            ?.trim()?.removePrefix("SIZE:")?.toLongOrNull() ?: 0L
        return when {
            kb <= 0L -> ""
            kb < 1024L -> "${kb} KB"
            else -> String.format(Locale.US, "%.1f MB", kb / 1024.0)
        }
    }

    private fun referencesName(text: String, name: String): Boolean {
        val n = java.util.regex.Pattern.quote(name)
        val reName = Regex("(?m)^\\s*(?:-\\s+)?name:\\s*[\"']?$n[\"']?\\s*$")
        if (reName.containsMatchIn(text)) return true
        val reId = Regex("(?m)^\\s*(?:-\\s+)?id:\\s*[\"']?$n[\"']?\\s*$")
        return reId.containsMatchIn(text)
    }

    // ---------------- 彻底清理 ----------------

    fun purge(env: DshEnv, name: String, cacheDir: File): PurgeReport {
        val steps = ArrayList<Step>(8)
        val target = name.trim()
        if (target.isEmpty()) return PurgeReport(steps, listOf("插件名不能为空"))

        // 1) 官方命令：坏插件时它自己也可能起不来，失败不阻断后续强制清理。
        var cliOk = false
        var cliDetail = ""
        for (p in env.profiles) {
            val r = try {
                Proot.exec(env, listOf("/usr/local/bin/dsh", "plugin", "--profile", p.name, "remove", target), 120_000)
            } catch (e: Exception) {
                null
            }
            if (r != null && r.ok) {
                cliOk = true
            } else if (r != null) {
                cliDetail = r.out.trim().take(160)
            }
        }
        steps.add(Step("调用 DSH 官方卸载命令", cliOk, if (cliOk) "" else ("失败，改用强制清理：" + cliDetail).trim()))

        // 2) 配置：依赖 + 启用列表
        var cfgChanged = 0
        for (p in env.profiles) {
            val manifest = readManifest(env, p.name)
            if (manifest == null) {
                steps.add(Step("${p.name}：读取配置", false, "package.json 读不出来"))
                continue
            }
            var changed = false
            val deps = manifest.optJSONObject("dependencies")
            if (deps != null && deps.has(target)) {
                deps.remove(target)
                manifest.put("dependencies", deps)
                changed = true
            }
            val dsh = manifest.optJSONObject("dsh")
            val prof = dsh?.optJSONObject("profile")
            if (prof != null) {
                val names = (prof.optJSONArray("bundles")?.toStringList() ?: emptyList()).toMutableList()
                if (names.removeAll { it == target }) {
                    prof.put("bundles", JSONArray(names))
                    dsh.put("profile", prof)
                    manifest.put("dsh", dsh)
                    changed = true
                }
            }
            if (changed) {
                if (writeManifest(env, p.name, manifest, cacheDir)) cfgChanged++ else steps.add(
                    Step("${p.name}：写入配置", false, "写 package.json 失败")
                )
            }
            // node_modules 条目
            val entry = "${nodeModules(env, p.name)}/$target"
            val rmEntry = RootShell.run(
                "rm -rf " + RootShell.q(entry) + " 2>/dev/null; test -e " + RootShell.q(entry) + " || echo GONE",
                60_000
            )
            if (rmEntry.out.contains("GONE")) {
                steps.add(Step("${p.name}：删除 node_modules/$target", true))
            } else {
                val qdir = env.hostPath("${env.dshHome}/plugin-installer-quarantine")
                RootShell.run(
                    "mkdir -p " + RootShell.q(qdir) + " && mv " + RootShell.q(entry) + " " +
                        RootShell.q("$qdir/$target-${System.currentTimeMillis()}") + " 2>/dev/null; true",
                    60_000
                )
                steps.add(Step("${p.name}：node_modules/$target 删不掉，已改名隔离", true, "存放在 plugin-installer-quarantine"))
            }
        }
        steps.add(Step("清理配置里的依赖与启用项", cfgChanged >= 0, "改动 $cfgChanged 个 profile"))

        // 3) cordis.patch.yml 里对这个插件的配置块
        var patchHits = 0
        for (file in patchFiles(env)) {
            val text = RootShell.readFile(file) ?: continue
            val (stripped, count) = stripPatchEntries(text, setOf(target))
            if (count <= 0) continue
            if (RootShell.writeText(file, stripped, cacheDir)) {
                patchHits += count
                steps.add(Step("清理配置补丁：" + file.substringAfterLast('/'), true, "移除 $count 处引用"))
            } else {
                steps.add(Step("清理配置补丁：" + file.substringAfterLast('/'), false, "写入失败"))
            }
        }
        if (patchHits == 0) steps.add(Step("清理配置补丁 cordis.patch.yml", true, "没有需要清理的引用"))

        // 4) 版本豁免
        pruneExemptions(env, target)

        // 5) 本地插件副本
        val rmCopy = RootShell.run(
            "for d in " + RootShell.q("${localPluginsDir(env)}/$target") + "-* " +
                RootShell.q("${localPluginsDir(env)}/$target") + " " +
                RootShell.q("${dshGoPluginsDir(env)}/$target") + "; do rm -rf \"\$d\" 2>/dev/null; done; echo DONE",
            90_000
        )
        steps.add(Step("删除本地插件副本（local-plugins / dsh-go/plugins）", rmCopy.out.contains("DONE")))

        // 6) 归还属主与权限
        try {
            Installer.normalizeDshHome(env)
            steps.add(Step("把 DSH_HOME 属主与权限归还给 dsh-Go", true))
        } catch (e: Exception) {
            steps.add(Step("归还属主与权限", false, e.message ?: ""))
        }

        // 7) 残留复核
        val leftovers = findLeftovers(env, target)
        return PurgeReport(steps, leftovers)
    }

    private fun findLeftovers(env: DshEnv, name: String): List<String> {
        val out = ArrayList<String>()
        for (p in env.profiles) {
            val manifest = readManifest(env, p.name)
            if (manifest != null) {
                if (manifest.optJSONObject("dependencies")?.has(name) == true) out.add("${p.name}: dependencies 里还有 $name")
                val bundles = manifest.optJSONObject("dsh")?.optJSONObject("profile")
                    ?.optJSONArray("bundles")?.toStringList() ?: emptyList()
                if (bundles.contains(name)) out.add("${p.name}: 启用列表里还有 $name")
            }
            if (RootShell.exists("${nodeModules(env, p.name)}/$name")) out.add("${p.name}: node_modules/$name 还在")
        }
        for (file in patchFiles(env)) {
            val text = RootShell.readFile(file) ?: continue
            if (referencesName(text, name)) out.add("${file.substringAfterLast('/')} 里仍有引用")
        }
        if (RootShell.exists("${localPluginsDir(env)}/$name")) out.add("local-plugins/$name 还在")
        if (RootShell.exists("${dshGoPluginsDir(env)}/$name")) out.add("dsh-go/plugins/$name 还在")
        return out
    }

    fun patchFiles(env: DshEnv): List<String> {
        val out = ArrayList<String>()
        for (p in env.profiles) out.add("${profileDir(env, p.name)}/cordis.patch.yml")
        out.add(env.hostPath("${env.dshHome}/cordis.patch.yml"))
        return out
    }

    private fun pruneExemptions(env: DshEnv, name: String) {
        for (p in env.profiles) {
            val path = "${profileDir(env, p.name)}/compatibility.json"
            val text = RootShell.readFile(path) ?: continue
            try {
                val obj = JSONObject(text)
                var changed = false
                for (k in obj.keys().asSequence().toList()) {
                    if (k == name || k.startsWith("$name@")) {
                        obj.remove(k)
                        changed = true
                    }
                }
                if (changed) RootShell.writeText(path, obj.toString(2) + "\n", File("/data/local/tmp"))
            } catch (_: Exception) {
            }
        }
    }

    // ---------------- 急救：停用 / 恢复 ----------------

    /** 一键停用所有第三方插件：只改配置，文件一律不动，dsh-Go 立刻能起来。 */
    fun disableAllThirdParty(env: DshEnv, cacheDir: File): RepairResult {
        val removed = ArrayList<String>()
        val notes = ArrayList<String>()
        for (p in env.profiles) {
            val manifest = readManifest(env, p.name)
            if (manifest == null) {
                notes.add("跳过 ${p.name}：读不到 package.json")
                continue
            }
            val dsh = manifest.optJSONObject("dsh") ?: JSONObject()
            val prof = dsh.optJSONObject("profile") ?: JSONObject()
            val names = (prof.optJSONArray("bundles")?.toStringList() ?: emptyList()).toMutableList()
            val third = names.filter { !isInternal(it) }
            if (third.isNotEmpty()) {
                names.removeAll { !isInternal(it) }
                prof.put("bundles", JSONArray(names))
                dsh.put("profile", prof)
                manifest.put("dsh", dsh)
                if (writeManifest(env, p.name, manifest, cacheDir)) {
                    for (t in third) removed.add("${p.name}: $t")
                    notes.add("${p.name}：已停用 ${third.size} 个第三方插件（文件都还在）")
                } else {
                    notes.add("${p.name}：写入失败，没有改动")
                }
            } else {
                notes.add("${p.name}：本来就没有启用的第三方插件")
            }
            val pf = "${profileDir(env, p.name)}/cordis.patch.yml"
            if (RootShell.exists(pf)) {
                val bak = "$pf.off-pluginmanager"
                val r = RootShell.run("mv " + RootShell.q(pf) + " " + RootShell.q(bak) + " && echo DONE", 30_000)
                if (r.out.contains("DONE")) notes.add("${p.name}：cordis.patch.yml 已挪到 *.off-pluginmanager")
            }
        }
        val globalPatch = env.hostPath("${env.dshHome}/cordis.patch.yml")
        if (RootShell.exists(globalPatch)) {
            val r = RootShell.run(
                "mv " + RootShell.q(globalPatch) + " " + RootShell.q("$globalPatch.off-pluginmanager") + " && echo DONE",
                30_000
            )
            if (r.out.contains("DONE")) notes.add("全局 cordis.patch.yml 已挪到 *.off-pluginmanager")
        }
        try {
            Installer.normalizeDshHome(env)
        } catch (_: Exception) {
        }
        return RepairResult(removed, notes)
    }

    /** 撤销急救：把 *.off-pluginmanager 改回原名（目标已存在则保留，不覆盖）。 */
    fun restoreDisabled(env: DshEnv): RepairResult {
        val restored = ArrayList<String>()
        val kept = ArrayList<String>()
        val dirs = ArrayList<String>()
        for (p in env.profiles) dirs.add(profileDir(env, p.name))
        dirs.add(env.hostPath(env.dshHome))
        for (dir in dirs) {
            val ls = RootShell.run(
                "ls -1 " + RootShell.q(dir) + "/cordis.patch.yml.off-pluginmanager 2>/dev/null", 20_000
            )
            for (line in ls.out.lines()) {
                val full = line.trim()
                if (full.isEmpty()) continue
                val base = full.removeSuffix(".off-pluginmanager")
                val shown = full.removePrefix(env.rootfs).let { if (it.startsWith("/")) it else "/$it" }
                if (RootShell.exists(base)) {
                    kept.add(shown)
                    continue
                }
                val r = RootShell.run("mv " + RootShell.q(full) + " " + RootShell.q(base) + " && echo DONE", 30_000)
                if (r.out.contains("DONE")) restored.add(shown) else kept.add(shown)
            }
        }
        if (restored.isNotEmpty()) {
            try {
                Installer.normalizeDshHome(env)
            } catch (_: Exception) {
            }
        }
        return RepairResult(restored, kept)
    }

    // ---------------- 配置读写与 YAML 清理 ----------------

    private fun readManifest(env: DshEnv, profile: String): JSONObject? {
        val text = RootShell.readFile("${profileDir(env, profile)}/package.json") ?: return null
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            null
        }
    }

    private fun writeManifest(env: DshEnv, profile: String, obj: JSONObject, cacheDir: File): Boolean {
        val dir = profileDir(env, profile)
        RootShell.mkdirp("${env.hostPath(env.dshHome)}/plugin-installer-backups")
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(java.util.Date())
        RootShell.run(
            "cp -f " + RootShell.q("$dir/package.json") + " " +
                RootShell.q("${env.hostPath(env.dshHome)}/plugin-installer-backups/$profile-$stamp.json") +
                " 2>/dev/null; true",
            30_000
        )
        val ok = RootShell.writeText("$dir/package.json", obj.toString(2) + "\n", cacheDir)
        if (ok) {
            try {
                Installer.fixOwnership(env, listOf("${env.dshHome}/profiles/$profile"))
            } catch (_: Exception) {
            }
        }
        return ok
    }

    /**
     * 从 cordis.patch.yml 里删掉引用某个插件的整段配置。
     * 采用"找到 name:/id: 命中行 → 向上找它所属的列表项起点 → 删到该项结束"的方式，
     * 这样 `- insert:` 下面的子项也能正确删掉而不伤到兄弟项。
     */
    fun stripPatchEntries(text: String, names: Set<String>): Pair<String, Int> {
        if (names.isEmpty() || text.isBlank()) return text to 0
        val lines = text.split("\n").toMutableList()
        val drop = BooleanArray(lines.size)
        var removed = 0

        fun indentOf(s: String): Int = s.length - s.trimStart().length
        fun isItem(s: String): Boolean = s.trimStart().startsWith("- ")

        fun endOfBlock(start: Int): Int {
            val base = indentOf(lines[start])
            var j = start + 1
            while (j < lines.size) {
                val l = lines[j]
                if (l.isBlank()) {
                    j++
                    continue
                }
                if (indentOf(l) <= base) break
                j++
            }
            return j - 1
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val m = Regex("^\\s*(?:-\\s+)?(?:name|id):\\s*[\"']?([^\"'\\s#]+)[\"']?\\s*$").find(line)
            val hit = m != null && names.contains(m.groupValues[1].trim())
            if (!hit) {
                i++
                continue
            }
            // 向上找所属列表项起点
            val ind = indentOf(line)
            var start = i
            for (k in i - 1 downTo 0) {
                val l = lines[k]
                if (l.isBlank()) continue
                if (indentOf(l) < ind && isItem(l)) {
                    start = k
                    break
                }
                if (indentOf(l) < ind && !isItem(l)) break
            }
            if (!isItem(lines[start])) start = i
            // 连带删掉紧贴上方的注释行
            var s = start
            for (k in start - 1 downTo 0) {
                val l = lines[k]
                if (l.isBlank()) break
                if (l.trimStart().startsWith("#")) {
                    s = k
                } else {
                    break
                }
            }
            val e = endOfBlock(start)
            for (k in s..e) drop[k] = true
            removed++
            i = e + 1
        }

        if (removed == 0) return text to 0

        val kept = ArrayList<String>(lines.size)
        for (k in lines.indices) if (!drop[k]) kept.add(lines[k])

        // 空掉的 `- insert:` 一起删掉
        val cleaned = ArrayList<String>(kept.size)
        var idx = 0
        while (idx < kept.size) {
            val line = kept[idx]
            if (line.trim() == "- insert:") {
                val base = line.length - line.trimStart().length
                var j = idx + 1
                var hasChild = false
                while (j < kept.size) {
                    val l = kept[j]
                    if (l.isBlank()) {
                        j++
                        continue
                    }
                    if (l.length - l.trimStart().length <= base) break
                    hasChild = true
                    j++
                }
                if (!hasChild) {
                    idx = j
                    continue
                }
            }
            cleaned.add(line)
            idx++
        }

        // 收敛多余空行
        val out = StringBuilder()
        var blankRun = 0
        for (line in cleaned) {
            if (line.isBlank()) {
                blankRun++
                if (blankRun > 1) continue
                out.append('\n')
            } else {
                blankRun = 0
                out.append(line).append('\n')
            }
        }
        return out.toString() to removed
    }
}
