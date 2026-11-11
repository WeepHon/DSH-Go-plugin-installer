package com.dshgo.plugininstaller

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.ArrayDeque
import java.util.zip.ZipInputStream

/** 一个待安装的插件包（已在 App 缓存里解压好）。 */
data class PluginPkg(
    val name: String,
    val version: String,
    val kind: String,
    val title: String?,
    val description: String?,
    val enginesDsh: String?,
    val peers: LinkedHashMap<String, String>,
    val bundlePatch: String?,
    val manifestText: String,
    val stagingDir: File,
    val fileCount: Int,
    val totalBytes: Long
) {
    val kindLabel: String
        get() = when (kind) {
            "dshgo" -> "dsh-go 原生插件"
            "bundle" -> "Cordis 插件（bundle）"
            else -> "普通插件依赖"
        }

    fun targetInside(env: DshEnv): String =
        if (kind == "dshgo") "${env.dshHome}/dsh-go/plugins/$name"
        else "${env.dshHome}/local-plugins/$name-$version"
}

data class PreflightResult(
    val ran: Boolean,
    val runtime: String?,
    val compatible: Boolean?,
    val requirements: String?,
    val error: String?
) {
    val incompatible: Boolean get() = compatible == false
}

object PluginAnalyzer {
    private const val MAX_FILES = 20000
    private const val MAX_BYTES = 256L * 1024 * 1024
    private val NAME_RE = Regex("^(@[A-Za-z0-9][A-Za-z0-9._-]*/)?[A-Za-z0-9][A-Za-z0-9._-]*$")

    fun prepareFromUri(open: (Uri) -> InputStream?, uri: Uri, opDir: File): PluginPkg {
        open(uri)?.use { input ->
            return prepareFromStream(input, opDir)
        }
        throw IllegalStateException("无法读取所选文件")
    }

    fun prepareFromStream(input: InputStream, opDir: File): PluginPkg {
        val head = ByteArray(4)
        val mark = BufferedInputStream(input)
        mark.mark(8)
        val n = mark.read(head)
        mark.reset()
        if (n < 4 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
            throw IllegalStateException("这不是 zip 压缩包。可以改用「选择文件夹」安装已解压的插件。")
        }
        val extractDir = File(opDir, "extract")
        resetDir(opDir)
        extractDir.mkdirs()
        val (count, bytes) = extractZip(mark, extractDir)
        val root = locateRoot(extractDir) ?: throw IllegalStateException("压缩包里没有找到 package.json")
        return finish(opDir, root, count, bytes)
    }

    fun prepareFromTree(tree: DocumentFile, open: (Uri) -> InputStream?, opDir: File): PluginPkg {
        resetDir(opDir)
        val extractDir = File(opDir, "extract")
        extractDir.mkdirs()
        var count = 0
        var bytes = 0L
        copyTree(tree, extractDir, open, object : CopyState {
            override fun onFile(size: Long) {
                count++
                bytes += size
                if (count > MAX_FILES) throw IllegalStateException("文件数量过多")
                if (bytes > MAX_BYTES) throw IllegalStateException("插件目录过大")
            }
        })
        val root = locateRoot(extractDir) ?: throw IllegalStateException("所选文件夹里没有找到 package.json")
        return finish(opDir, root, count, bytes)
    }

