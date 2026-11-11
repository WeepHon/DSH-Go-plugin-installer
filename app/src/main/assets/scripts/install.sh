#!/system/bin/sh
# DSH 插件安装器：把已解压的插件目录复制进运行时，并备份 profile 元数据。
# 环境变量：ROOTFS DSH_HOME PROFILE PKG VER TYPE HERE
set -u
: "${ROOTFS:?}" "${DSH_HOME:?}" "${PROFILE:?}" "${PKG:?}" "${VER:?}" "${TYPE:?}" "${HERE:?}"

TS=$(date +%Y%m%d-%H%M%S)
BK_REL="$DSH_HOME/plugin-installer-backups/$TS/$PROFILE"
BK="$ROOTFS$BK_REL"
mkdir -p "$BK" || exit 11
PROF_DIR="$ROOTFS$DSH_HOME/profiles/$PROFILE"
for f in package.json pnpm-lock.yaml pnpm-workspace.yaml compatibility.json cordis.patch.yml cordis.yml; do
  if [ -f "$PROF_DIR/$f" ]; then cp -f "$PROF_DIR/$f" "$BK/$f" 2>/dev/null; fi
done

if [ "$TYPE" = "dshgo" ]; then
  TARGET_REL="$DSH_HOME/dsh-go/plugins/$PKG"
else
  TARGET_REL="$DSH_HOME/local-plugins/$PKG-$VER"
fi
TARGET="$ROOTFS$TARGET_REL"

if [ "$TYPE" != "dshgo" ]; then
  for old in "$ROOTFS$DSH_HOME/local-plugins/$PKG"-*; do
    [ -e "$old" ] || continue
    [ "$old" = "$TARGET" ] && continue
    mkdir -p "$BK/old-local-plugins"
    mv "$old" "$BK/old-local-plugins/" 2>/dev/null || rm -rf "$old"
  done
fi
if [ -e "$TARGET" ]; then
  mkdir -p "$BK/old-target"
  mv "$TARGET" "$BK/old-target/$(basename "$TARGET")" 2>/dev/null || rm -rf "$TARGET"
fi

mkdir -p "$(dirname "$TARGET")" "$TARGET" || exit 12
cp -a "$HERE/plugin-src/." "$TARGET/" || exit 13
chmod -R u+rwX "$TARGET" 2>/dev/null

echo "TARGET=$TARGET_REL"
echo "BACKUP=$BK_REL"
echo "DONE=1"
exit 0
