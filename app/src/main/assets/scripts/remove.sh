#!/system/bin/sh
# DSH 插件安装器：删除本地插件副本（CLI 卸载由 App 另行执行）。
# 环境变量：ROOTFS DSH_HOME PKG TYPE CLEAN
set -u
: "${ROOTFS:?}" "${DSH_HOME:?}" "${PKG:?}" "${TYPE:?}"
CLEAN="${CLEAN:-1}"
[ "$CLEAN" = "1" ] || { echo "SKIP=1"; exit 0; }
if [ "$TYPE" = "dshgo" ]; then
  rm -rf "$ROOTFS$DSH_HOME/dsh-go/plugins/$PKG"
else
  for d in "$ROOTFS$DSH_HOME/local-plugins/$PKG"-*; do
    [ -e "$d" ] && rm -rf "$d"
  done
fi
echo "DONE=1"
exit 0