    private fun finish(opDir: File, root: File, count: Int, bytes: Long): PluginPkg {
        val manifestFile = File(root, "package.json")
        val manifestText = manifestFile.readText()
        val m = try { JSONObject(manifestText) } catch (e: Exception) {
            throw IllegalStateException("package.json 不是合法 JSON：${e.message}")
        }
        val name = m.optString("name", "").trim()
        val version = m.optString("version", "").trim().ifEmpty { "0.0.0" }
        if (name.isEmpty()) throw IllegalStateException("package.json 缺少 name 字段")
        if (!NAME_RE.matches(name)) throw IllegalStateException("包名不合法：$name")

        val dshGo = m.optJSONObject("dshGo")
        val bundle = m.optJSONObject("dsh")?.optJSONObject("bundle")
        val bundlePatch = bundle?.optString("patch", "")?.takeIf { it.isNotBlank() }
        val kind = when {
            dshGo != null -> "dshgo"
            bundlePatch != null -> "bundle"
            else -> "npm"
        }
        val title = when {
            dshGo?.optJSONObject("title") != null -> dshGo.optJSONObject("title")?.optString("zh")
                ?: dshGo.optJSONObject("title")?.optString("en")
            dshGo?.optString("title")?.isNotBlank() == true -> dshGo.optString("title")
            else -> m.optString("description", "").takeIf { it.isNotBlank() }
        }
        val peers = LinkedHashMap<String, String>()
        m.optJSONObject("peerDependencies")?.let { pd ->
            for (key in pd.keys()) {
                if (key == "@deepseek-ai/dsh" || key.startsWith("@deepseek-ai/dsh-")) {
                    peers[key] = pd.optString(key, "")
                }
            }
        }
        val staging = File(opDir, "plugin-src")
        if (staging.exists()) staging.deleteRecursively()
        root.copyRecursively(staging, overwrite = true)

        Logx.i("插件包解析完成：$name@$version（$kind，$count 个文件，${bytes / 1024} KB）")
        return PluginPkg(
            name = name,
            version = version,
            kind = kind,
            title = title,
            description = m.optString("description", "").takeIf { it.isNotBlank() },
            enginesDsh = m.optJSONObject("engines")?.optString("dsh", "")?.takeIf { it.isNotBlank() },
            peers = peers,
            bundlePatch = bundlePatch,
            manifestText = m.toString(2),
            stagingDir = staging,
            fileCount = count,
            totalBytes = bytes
        )
    }

    private fun resetDir(dir: File) {
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
    }

    private fun extractZip(input: InputStream, dest: File): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        val zis = ZipInputStream(input)
        try {
            while (true) {
                val entry = zis.nextEntry ?: break
                val raw = entry.name.replace('\\', '/')
                if (entry.isDirectory) { zis.closeEntry(); continue }
                val safe = sanitize(raw)
                if (safe == null) { zis.closeEntry(); continue }
                count++
                if (count > MAX_FILES) throw IllegalStateException("压缩包文件数量过多")
                val out = File(dest, safe)
                out.parentFile?.mkdirs()
                out.outputStream().use { o ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val n = zis.read(buf)
                        if (n < 0) break
                        bytes += n
                        if (bytes > MAX_BYTES) throw IllegalStateException("压缩包解压后过大")
                        o.write(buf, 0, n)
                    }
                }
                zis.closeEntry()
            }
        } finally {
            try { zis.close() } catch (_: Exception) {}
        }
        return count to bytes
    }

    private fun sanitize(path: String): String? {
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        if (parts.any { it == ".." }) return null
        if (path.startsWith("/")) return null
        if (parts.firstOrNull() == "__MACOSX") return null
        return parts.joinToString("/")
    }

    private fun locateRoot(dir: File): File? {
        if (File(dir, "package.json").isFile) return dir
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(dir to 0)
        while (queue.isNotEmpty()) {
            val (d, depth) = queue.removeFirst()
            if (depth >= 3) continue
            val children = d.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: continue
            for (child in children) {
                if (child.name == "node_modules") continue
                if (File(child, "package.json").isFile) return child
                queue.add(child to depth + 1)
            }
        }
        return null
    }

    private interface CopyState {
        fun onFile(size: Long)
    }

    private fun copyTree(dir: DocumentFile, dest: File, open: (Uri) -> InputStream?, state: CopyState) {
        dest.mkdirs()
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            if (name == "__MACOSX") continue
            val target = File(dest, name)
            if (!target.canonicalPath.startsWith(dest.canonicalPath + File.separator)) continue
            if (child.isDirectory) {
                copyTree(child, target, open, state)
            } else if (child.isFile) {
                state.onFile(child.length())
                child.uri.let { uri ->
                    val input = open(uri)
                    if (input == null) throw IllegalStateException("无法读取文件：$name")
                    input.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
                }
            }
        }
    }
}
