# App 自更新服务器

App 每次打开时读取下面这个文件，发现版本号比自己大，就弹窗提示更新：

```
https://luzi303.bbroot.com:8080/apps/videodl/latest.json
```

安装包和 `latest.json` 放在同一个目录。服务器只提供静态文件，没有后台程序。

## 服务器上的位置

- 机器：Oracle Node2（79.76.111.222），复用已有的 8080 nginx 网关和证书
- 文件目录：`/var/www/apps/<应用名>/`，视频下载用 `videodl`
- 以后别的 App 也要自更新，就在 `/var/www/apps/` 下新建一个目录，nginx 不用再改

## 一次性配置（只做一次）

**2026-10-14 已在 Node2 上做完**，下面留作记录。服务器侧的改动记录在 Oracle cloud 工程的 `memory/app-updates.md`。

```bash
# 1. 建目录，交给 SSH 登录用户，这样发布脚本 scp 时不需要 sudo
sudo mkdir -p /var/www/apps/videodl
sudo chown -R "$(whoami)": /var/www/apps

# 2. 备份 nginx 配置
sudo cp -p /etc/nginx/conf.d/loadbalancer.conf /etc/nginx/conf.d/loadbalancer.conf.bak.2026-10-14

# 3. 编辑 /etc/nginx/conf.d/loadbalancer.conf，
#    在「其余路径一律 404」的 location / 前面插入下面两段
```

```nginx
    # App 自更新：只读静态文件。不留访问日志，和 README 里「不收集数据」一致。
    location ~ ^/apps/[a-z0-9_-]+/latest\.json$ {
        root /var/www;
        access_log off;
        limit_req zone=iplimit burst=20 nodelay;
        add_header Cache-Control "no-cache";
    }
    location /apps/ {
        root /var/www;
        access_log off;
        limit_req zone=iplimit burst=20 nodelay;
        autoindex off;
    }
```

```bash
# 4. 检查语法后生效
sudo nginx -t && sudo systemctl reload nginx

# 5. 公网验证（还没发布时应该是 404）
curl -I https://luzi303.bbroot.com:8080/apps/videodl/latest.json
```

回滚：把 `.bak.2026-10-14` 拷回去，`nginx -t` 后 reload，再删掉 `/var/www/apps`。

## 每次发布新版本

1. 改 `app/build.gradle.kts` 的 `versionCode`（必须变大）和 `versionName`
2. 写 `release-notes/<versionName>.txt`，一行一条，用户会在更新弹窗里看到
3. `./gradlew assembleRelease`
4. `tools/publish-update.sh` 先看生成的 `latest.json`
5. `tools/publish-update.sh --upload` 上传

服务器 SSH 地址用环境变量 `UPDATE_SSH` 指定，默认是 ssh 别名 `Node2`。

## 注意

- **新版本必须用同一个签名密钥（release.jks）打包。** 签名不一致，手机会拒绝覆盖安装。
- **版本号只能往上加。** 想撤回一个坏版本，不能把 `latest.json` 改回旧版本号，手机不会降级。
  正确做法是用旧代码打一个版本号更大的包，再发布一次。
- 第一次通过 App 内更新时，系统会要求允许「视频下载」安装应用，这是安卓的规定，只需允许一次。
- 用户在国内，从法兰克福下载约 63 MB 的安装包可能要等一会儿。
- debug 版（包名带 `.debug`）不检查更新。
