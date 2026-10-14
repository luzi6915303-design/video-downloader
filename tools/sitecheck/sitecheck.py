#!/usr/bin/env python3
"""Scheduled availability check for the sites 视频下载 relies on.

Runs on Oracle Node2 from a systemd timer. For each test link it resolves the
video with yt-dlp (same UA/referer the app uses) and downloads the first 10 KB
of the media, so "OK" means a download would actually start, not just that the
page loaded. Results go to the owner's phone through ntfy.

A site only alerts after FAIL_THRESHOLD consecutive failed runs, and alerts
again when it recovers. A weekly heartbeat proves the checker itself is alive:
silence must never be mistaken for "all fine".
"""
import datetime
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
YTDLP = os.environ.get("VDLCHECK_YTDLP", "/opt/vdlcheck/bin/yt-dlp")
SITES = os.environ.get("VDLCHECK_SITES", os.path.join(HERE, "sites.json"))
CONFIG = os.environ.get("VDLCHECK_CONFIG", "/etc/vdlcheck/config.json")
STATE = os.environ.get("VDLCHECK_STATE", "/var/lib/vdlcheck/state.json")

FAIL_THRESHOLD = 2
ATTEMPTS = 2
RETRY_PAUSE = int(os.environ.get("VDLCHECK_RETRY_PAUSE", "30"))

# Kept identical to the app: XiaohongshuHandler needs a desktop UA, UrlResolver
# expands short links with a mobile one.
DESKTOP_UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
MOBILE_UA = ("Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) "
             "Chrome/131.0.0.0 Mobile Safari/537.36")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def expand(url):
    """Walks a short link's redirect chain hop by hop, like UrlResolver."""
    opener = urllib.request.build_opener(NoRedirect)
    for _ in range(6):
        req = urllib.request.Request(url, headers={"User-Agent": MOBILE_UA})
        try:
            with opener.open(req, timeout=15):
                return url
        except urllib.error.HTTPError as e:
            loc = e.headers.get("Location")
            if 300 <= e.code < 400 and loc:
                url = urllib.parse.urljoin(url, loc)
                continue
            raise RuntimeError(f"短链接跳转返回 HTTP {e.code}")
    return url


def check_ytdlp(site):
    """Lets yt-dlp itself fetch the first 10 KB (its --test mode), so cookies,
    headers and HLS are handled exactly as in a real download."""
    url = expand(site["url"]) if site.get("expand") else site["url"]
    with tempfile.TemporaryDirectory(prefix="vdlcheck-") as tmp:
        cmd = [YTDLP, "--test", "--no-part", "--no-warnings", "--no-playlist",
               "--socket-timeout", "20", "-f", "b/bv*", "-P", tmp, "-o", "probe.%(ext)s",
               "--print", "after_move:%(title).30s\t%(filepath)s"]
        if site.get("desktop_ua"):
            cmd += ["--user-agent", DESKTOP_UA]
        if site.get("referer"):
            cmd += ["--referer", site["referer"]]
        proc = subprocess.run(cmd + [url], capture_output=True, text=True, timeout=180)
        if proc.returncode != 0:
            lines = [l for l in proc.stderr.strip().splitlines() if l.strip()]
            raise RuntimeError((lines[-1] if lines else f"yt-dlp 退出码 {proc.returncode}")[:300])
        title, _, path = proc.stdout.strip().splitlines()[-1].partition("\t")
        size = os.path.getsize(path) if os.path.exists(path) else 0
        if size == 0:
            raise RuntimeError("解析成功，但视频文件读不到数据")
        return f"{title}，读到 {size // 1024} KB"


def check_redirect(site):
    final = expand(site["url"])
    if not re.search(site["expect"], final):
        raise RuntimeError(f"跳转结果不对：{final[:120]}")
    return final[:80]


def run_site(site):
    check = check_ytdlp if site["kind"] == "ytdlp" else check_redirect
    error = None
    for attempt in range(ATTEMPTS):
        if attempt:
            time.sleep(RETRY_PAUSE)
        try:
            return True, check(site)
        except Exception as e:  # any failure counts; the message is what we report
            error = str(e) or type(e).__name__
    return False, error


def load_json(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return default


def push(config, title, message, priority=3, tags=None):
    topic = config.get("ntfy_topic")
    if not topic:
        print(f"[未推送] {title}\n{message}")
        return
    body = json.dumps({
        "topic": topic, "title": title, "message": message,
        "priority": priority, "tags": tags or [],
    }).encode()
    req = urllib.request.Request(config.get("ntfy_server", "https://ntfy.sh"), data=body,
                                 headers={"Content-Type": "application/json"})
    urllib.request.urlopen(req, timeout=20).read()


def update_ytdlp():
    before = subprocess.run([YTDLP, "--version"], capture_output=True, text=True).stdout.strip()
    subprocess.run([YTDLP, "-U"], capture_output=True, text=True, timeout=120)
    after = subprocess.run([YTDLP, "--version"], capture_output=True, text=True).stdout.strip()
    return before, after


def main():
    no_push = "--no-push" in sys.argv
    config = {} if no_push else load_json(CONFIG, {})
    sites = load_json(SITES, {})["sites"]
    state = load_json(STATE, {"sites": {}})
    now = datetime.datetime.now(datetime.timezone.utc)

    if "--no-update" in sys.argv:
        before = after = subprocess.run([YTDLP, "--version"], capture_output=True, text=True).stdout.strip()
    else:
        before, after = update_ytdlp()
    if before != after:
        print(f"yt-dlp {before} -> {after}")

    alerts, recoveries, summary = [], [], []
    for site in sites:
        ok, detail = run_site(site)
        s = state["sites"].setdefault(site["name"], {"fails": 0, "alerted": False})
        print(f"{'OK  ' if ok else 'FAIL'} {site['name']}: {detail}")
        summary.append(f"{'✅' if ok else '❌'} {site['name']}")
        if ok:
            if s.get("alerted"):
                recoveries.append(site["name"])
            s.update(fails=0, alerted=False, last_ok=now.isoformat(timespec="minutes"))
        else:
            s["fails"] = s.get("fails", 0) + 1
            s["last_error"] = detail
            if s["fails"] >= FAIL_THRESHOLD and not s.get("alerted"):
                s["alerted"] = True
                line = f"❌ {site['name']}：{detail}"
                if site.get("hint"):
                    line += f"\n   提示：{site['hint']}"
                alerts.append(line)

    version = after or before
    if alerts:
        push(config, "视频下载：有网站下载失败",
             "连续两次检测失败：\n" + "\n".join(alerts) + f"\n\nyt-dlp {version}",
             priority=4, tags=["warning"])
    if recoveries:
        push(config, "视频下载：已恢复", "恢复正常：" + "、".join(recoveries), tags=["white_check_mark"])

    # Weekly proof of life, on the first run of each Monday (UTC).
    week = now.strftime("%G-W%V")
    if now.weekday() == 0 and state.get("heartbeat") != week:
        state["heartbeat"] = week
        push(config, "视频下载：每周检测报告", "\n".join(summary) + f"\n\nyt-dlp {version}",
             priority=2, tags=["calendar"])

    if not no_push:
        os.makedirs(os.path.dirname(STATE), exist_ok=True)
        with open(STATE + ".tmp", "w", encoding="utf-8") as f:
            json.dump(state, f, ensure_ascii=False, indent=2)
        os.replace(STATE + ".tmp", STATE)


if __name__ == "__main__":
    if "--test-push" in sys.argv:
        push(load_json(CONFIG, {}), "视频下载：测试推送", "能收到这条，说明站点检测的推送通了。", tags=["tada"])
    else:
        main()
