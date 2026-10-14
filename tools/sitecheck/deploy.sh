#!/usr/bin/env bash
# 把站点检测部署到 Oracle Node2。可重复执行：只更新脚本和测试链接，不动推送配置和历史状态。
# 用法：tools/sitecheck/deploy.sh            （服务器 ssh 别名默认 Node2，可用 UPDATE_SSH 改）
set -euo pipefail
cd "$(dirname "$0")"
H="${UPDATE_SSH:-Node2}"

scp -q sitecheck.py sites.json vdlcheck.service vdlcheck.timer "$H:/tmp/"
ssh "$H" 'bash -s' <<'REMOTE'
set -euo pipefail
id vdlcheck >/dev/null 2>&1 || sudo useradd --system --no-create-home --shell /usr/sbin/nologin vdlcheck
sudo install -d -o root -g root -m 755 /opt/vdlcheck
sudo install -d -o vdlcheck -g vdlcheck -m 755 /opt/vdlcheck/bin /var/lib/vdlcheck /var/lib/vdlcheck/cache
sudo install -d -o root -g vdlcheck -m 750 /etc/vdlcheck
sudo install -o root -g root -m 755 /tmp/sitecheck.py /opt/vdlcheck/sitecheck.py
sudo install -o root -g root -m 644 /tmp/sites.json /opt/vdlcheck/sites.json
sudo install -o root -g root -m 644 /tmp/vdlcheck.service /tmp/vdlcheck.timer /etc/systemd/system/
rm -f /tmp/sitecheck.py /tmp/sites.json /tmp/vdlcheck.service /tmp/vdlcheck.timer

if [ ! -x /opt/vdlcheck/bin/yt-dlp ]; then
  sudo -u vdlcheck curl -fsSL -o /opt/vdlcheck/bin/yt-dlp \
    https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux
  sudo chmod 755 /opt/vdlcheck/bin/yt-dlp
fi

# 推送频道名只在第一次部署时生成，之后保持不变，手机订阅一次就行
if [ ! -f /etc/vdlcheck/config.json ]; then
  topic="vdl-zorro-$(openssl rand -hex 6)"
  printf '{\n  "ntfy_server": "https://ntfy.sh",\n  "ntfy_topic": "%s"\n}\n' "$topic" | sudo tee /etc/vdlcheck/config.json >/dev/null
  sudo chown root:vdlcheck /etc/vdlcheck/config.json
  sudo chmod 640 /etc/vdlcheck/config.json
fi

sudo systemctl daemon-reload
sudo systemctl enable --now vdlcheck.timer
echo "推送频道：$(sudo python3 -c 'import json;print(json.load(open("/etc/vdlcheck/config.json"))["ntfy_topic"])')"
systemctl list-timers vdlcheck.timer --no-pager | head -2
REMOTE
