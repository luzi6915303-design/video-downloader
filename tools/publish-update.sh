#!/usr/bin/env bash
# 把刚打好的 release 包发布到自己的更新服务器（Oracle Node2）。
#
# 用法：
#   1. 改 app/build.gradle.kts 里的 versionCode / versionName
#   2. 写 release-notes/<versionName>.txt，一行一条更新内容
#   3. ./gradlew assembleRelease
#   4. tools/publish-update.sh            # 只生成，不上传，先看一眼
#      tools/publish-update.sh --upload   # 真正上传
#
# 服务器地址用 UPDATE_SSH 指定（ssh 别名或 user@host），默认 Node2。
# 先传安装包、后换 latest.json，所以手机永远不会看到一个指向不存在文件的版本。
set -euo pipefail

cd "$(dirname "$0")/.."

UPDATE_SSH="${UPDATE_SSH:-Node2}"
REMOTE_DIR="${REMOTE_DIR:-/var/www/apps/videodl}"
BUILT=app/build/outputs/apk/release/app-release.apk

code=$(sed -n 's/^ *versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
name=$(sed -n 's/^ *versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
notes="release-notes/$name.txt"
apk_name="VideoDownloader-v$name-arm64.apk"
apk="dist/$apk_name"
out="dist/latest.json"

[ -f "$BUILT" ] || { echo "没找到 $BUILT，先跑 ./gradlew assembleRelease" >&2; exit 1; }
[ -s "$notes" ] || { echo "没找到更新说明 $notes（一行一条）" >&2; exit 1; }

# 防止把旧包当新版本发出去：安装包里的版本号必须和 build.gradle.kts 一致。
sdk=$(sed -n 's/^sdk.dir=//p' local.properties)
aapt2=$(ls -d "$sdk"/build-tools/*/aapt2 | tail -1)
built_code=$("$aapt2" dump badging "$BUILT" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")
if [ "$built_code" != "$code" ]; then
  echo "安装包的 versionCode 是 $built_code，build.gradle.kts 里是 $code。先重新打包。" >&2
  exit 1
fi

mkdir -p dist
cp "$BUILT" "$apk"

python3 - "$apk" "$apk_name" "$code" "$name" "$notes" "$out" <<'EOF'
import hashlib, json, os, sys, datetime
apk, apk_name, code, name, notes, out = sys.argv[1:]
lines = [l.strip().lstrip("-•* ").strip() for l in open(notes, encoding="utf-8")]
release = {
    "versionCode": int(code),
    "versionName": name,
    "apk": apk_name,
    "size": os.path.getsize(apk),
    "sha256": hashlib.sha256(open(apk, "rb").read()).hexdigest(),
    "notes": [l for l in lines if l],
    "publishedAt": datetime.date.today().isoformat(),
}
with open(out, "w", encoding="utf-8") as f:
    json.dump(release, f, ensure_ascii=False, indent=2)
EOF

echo "== 已生成 $out"
cat "$out"
echo

if [ "${1:-}" != "--upload" ]; then
  echo "没有上传。确认无误后加 --upload 再跑一次。"
  exit 0
fi

echo "== 上传到 $UPDATE_SSH:$REMOTE_DIR"
scp "$apk" "$UPDATE_SSH:$REMOTE_DIR/$apk_name"
scp "$out" "$UPDATE_SSH:$REMOTE_DIR/latest.json.tmp"
ssh "$UPDATE_SSH" "mv '$REMOTE_DIR/latest.json.tmp' '$REMOTE_DIR/latest.json'"

url="https://luzi303.bbroot.com:8080/apps/videodl/latest.json"
echo "== 公网核对：$url"
curl -fsS "$url" | python3 -c 'import json,sys; d=json.load(sys.stdin); print("线上版本", d["versionName"], "(", d["versionCode"], ")")'
