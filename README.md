# DSH 插件安装器（Android）

在手机上给 [dsh-Go](https://github.com/ccch1mneyyy/dsh-Go) 安装本地 DSH 插件，不用连电脑、不用命令行。

dsh-Go 是运行在 Android 上的 DeepSeek Harness 客户端，插件装在它的私有目录里（proot 内的 `$DSH_HOME`）。
本工具用 Root 进入该目录，调用 DSH 官方 `dsh plugin` 命令完成安装，并提供状态查看、卸载、版本豁免、启动修复等功能。

> 需要 **Root**（KernelSU / Magisk / APatch 均可）。没有 Root 读不到 dsh-Go 的私有目录，本工具无法工作。

## 功能

- **环境检测**：自动定位 dsh-Go 的 rootfs、proot、`DSH_HOME`、profile 列表和 DSH 版本
- **三种选包方式**：系统文件选择器选 zip、选已解压的文件夹、扫描 `Download` 目录里的 zip
- **兼容性预检**：安装前调用 `evaluatePluginCompatibility()` 判断 `engines.dsh` 是否满足，可选择继续
- **安装**：复制到 `$DSH_HOME/local-plugins/<名>-<版本>`（dsh-go 原生插件放 `dsh-go/plugins/<名>`）→ 调用官方 CLI
- **安装后加固**：把 `node_modules` 里的软链换成真实目录、修正属主（见下）
- **已安装列表（带体检）**：显示版本、类型、占用体积、所在 profile；自动标出「会让 dsh-Go 起不来」的问题（依赖目录不存在、`node_modules` 缺实体、软链、显示启用但文件缺失）
- **一键彻底清理**：官方卸载命令 → 配置依赖与启用项 → `node_modules` 文件（删不掉就改名隔离）→ `cordis.patch.yml` 里对它的引用 → 版本豁免记录 → 本地副本 → 归还属主，最后再扫一遍残留并报告
- **急救停用 / 恢复启用**：只改配置不删文件——把所有第三方插件踢出启用列表、`cordis.patch.yml` 改名成 `*.off-pluginmanager`，让 dsh-Go 立刻能起来；一键可恢复
- **版本豁免**：对不兼容插件写 `compatibility.json`
- **解除/还原版本限制（高级）**：直接给 DSH 的兼容性判定函数打补丁，可逐字节还原
- **启动修复**：清理失效的依赖条目、bundles 残留、`node_modules` 孤儿条目、坏软链
- **强制清理**：按插件名彻底清掉所有 profile 里的残留
- **配置备份与恢复**：每次写入前自动快照
- **恢复安全模式挪走的文件**：dsh-Go 进安全模式会把 `cordis.patch.yml`、`.env` 改名成 `*.off-*`，这里能改回去
- **日志**：内存日志，可复制、保存诊断

## 截图

> 待补充。欢迎 PR 截图到 `docs/`。

## 安装

1. 从 [Releases](../../releases) 下载 APK；
2. 授予 Root（首次操作时系统会弹授权）；
3. 打开 App → 刷新环境 → 选择插件 zip → 选中 profile → 安装。

> APK 用作者自己的 keystore 签名。自行编译的话，签名不同不能覆盖安装。

## 构建

需要 JDK 17、Android SDK（platforms;android-34、build-tools;34.0.0）。

```bash
# 1) 把 gradlew 加执行权限
chmod +x gradlew

# 2) 用 Android Studio 打开，或命令行构建
./gradlew assembleRelease

# 3) 签名
zipalign -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
apksigner sign --ks your.keystore --ks-key-alias your_key --out DSH-Plugin-Installer.apk aligned.apk
```

`local.properties` 由 Android Studio 自动生成（`sdk.dir=...`），不要提交。

如果在 arm64 Linux（比如 Termux + proot、部分云环境）上构建，官方 `aapt2` 可能没有 arm64 版本，
可以在 `~/.gradle/gradle.properties`（不要写进仓库）里指向可用的 aapt2：

```properties
android.aapt2FromMavenOverride=/path/to/aapt2
```

## 实现里踩过的坑（改代码前建议读）

1. **proot 需要 `PROOT_LOADER`**
   dsh-Go 自带的 `libproot.so` 不是直接执行就能用的：要设 `LD_LIBRARY_PATH`（找 `libtalloc.so`）、
   `PROOT_LOADER=<lib>/libproot-loader.so`、`PROOT_L2S_DIR`、`PROOT_TMP_DIR`。
   缺 `PROOT_LOADER` 时 guest 里所有动态链接程序 `execve` 都返回 ENOENT，报错看起来像“程序不存在”。

2. **`node_modules` 里的软链会让引擎崩**
   `dsh plugin add <本地目录>` 会产生 `link:` 依赖，node_modules 里是一个软链。
   dsh 0.2.2-rc.1 启动时对模板块里的每个依赖做 `realpath()`（`linkedProfileRoots()`，没有 try/catch），
   proot 下这个 realpath 会 EACCES，直接把整个引擎带崩：
   `Error: EACCES: permission denied, realpath '.../node_modules/<name>'`。
   所以安装完成后 `Installer.materializeEntry()` 会把软链换成真实目录副本（`package.json` 里的声明不动）。

3. **属主要还给 dsh-Go**
   以 root 跑 CLI 会写出 root 属主的文件，引擎以 App 自己的 uid 跑，读不了就 EACCES。
   安装、卸载、改配置之后都要 `normalizeDshHome()`：`chown -R <appUid> "$DSH_HOME"` + `chmod -R a+rX`。

4. **KernelSU 需要 mount-master**
   普通 `su -c` 处在受限的 mount namespace 里，看不到 dsh-Go 的私有目录（表现为探测失败）。
   `su -M -c` 可以。安装器探测成功后会记住这个模式，后续命令沿用。

5. **安全模式会改文件名**
   引擎连续崩溃后 dsh-Go 会把 `$DSH_HOME/cordis.patch.yml`、`$DSH_HOME/.env`、`/root/.env`
   改名成 `*.off-<时间>` 挪走，退出安全模式后不会自动还原，全局补丁（比如 MCP 注册）就这样“消失”。
   App 里「恢复安全模式挪走的补丁」就是把它们改回原名。

## 权限与风险

- 需要 Root 才能读写 dsh-Go 私有目录；所有文件操作都在 `$DSH_HOME` 与运行时目录内。
- 任何写入前都会把 profile 元数据备份到 `$DSH_HOME/plugin-installer-backups/<时间>/`。
- 仍建议：安装新插件前确认它能接受当前 DSH 版本；出问题时先用 App 里的「修复」或「强制清理」。
- 本工具不联网上传任何数据；「引擎运行中」的检测只是本地 TCP 探测 `127.0.0.1:3080`。

## 兼容性

- 测试环境：Xiaomi 2509FPN0BC / Android 16 (SDK 36) / arm64-v8a / KernelSU，
  dsh-Go 1.0.14，DSH 0.2.2-rc.1，Node 24.22.1。
- 其他机型、其他 Root 方案、其他 DSH 版本未验证，欢迎反馈 issue。

## 协议

MIT，见 [LICENSE](LICENSE)。仅供学习与自用，使用风险自负。
