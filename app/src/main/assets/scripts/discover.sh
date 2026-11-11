#!/system/bin/sh
# DSH 插件安装器：环境探测（以 root 运行）
# 输出 KEY=VALUE 行，以及 @@FILE <rootfs 内路径> / @@END 包裹的文件内容。
set -u

MARKER="etc/dsh-app/runtime.json"
ROOTFS=""
STRATEGY=""

pick() {
  [ -n "$1" ] || return 1
  if [ -f "$1/$MARKER" ]; then ROOTFS="$1"; return 0; fi
  return 1
}

# 0) 测试用覆盖
if pick "${DSH_PI_ROOTFS:-}"; then STRATEGY=override; fi

# 1) 已知包名直接猜（这个 App 的包名是固定的，换版本也不会变）
if [ -z "$ROOTFS" ]; then
  for pkg in io.github.ccch1mneyyy.dsh_app io.github.ccch1mneyyy.dshapp; do
    if pick "/data/user/0/$pkg/files/runtime/rootfs"; then STRATEGY=known_pkg; break; fi
    if pick "/data/data/$pkg/files/runtime/rootfs"; then STRATEGY=known_pkg; break; fi
  done
fi

# 2) 安装包扫描：pm 里带 dsh 的包
if [ -z "$ROOTFS" ]; then
  for pkg in $(pm list packages 2>/dev/null | sed 's/^package://' | grep -i 'dsh'); do
    if pick "/data/user/0/$pkg/files/runtime/rootfs"; then STRATEGY=pm_scan; break; fi
    if pick "/data/data/$pkg/files/runtime/rootfs"; then STRATEGY=pm_scan; break; fi
  done
fi

# 3) 从正在运行的 proot 进程命令行里取 --rootfs=（dsh-Go 启动时会带这个参数）
if [ -z "$ROOTFS" ]; then
  for f in /proc/[0-9]*/cmdline; do
    [ -r "$f" ] || continue
    r=$(grep -aoE -- '--rootfs=[^[:cntrl:]]+' "$f" 2>/dev/null | head -n 1 | sed 's/^--rootfs=//')
    [ -n "$r" ] || continue
    if [ -f "$r/$MARKER" ]; then ROOTFS="$r"; STRATEGY=proc_cmdline; break; fi
  done
fi

# 4) 全局兜底搜索
if [ -z "$ROOTFS" ]; then
  hit=$(find /data/user /data/data /data/app -maxdepth 7 -type f -name runtime.json \
        -path '*dsh-app*' 2>/dev/null | head -n 1)
  if [ -n "$hit" ]; then
    if pick "$(dirname "$(dirname "$hit")")"; then STRATEGY=find; fi
  fi
fi

if [ -z "$ROOTFS" ]; then
  echo "ERROR=no_rootfs"
  echo "@@DIAG"
  echo "whoami=$(id 2>&1 | head -n 1)"
  echo "selinux=$(cat /proc/self/attr/current 2>/dev/null)"
  echo "sdk=$(getprop ro.build.version.sdk 2>/dev/null)"
  echo "su_bin=$(ls -l /system/bin/su 2>&1 | head -n 1)"
  echo "data_user0=$(ls -ld /data/user/0 2>&1 | head -n 1)"
  echo "data_data=$(ls -ld /data/data 2>&1 | head -n 1)"
  echo "data_data_count=$(ls /data/data 2>/dev/null | wc -l)"
  echo "data_data_head=$(ls /data/data 2>/dev/null | head -n 8 | tr '\n' ' ')"
  echo "dshgo_dir=$(ls -ld /data/data/io.github.ccch1mneyyy.dsh_app 2>&1 | head -n 1)"
  echo "dshgo_files=$(ls /data/data/io.github.ccch1mneyyy.dsh_app/files 2>&1 | head -n 8 | tr '\n' ' ')"
  echo "dshgo_runtime=$(ls /data/data/io.github.ccch1mneyyy.dsh_app/files/runtime 2>&1 | head -n 8 | tr '\n' ' ')"
  echo "pm_dsh=$(pm list packages 2>&1 | grep -i dsh | head -n 8 | tr '\n' ' ')"
  echo "proc_hits=$(grep -laF -- '--rootfs=' /proc/[0-9]*/cmdline 2>/dev/null | head -n 4 | tr '\n' ' ')"
  echo "proc_dshgo=$(ps -A 2>/dev/null | grep -i dsh | head -n 6 | tr '\n' ' ')"
  echo "find_hits=$(find /data/user /data/data -maxdepth 7 -type d -path '*files/runtime/rootfs' 2>/dev/null | head -n 5 | tr '\n' ' ')"
  echo "find_marker=$(find /data/user /data/data -maxdepth 9 -type f -name runtime.json -path '*dsh-app*' 2>/dev/null | head -n 5 | tr '\n' ' ')"
  echo "nsenter=$(command -v nsenter 2>/dev/null || ls /system/bin/nsenter 2>/dev/null || echo none)"
  echo "@@END"
  exit 1
