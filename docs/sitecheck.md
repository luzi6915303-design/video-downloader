# 站点可用性检测

Oracle Node2 每天两次（UTC 06:00 / 18:00，北京时间 14:00 / 02:00）检测各网站还能不能下载，出问题时推送到手机。

## 测什么

测试链接在 `tools/sitecheck/sites.json`（不进 git，格式见 `sites.example.json`）。每条链接用 yt-dlp 真实下载开头 10 KB，UA 和 Referer 跟 App 一致。

| 网站 | 测法 |
|---|---|
| 小红书 | 短链接跳转 + 下载开头 |
| TikTok、头条、X | 下载开头 |
| 抖音 | 只测短链接跳转。App 下载抖音走内置浏览器，服务器测不了 |
| YouTube、B站 | 不测。它们拦截机房 IP，会天天误报，以后放到 NAS 上测 |
| Instagram | 不测。要登录，放服务器上容易封号 |

## 什么时候推送

- 同一个网站**连续两次**失败才报警（每次检测内部还会重试一次），避免网络抖动误报
- 报警后恢复正常，再推一条"已恢复"
- 每周一第一次检测发一份周报。**周一没收到周报，说明检测本身坏了**
- 每次检测前先把 yt-dlp 更新到最新版。所以报警意味着"最新版 yt-dlp 也不行"，要改 App 代码

推送走 ntfy.sh，频道名在服务器 `/etc/vdlcheck/config.json`。

## 改测试链接 / 改脚本

改 `sites.json` 或 `sitecheck.py` 后运行 `tools/sitecheck/deploy.sh`。脚本可重复执行，不会动推送配置和历史状态。

本地试跑：`VDLCHECK_YTDLP=$(which yt-dlp) python3 -I tools/sitecheck/sitecheck.py --no-push --no-update`

## 服务器上的常用命令

```bash
sudo systemctl start vdlcheck.service              # 立刻跑一次
sudo journalctl -u vdlcheck.service -n 30 -o cat   # 看结果
systemctl list-timers vdlcheck.timer               # 下次什么时候跑
sudo -u vdlcheck python3 /opt/vdlcheck/sitecheck.py --test-push   # 发一条测试推送
```
