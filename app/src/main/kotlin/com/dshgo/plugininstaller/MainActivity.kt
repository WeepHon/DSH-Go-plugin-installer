package com.dshgo.plugininstaller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvStatus: TextView
    private lateinit var btnRefresh: MaterialButton
    private lateinit var btnRestart: MaterialButton
    private lateinit var tvPlugin: TextView
    private lateinit var btnPickZip: MaterialButton
    private lateinit var btnPickDir: MaterialButton
    private lateinit var btnScanDownload: MaterialButton
    private lateinit var spinnerProfile: Spinner
    private lateinit var swAllowIncompat: MaterialSwitch
    private lateinit var swSafetyOnly: MaterialSwitch
    private lateinit var btnInstall: MaterialButton
    private lateinit var progressInstall: LinearProgressIndicator
    private lateinit var tvInstallLog: TextView
    private lateinit var tvInstalledEmpty: TextView
    private lateinit var listInstalled: LinearLayout
    private lateinit var tvUnlock: TextView
    private lateinit var btnUnlock: MaterialButton
    private lateinit var btnUnlockRestore: MaterialButton
    private lateinit var tvBackups: TextView
    private lateinit var btnRestoreLatest: MaterialButton
    private lateinit var btnRepair: MaterialButton
    private lateinit var btnForceClean: MaterialButton
    private lateinit var btnRestoreSafe: MaterialButton
    private lateinit var tvLog: TextView
    private lateinit var btnCopyLog: MaterialButton
    private lateinit var btnSaveDiag: MaterialButton
    private lateinit var btnClearLog: MaterialButton

    private lateinit var scriptsDir: File
    private lateinit var opRoot: File

    private var env: DshEnv? = null
    private var prepared: PluginPkg? = null
    private var preflight: PreflightResult? = null
    private var installedVersions: Map<String, Map<String, String>> = emptyMap()
    private var unlockActive = false
    private var backupList: List<String> = emptyList()
    private var profileNames: MutableList<String> = ArrayList()
    private var currentProfile: String = ""
    private var brokenLinks: List<String> = emptyList()
    private var busy = false
    private var spinnerSyncing = false
    private var pendingUri: Uri? = null
    private val logBuffer = StringBuilder()
    private val prefs by lazy { getSharedPreferences("installer", MODE_PRIVATE) }

    private fun bindSafetySwitch() {
        swSafetyOnly.isChecked = prefs.getBoolean("safety_only", true)
        swSafetyOnly.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean("safety_only", v).apply()
        }
    }
    private var unlockDesired = false
    private var unlockVersion = ""

    private val zipPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) analyzeZip(uri)
    }
    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) analyzeTree(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        scriptsDir = File(filesDir, "scripts")
        opRoot = File(cacheDir, "op")
        EnvProbe.installScripts(assets, scriptsDir)
        Logx.listener = { line -> appendLogLine(line) }
        unlockDesired = prefs.getBoolean("unlock_desired", false)
        unlockVersion = prefs.getString("unlock_version", "") ?: ""

        if (intent?.action == Intent.ACTION_VIEW) pendingUri = intent.data

        wire()
        bindSafetySwitch()
        appendLogLine("DSH 插件安装器 v1.0.0 已启动")
        refreshAll()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        tvStatus = findViewById(R.id.tvStatus)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnRestart = findViewById(R.id.btnRestart)
        tvPlugin = findViewById(R.id.tvPlugin)
        btnPickZip = findViewById(R.id.btnPickZip)
        btnPickDir = findViewById(R.id.btnPickDir)
        btnScanDownload = findViewById(R.id.btnScanDownload)
        spinnerProfile = findViewById(R.id.spinnerProfile)
        swAllowIncompat = findViewById(R.id.swAllowIncompat)
        swSafetyOnly = findViewById(R.id.swSafetyOnly)
        btnInstall = findViewById(R.id.btnInstall)
        progressInstall = findViewById(R.id.progressInstall)
        tvInstallLog = findViewById(R.id.tvInstallLog)
        tvInstalledEmpty = findViewById(R.id.tvInstalledEmpty)
        listInstalled = findViewById(R.id.listInstalled)
        tvUnlock = findViewById(R.id.tvUnlock)
        btnUnlock = findViewById(R.id.btnUnlock)
        btnUnlockRestore = findViewById(R.id.btnUnlockRestore)
        tvBackups = findViewById(R.id.tvBackups)
        btnRestoreLatest = findViewById(R.id.btnRestoreLatest)
        btnRepair = findViewById(R.id.btnRepair)
        btnForceClean = findViewById(R.id.btnForceClean)
        btnRestoreSafe = findViewById(R.id.btnRestoreSafe)
        tvLog = findViewById(R.id.tvLog)
        btnCopyLog = findViewById(R.id.btnCopyLog)
        btnSaveDiag = findViewById(R.id.btnSaveDiag)
        btnClearLog = findViewById(R.id.btnClearLog)
    }

    private fun wire() {
        btnRefresh.setOnClickListener { refreshAll() }
        btnPickZip.setOnClickListener {
            zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "application/x-zip"))
        }
        btnPickDir.setOnClickListener { treePicker.launch(null) }
        btnScanDownload.setOnClickListener { scanDownloadZips() }
        btnInstall.setOnClickListener { doInstall() }
        swAllowIncompat.setOnCheckedChangeListener { _, _ -> updateInstallButton() }
        spinnerProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (spinnerSyncing) return
                if (position in profileNames.indices) {
                    currentProfile = profileNames[position]
                    renderPlugin()
                    updateInstallButton()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        btnRestart.setOnClickListener {
            confirm(
                "重启 dsh-Go？",
                "会强制停止 dsh-Go，当前正在进行的对话会中断；随后自动重新打开。插件在引擎重启后才会加载。",
                "重启"
            ) { restartDshGo() }
        }
        btnUnlock.setOnClickListener {
            confirm(
                "解除版本限制？",
                "会修改运行时内的 DSH 启动模块（/opt/dsh/.../dsh-app-boot/lib/index.js），跳过插件版本兼容性判定。\n\n" +
                    "修改前会自动备份原文件；可用「还原官方判定」恢复。DSH 升级后可能需要重新操作。\n\n" +
                    "风险：不兼容插件可能导致应用崩溃或数据损坏。",
                "解除限制"
            ) { doUnlock() }
        }
        btnUnlockRestore.setOnClickListener {
            confirm("还原官方版本判定？", "会移除之前写入的补丁，恢复 DSH 原本的兼容性检查，然后需要重启 dsh-Go。", "还原") { doUnlockRestore() }
        }
        btnRepair.setOnClickListener {
            confirm(
                "修复 dsh-Go 启动问题？",
                "会检查所有配置里的本地插件依赖，移除失效的依赖条目、bundles 项和 node_modules 里的坏链接，并修正目录权限。不会删除插件源文件。",
                "修复"
            ) { doRepair() }
        }
        btnForceClean.setOnClickListener { showForceCleanDialog() }
        btnRestoreSafe.setOnClickListener {
            confirm(
                "恢复被安全模式挪走的文件？",
                "安全模式会把 DSH_HOME/cordis.patch.yml、.env 改名成 *.off-<时间> 挪走，退出安全模式后不会自己放回去。" +
                    "这里把它们改回原名（同名文件已存在时保留不动）。",
                "恢复"
            ) { doRestoreSafe() }
        }
        btnRestoreLatest.setOnClickListener {
            confirm("恢复最近一次配置备份？", "会用最近的备份覆盖当前所选配置的 package.json 等元数据。", "恢复") { doRestoreLatest() }
        }
        btnCopyLog.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("DSH 插件安装器日志", Logx.snapshot()))
            toast("日志已复制")
        }
        btnSaveDiag.setOnClickListener { saveDiag() }
        btnClearLog.setOnClickListener {
            Logx.clear()
            logBuffer.setLength(0)
            tvLog.text = ""
        }
    }

    /** 把日志 + 环境摘要写到 Download/DSH-Go/，方便发给开发者定位。 */
    private fun saveDiag() {
        setBusy(true, "正在保存诊断…")
        io.execute {
            val e = env
            val sb = StringBuilder()
            sb.append("DSH 插件安装器诊断 ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())).append('\n')
            sb.append("root=").append(RootShell.hasRoot()).append(" su=").append(RootShell.su() ?: "?").append('\n')
            if (e != null) {
                sb.append("rootfs=").append(e.rootfs).append('\n')
                sb.append("pkg=").append(e.pkg).append(" userId=").append(e.userId).append('\n')
                sb.append("dsh=").append(e.dshVersion).append(" node=").append(e.nodeVersion)
                    .append(" runtime=").append(e.runtimeVersion).append('\n')
                sb.append("profiles=").append(e.profiles.joinToString(",") { it.name }).append('\n')
                sb.append("proot=").append(e.proot).append('\n')
            } else {
                sb.append("env=null\n")
            }
            sb.append("\n===== 日志 =====\n").append(Logx.snapshot())
            val text = sb.toString()
            val dir = "/sdcard/Download/DSH-Go"
            RootShell.run("mkdir -p " + RootShell.q(dir), 30_000)
            val name = "diag-" + java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".txt"
            val ok = RootShell.writeText("$dir/$name", text, cacheDir)
            ui.post {
                setBusy(false)
                if (ok) toast("已保存：$dir/$name") else toast("保存失败，请改用「复制」发日志")
            }
        }
    }

    private fun doRepair() {
        val e = env ?: run { toast("环境未就绪，请先刷新"); return }
        setBusy(true, "正在检查并修复…")
        io.execute {
            val r = try { Installer.repairStartup(e, cacheDir) } catch (ex: Exception) {
                RepairResult(emptyList(), listOf("修复失败：" + (ex.message ?: "")))
            }
            Logx.i("修复结果：" + r.text().replace('\n', ' '))
            ui.post {
                setBusy(false)
                AlertDialog.Builder(this)
                    .setTitle(if (r.removed.isEmpty()) "检查完成" else "已修复 ${r.removed.size} 项")
                    .setMessage(r.text())
                    .setPositiveButton("好", null)
                    .show()
                refreshAll()
            }
        }
    }

    // ---------------- 环境 ----------------

    private fun refreshAll() {
        if (busy) return
        setBusy(true, "正在检测环境…")
        io.execute {
            val hasRoot = RootShell.hasRoot()
            if (!hasRoot) {
                val msg = RootShell.lastError.ifBlank { "未获得 Root 授权" }
                ui.post {
                    setBusy(false)
                    env = null
                    renderNoRoot(msg)
                }
                return@execute
            }
            val res = EnvProbe.discover(scriptsDir)
            if (res.isFailure) {
                val msg = res.exceptionOrNull()?.message ?: "环境探测失败"
                ui.post {
                    setBusy(false)
                    renderError(msg)
                }
                return@execute
            }
            val e = res.getOrThrow()
            val versions = LinkedHashMap<String, Map<String, String>>()
            for (p in e.profiles) {
                val third = p.dependencies.map { it.first }.filter { !isInternal(it) }
                if (third.isNotEmpty()) {
                    versions[p.name] = EnvProbe.readInstalledVersions(e, p.name, third)
                }
            }
            val broken = try { Installer.findBroken(e) } catch (ex: Exception) { emptyList() }
            val unlock = try { Installer.unlockStatus(e) } catch (ex: Exception) { false }
            val backups = try { Installer.listBackups(e) } catch (ex: Exception) { emptyList() }
            ui.post {
                env = e
                installedVersions = versions
                brokenLinks = broken
                unlockActive = unlock
                backupList = backups
                if (currentProfile.isEmpty() || e.profile(currentProfile) == null) {
                    currentProfile = e.profiles.firstOrNull()?.name ?: ""
                }
                setBusy(false)
                renderAll()
                val pending = pendingUri
                if (pending != null) {
                    pendingUri = null
                    analyzeZip(pending)
                }
            }
        }
    }

    private fun renderAll() {
        val e = env ?: return
        renderStatus(e)
        renderProfiles(e)
        renderInstalled(e)
        renderAdvanced(e)
        renderPlugin()
        updateInstallButton()
    }

    private fun renderNoRoot(detail: String) {
        tvStatus.text = "Root：未授权 ✗\n" +
            "请在 Magisk / KernelSU / APatch 里给本应用授权 Root，然后点「刷新」。\n" +
            "详情：$detail"
        tvInstalledEmpty.visibility = View.VISIBLE
        tvInstalledEmpty.text = "未获得 Root，无法读取插件状态。"
        tvPlugin.text = "未获得 Root，暂不能安装。"
        tvUnlock.text = "版本限制：需要 Root"
        tvBackups.text = "配置备份：—"
        btnInstall.isEnabled = false
        swAllowIncompat.visibility = View.GONE
        listInstalled.removeAllViews()
    }

    private fun renderError(msg: String) {
        tvStatus.text = "环境检测失败 ✗\n$msg\n\n" +
            "常见原因：\n" +
            "1. 没有授予 Root；\n" +
            "2. dsh-Go 还没启动过（找不到运行时）；\n" +
            "3. 运行时目录被系统清理。"
        tvInstalledEmpty.visibility = View.VISIBLE
        tvInstalledEmpty.text = "环境不可用。"
        btnInstall.isEnabled = false
        swAllowIncompat.visibility = View.GONE
    }

    private fun renderStatus(e: DshEnv) {
        val sb = StringBuilder()
        sb.append("Root：已授权 ✓\n")
        sb.append("运行时：").append(e.summary()).append('\n')
        sb.append("引擎：").append(if (e.engineUp) "运行中（127.0.0.1:${e.enginePort}）" else "未运行（安装不依赖引擎，重启后插件生效）").append('\n')
        sb.append("运行时目录：").append(e.rootfs).append('\n')
        sb.append("配置：").append(if (profileNames.isEmpty()) e.profiles.joinToString(", ") { it.name } else currentProfile).append('\n')
        sb.append("dsh-go 原生插件：").append(if (e.dshGoPlugins.isEmpty()) "无" else e.dshGoPlugins.joinToString(", "))
        if (!e.hasProot) {
            sb.append("\n\n⚠ 未找到 proot（libproot.so），将使用直接写入模式安装（不经过 DSH CLI）。")
        }
        if (e.dshVersion.isEmpty()) {
            sb.append("\n⚠ 读不到 DSH 版本（runtime.json 异常）。")
        }
        if (brokenLinks.isNotEmpty()) {
            sb.append("\n\n⚠ 发现失效的本地插件依赖（会让 dsh-Go 启动失败）：")
            sb.append(brokenLinks.joinToString("、"))
            sb.append("\n点下方「修复 dsh-Go 启动问题」清理。")
        }
        tvStatus.text = sb.toString()
    }

    private fun renderProfiles(e: DshEnv) {
        val names = e.profiles.map { it.name }
        if (names != profileNames) {
            profileNames = names.toMutableList()
            spinnerSyncing = true
            spinnerProfile.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, profileNames)
            var idx = profileNames.indexOf(currentProfile)
            if (idx < 0) idx = 0
            if (profileNames.isNotEmpty()) {
                spinnerProfile.setSelection(idx)
                currentProfile = profileNames[idx]
            } else {
                currentProfile = ""
            }
            spinnerSyncing = false
        }
        if (profileNames.isEmpty()) {
            tvStatus.append("\n⚠ 没有已初始化的 profile，先启动一次 dsh-Go 再刷新。")
        }
    }

    private fun renderInstalled(e: DshEnv) {
        listInstalled.removeAllViews()
        var count = 0
        for (p in e.profiles) {
            val third = p.dependencies.filter { !isInternal(it.first) }
            if (third.isEmpty()) continue
            if (e.profiles.size > 1) listInstalled.addView(sectionLabel("配置 ${p.name}"))
            for ((name, spec) in third) {
                count++
                listInstalled.addView(pluginRow(e, p, name, spec))
            }
        }
        tvInstalledEmpty.visibility = if (count == 0) View.VISIBLE else View.GONE
    }

    private fun pluginRow(e: DshEnv, p: ProfileState, name: String, spec: String): View {
        val version = installedVersions[p.name]?.get(name)?.takeIf { it.isNotBlank() } ?: spec
        val kind = when {
            e.dshGoPlugins.contains(name) -> "dsh-go 原生"
            spec.startsWith("link:") -> "本地链接"
            spec.contains("://") || spec.startsWith("git") -> "远程包"
            else -> "npm 包"
        }
        val enabled = p.isEnabled(name)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val line1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line1.addView(TextView(this).apply {
            text = name
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        line1.addView(TextView(this).apply {
            text = version
            textSize = 12f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
        })
        container.addView(line1)

        val line2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line2.addView(TextView(this).apply {
            text = "类型：$kind · 状态：" + if (enabled) "已启用" else "已停用"
            textSize = 11f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = MaterialSwitch(this).apply {
            text = "启用"
            textSize = 12f
            isChecked = enabled
        }
        sw.setOnCheckedChangeListener { _, checked ->
            if (!spinnerSyncing) toggleBundle(p.name, name, checked)
        }
        line2.addView(sw)
        val del = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "卸载"
            textSize = 12f
            minWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
        }
        del.setOnClickListener { confirmUninstall(e, p.name, name) }
        line2.addView(del)
        container.addView(line2)
        return container
    }

    // ---------------- 选择与解析 ----------------

    /** 用 root 列 /sdcard/Download 下的 zip，避开 App 没有存储权限的问题。 */
    private fun scanDownloadZips() {
        setBusy(true, "正在扫描 Download 文件夹…")
        io.execute {
            val r = RootShell.run(
                "find /sdcard/Download -maxdepth 3 -type f -name '*.zip' 2>/dev/null | head -n 60", 60_000
            )
            val files = r.out.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(30)
            ui.post {
                setBusy(false)
                if (files.isEmpty()) {
                    toast("Download 文件夹里没找到 zip")
                    return@post
                }
                val labels = files.map { it.substringAfterLast('/') }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("选择要安装的插件包")
                    .setItems(labels) { _, which -> analyzeRemoteZip(files[which]) }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    /** root 把选中的 zip 复制到 App 缓存（App 读不了 /sdcard/Download），再按普通 zip 解析。 */
    private fun analyzeRemoteZip(path: String) {
        val e = env ?: run { toast("环境未就绪，请先刷新"); return }
        setBusy(true, "正在读取插件包…")
        io.execute {
            try {
                val scanDir = File(opRoot, "scan")
                scanDir.mkdirs()
                val local = File(scanDir, "plugin.zip")
                local.delete()
                val cp = RootShell.run(
                    "cp -f " + RootShell.q(path) + " " + RootShell.q(local.absolutePath) +
                        " && chmod 644 " + RootShell.q(local.absolutePath), 120_000
                )
                if (!cp.ok) throw IllegalStateException("复制失败：" + cp.out.take(160))
                val opDir = File(opRoot, "current")
                val pkg = PluginAnalyzer.prepareFromStream(java.io.FileInputStream(local), opDir)
                val pf = Compat.preflight(e, pkg, scriptsDir, cacheDir, exemptionsOf(e, currentProfile))
                ui.post {
                    prepared = pkg
                    preflight = pf
                    setBusy(false)
                    tvInstallLog.visibility = View.GONE
                    renderPlugin()
                    toast("已解析：${pkg.name}@${pkg.version}")
                }
            } catch (ex: Exception) {
                val msg = ex.message ?: "解析失败"
                ui.post {
                    setBusy(false)
                    toast("解析失败：$msg")
                    Logx.i("解析失败：$msg")
                }
            }
        }
    }

    private fun analyzeZip(uri: Uri) {
        val e = env ?: run { toast("环境未就绪，请先刷新"); return }
        setBusy(true, "正在解析插件包…")
        io.execute {
            try {
                val opDir = File(opRoot, "current")
                val pkg = PluginAnalyzer.prepareFromUri({ u -> contentResolver.openInputStream(u) }, uri, opDir)
                val pf = Compat.preflight(e, pkg, scriptsDir, cacheDir, exemptionsOf(e, currentProfile))
                ui.post {
                    prepared = pkg
                    preflight = pf
                    setBusy(false)
                    tvInstallLog.visibility = View.GONE
                    renderPlugin()
                    toast("已解析：${pkg.name}@${pkg.version}")
                }
            } catch (ex: Exception) {
                val msg = ex.message ?: "解析失败"
                ui.post {
                    setBusy(false)
                    toast("解析失败：$msg")
                    Logx.i("解析失败：$msg")
                }
            }
        }
    }

    private fun analyzeTree(uri: Uri) {
        val e = env ?: run { toast("环境未就绪，请先刷新"); return }
        val tree = DocumentFile.fromTreeUri(this, uri)
        if (tree == null) {
            toast("无法读取所选文件夹")
            return
        }
        setBusy(true, "正在读取文件夹…")
        io.execute {
            try {
                val opDir = File(opRoot, "current")
                val pkg = PluginAnalyzer.prepareFromTree(
                    tree,
                    { u -> contentResolver.openInputStream(u) },
                    opDir
                )
                val pf = Compat.preflight(e, pkg, scriptsDir, cacheDir, exemptionsOf(e, currentProfile))
                ui.post {
                    prepared = pkg
                    preflight = pf
                    setBusy(false)
                    tvInstallLog.visibility = View.GONE
                    renderPlugin()
                    toast("已解析：${pkg.name}@${pkg.version}")
                }
            } catch (ex: Exception) {
                val msg = ex.message ?: "读取失败"
                ui.post {
                    setBusy(false)
                    toast("读取失败：$msg")
                    Logx.i("读取文件夹失败：$msg")
                }
            }
        }
    }

    private fun exemptionsOf(e: DshEnv, profile: String): JSONObject? =
        e.profile(profile)?.compatibility

    private fun renderPlugin() {
        val p = prepared
        if (p == null) {
            tvPlugin.text = "还没有选择插件包。选择一个 zip，或选择已解压的插件文件夹。"
            swAllowIncompat.visibility = View.GONE
            swAllowIncompat.isChecked = false
            updateInstallButton()
            return
        }
        val sb = StringBuilder()
        sb.append("名称：").append(p.name).append('\n')
        sb.append("版本：").append(p.version).append('\n')
        sb.append("类型：").append(p.kindLabel).append('\n')
        p.title?.let { if (it.isNotBlank() && it != p.name) sb.append("标题：").append(it).append('\n') }
        p.enginesDsh?.let { sb.append("要求 DSH：").append(it).append('\n') }
        if (p.peers.isNotEmpty()) {
            sb.append("要求运行时：").append(p.peers.entries.joinToString("；") { "${it.key} ${it.value}" }).append('\n')
        }
        val pf = preflight
        when {
            pf == null || !pf.ran -> sb.append("兼容性：未能检测（").append(pf?.error ?: "未检测").append("）\n")
            pf.compatible == true -> sb.append("兼容性：兼容 ").append(pf.runtime ?: "").append(" ✓\n")
            else -> sb.append("兼容性：不兼容（要求 ").append(pf.requirements ?: "未知").append("），需打开下面的开关\n")
        }
        env?.let { sb.append("目标：").append(p.targetInside(it)).append('\n') }
        if (p.fileCount > 0) sb.append("文件：").append(p.fileCount).append(" 个，").append(p.totalBytes / 1024).append(" KB")
        tvPlugin.text = sb.toString().trimEnd()
        val incompatible = pf?.incompatible == true
        swAllowIncompat.visibility = if (incompatible) View.VISIBLE else View.GONE
        if (!incompatible) swAllowIncompat.isChecked = false
        updateInstallButton()
    }

    private fun updateInstallButton() {
        val e = env
        val p = prepared
        var enabled = e != null && p != null && !busy && currentProfile.isNotEmpty() && e.profiles.isNotEmpty()
        if (preflight?.incompatible == true && !swAllowIncompat.isChecked) enabled = false
        btnInstall.isEnabled = enabled
    }

    // ---------------- 操作 ----------------

    private fun doInstall() {
        val e = env ?: run { toast("环境未就绪"); return }
        val p = prepared ?: run { toast("请先选择插件包"); return }
        if (currentProfile.isEmpty()) { toast("没有可用的配置"); return }
        val incompatible = preflight?.incompatible == true
        if (incompatible && !swAllowIncompat.isChecked) {
            toast("该插件与当前 DSH 版本不兼容，请先勾选允许")
            return
        }
        val target = p.targetInside(e)
        confirm(
            "安装 ${p.name}@${p.version}",
            "配置：$currentProfile\n类型：${p.kindLabel}\n目标：$target\n\n安装前会先备份该配置的元数据。",
            "安装"
        ) {
            setBusy(true, "正在安装…")
            tvInstallLog.visibility = View.VISIBLE
            tvInstallLog.text = "开始安装…"
            io.execute {
                val allow = incompatible && swAllowIncompat.isChecked
                val r = Installer.install(
                    e, p, currentProfile, allow, File(opRoot, "current"), scriptsDir, cacheDir,
                    allowDirect = !swSafetyOnly.isChecked
                )
                ui.post {
                    setBusy(false)
                    tvInstallLog.text = r.text()
                    appendLogLine(r.text())
                    toast(r.message)
                    if (r.ok) {
                        prepared = null
                        preflight = null
                        renderPlugin()
                        refreshAll()
                    }
                }
            }
        }
    }

    private fun confirmUninstall(e: DshEnv, profile: String, name: String) {
        val kind = if (e.dshGoPlugins.contains(name)) "dshgo" else "npm"
        confirm("卸载 $name", "会从配置 $profile 移除该插件，并删除 App 管理的本地副本（配置备份保留）。", "卸载") {
            setBusy(true, "正在卸载…")
            io.execute {
                val r = Installer.uninstall(e, profile, name, kind, scriptsDir)
                ui.post {
                    setBusy(false)
                    appendLogLine(r.text())
                    toast(r.message)
                    if (r.ok) refreshAll() else renderAll()
                }
            }
        }
    }

    private fun toggleBundle(profile: String, name: String, enabled: Boolean) {
        val e = env ?: return
        setBusy(true, if (enabled) "正在启用…" else "正在停用…")
        io.execute {
            val r = Installer.setBundleEnabled(e, profile, name, enabled, cacheDir)
            ui.post {
                setBusy(false)
                appendLogLine(r.text())
                toast(r.message)
                refreshAll()
            }
        }
    }

    private fun doUnlock() {
        val e = env ?: run { toast("环境未就绪"); return }
        setBusy(true, "正在解除版本限制…")
        io.execute {
            val r = Installer.unlockApply(e, cacheDir)
            if (r.ok) {
                unlockDesired = true
                unlockVersion = e.dshVersion
                prefs.edit().putBoolean("unlock_desired", true).putString("unlock_version", e.dshVersion).apply()
            }
            ui.post {
                setBusy(false)
                appendLogLine(r.text())
                toast(r.message)
                refreshAll()
            }
        }
    }

    private fun doUnlockRestore() {
        val e = env ?: run { toast("环境未就绪"); return }
        setBusy(true, "正在还原官方判定…")
        io.execute {
            val r = Installer.unlockRestore(e, cacheDir)
            if (r.ok) {
                unlockDesired = false
                prefs.edit().putBoolean("unlock_desired", false).apply()
            }
            ui.post {
                setBusy(false)
                appendLogLine(r.text())
                toast(r.message)
                refreshAll()
            }
        }
    }

    private fun doRestoreLatest() {
        val e = env ?: return
        if (currentProfile.isEmpty()) { toast("没有可用的配置"); return }
        setBusy(true, "正在恢复备份…")
        io.execute {
            val r = Installer.restoreLatest(e, currentProfile)
            ui.post {
                setBusy(false)
                appendLogLine(r.text())
                toast(r.message)
                refreshAll()
            }
        }
    }

    private fun restartDshGo() {
        val e = env ?: return
        setBusy(true, "正在重启 dsh-Go…")
        io.execute {
            val r = Installer.restartDshGo(e)
            ui.post {
                setBusy(false)
                if (!r.ok) toast("重启失败：${r.out.take(200)}") else toast("已发送重启命令")
            }
        }
    }

    // ---------------- 高级渲染 ----------------

    private fun renderAdvanced(e: DshEnv) {
        tvUnlock.text = when {
            unlockActive -> "版本限制：已解除。DSH 启动模块里的兼容性判定已被禁用，重启后所有插件都不再被版本门拦截。"
            unlockDesired && unlockVersion.isNotEmpty() && unlockVersion != e.dshVersion ->
                "版本限制：DSH 已从 $unlockVersion 升级到 ${e.dshVersion}，之前的补丁已失效。请重新点「解除版本限制」。"
            else -> "版本限制：官方判定（不兼容插件会被 DSH 自动停用）。可对单个插件登记精确版本豁免，或在这里全局解除。"
        }
        btnUnlock.isEnabled = !unlockActive && !busy
        btnUnlockRestore.isEnabled = unlockActive && !busy
        tvBackups.text = if (backupList.isEmpty()) {
            "配置备份：暂无"
        } else {
            "配置备份：${backupList.size} 个（最近 ${backupList.first()}）"
        }
        btnRestoreLatest.isEnabled = backupList.isNotEmpty() && !busy
    }

    /** 手动按名字强制清理：插件坏了、已安装列表里又看不到时的救急入口。 */
    private fun doRestoreSafe() {
        val e = env ?: run { toast("环境未就绪，请先刷新"); return }
        setBusy(true, "正在恢复被挪走的文件…")
        io.execute {
            val r = try { Installer.restoreSafeModeFiles(e) } catch (ex: Exception) {
                Installer.RestoreResult(emptyList(), listOf("恢复失败：" + (ex.message ?: "")))
            }
            Logx.i("恢复结果：" + r.text().replace('\n', ' '))
            ui.post {
                setBusy(false)
                AlertDialog.Builder(this)
                    .setTitle(if (r.restored.isEmpty()) "没有需要恢复的文件" else "已恢复 ${r.restored.size} 个文件")
                    .setMessage(r.text())
                    .setPositiveButton("好", null)
                    .show()
                refreshAll()
            }
        }
    }

    private fun showForceCleanDialog() {
        val e = env
        if (e == null) {
            toast("环境尚未就绪，先刷新")
            return
        }
        val input = EditText(this).apply {
            hint = "插件包名，例如 dsh-unrestricted"
            setSingleLine(true)
        }
        val pad = dp(20)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("强制清理插件")
            .setMessage("会从所有配置的依赖和启用列表里删掉这个名字，删除 node_modules 里的条目、本地插件副本和版本豁免。\n不会动其他插件。")
            .setView(wrap)
            .setNegativeButton("取消", null)
            .setPositiveButton("清理") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast("请输入插件名")
                    return@setPositiveButton
                }
                setBusy(true, "正在强制清理 $name…")
                io.execute {
                    val text = Installer.forceClean(e, name, cacheDir)
                    ui.post {
                        setBusy(false)
                        appendLogLine("强制清理 $name：\n$text")
                        AlertDialog.Builder(this)
                            .setTitle("清理完成")
                            .setMessage(text)
                            .setPositiveButton("好", null)
                            .show()
                        refreshAll()
                    }
                }
            }
            .show()
    }

    // ---------------- 杂项 ----------------

    private fun isInternal(name: String): Boolean =
        name.startsWith("@deepseek-ai/dsh") || name.startsWith("dsh-profile-")

    private fun setBusy(value: Boolean, message: String? = null) {
        busy = value
        progressInstall.visibility = if (value) View.VISIBLE else View.GONE
        toolbar.subtitle = if (value && message != null) message else "为 dsh-Go 安装本地插件"
        btnRefresh.isEnabled = !value
        btnPickZip.isEnabled = !value
        btnPickDir.isEnabled = !value
        btnScanDownload.isEnabled = !value
        btnRestart.isEnabled = !value
        btnInstall.isEnabled = false
        if (!value) updateInstallButton()
        btnForceClean.isEnabled = !value
        btnRestoreSafe.isEnabled = !value
        if (value) {
            btnUnlock.isEnabled = false
            btnUnlockRestore.isEnabled = false
            btnRestoreLatest.isEnabled = false
        } else {
            env?.let { renderAdvanced(it) }
        }
    }

    private fun appendLogLine(line: String) {
        logBuffer.append(line).append('\n')
        if (logBuffer.length > 40_000) logBuffer.delete(0, logBuffer.length - 40_000)
        tvLog.text = logBuffer.toString()
    }

    private fun confirm(title: String, message: String, positive: String, onOk: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton(positive) { _, _ -> onOk() }
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun sectionLabel(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(themeColor(android.R.attr.textColorSecondary))
        setPadding(0, dp(10), 0, 0)
    }
}