fi

PKG=$(printf '%s' "$ROOTFS" | sed 's#^/data/user/[0-9]*/##; s#^/data/data/##; s#/files/runtime/rootfs$##')
USER_ID=$(printf '%s' "$ROOTFS" | sed -n 's#^/data/user/\([0-9]*\)/.*#\1#p')
[ -n "$USER_ID" ] || USER_ID=0
APK=$(pm path --user "$USER_ID" "$PKG" 2>/dev/null | head -n 1 | sed 's/^package://')
[ -n "$APK" ] || APK=$(pm path "$PKG" 2>/dev/null | head -n 1 | sed 's/^package://')
PROOT=""
if [ -n "$APK" ]; then
  LIBDIR=$(dirname "$APK")/lib/arm64
  if [ -f "$LIBDIR/libproot.so" ]; then PROOT="$LIBDIR/libproot.so"; fi
fi
if [ -z "$PROOT" ]; then
  PROOT=$(find /data/app -path '*lib/arm64/libproot.so' 2>/dev/null | head -n 1)
fi

DSH_HOME=/root/.dsh
if [ ! -d "$ROOTFS$DSH_HOME" ]; then
  DSH_HOME=$(ls -d "$ROOTFS"/root/.dsh "$ROOTFS"/home/*/.dsh 2>/dev/null | head -n 1)
  DSH_HOME=${DSH_HOME#"$ROOTFS"}
  [ -n "$DSH_HOME" ] || DSH_HOME=/root/.dsh
fi

echo "UID=$(id -u 2>/dev/null)"
APP_UID=$(stat -c %u "$ROOTFS" 2>/dev/null || echo 0)
APP_GID=$(stat -c %g "$ROOTFS" 2>/dev/null || echo 0)
echo "APP_UID=$APP_UID"
echo "APP_GID=$APP_GID"
echo "ROOTFS=$ROOTFS"
echo "PKG=$PKG"
echo "USER_ID=$USER_ID"
echo "PROOT=$PROOT"
echo "DSH_HOME=$DSH_HOME"
echo "SDCARD=$([ -d /sdcard ] && echo 1 || echo 0)"
[ -f "$ROOTFS/etc/dsh-app/runtime.json" ] && echo "RUNTIME_JSON=1"

dump() {
  echo "@@FILE $1"
  cat "$ROOTFS$1" 2>/dev/null
  echo "@@END"
}

dump /etc/dsh-app/runtime.json

for pdir in "$ROOTFS$DSH_HOME"/profiles/*; do
  [ -d "$pdir" ] || continue
  name=$(basename "$pdir")
  echo "@@PROFILE $name"
  for f in package.json compatibility.json; do
    if [ -f "$pdir/$f" ]; then
      echo "@@FILE $DSH_HOME/profiles/$name/$f"
      cat "$pdir/$f" 2>/dev/null
      echo "@@END"
    fi
  done
done

# dsh-go 原生插件目录里的清单（只列目录名，读安装在 App 侧做）
if [ -d "$ROOTFS$DSH_HOME/dsh-go/plugins" ]; then
  for d in "$ROOTFS$DSH_HOME/dsh-go/plugins"/*; do
    [ -d "$d" ] && echo "DSHGO_PLUGIN=$(basename "$d")"
  done
fi

echo "STRATEGY=$STRATEGY"
echo "OK=1"
