# 视频下载

一个 Android 视频下载工具。粘贴链接就能下载，视频直接进系统相册，**没有广告、没有内购、不收集任何数据**。

内置 yt-dlp（Python 3.12 运行时 + ffmpeg），不依赖任何第三方解析服务器。

**下载安装包 → [Releases](../../releases/latest)**（Android 8.0+ / arm64）

## 支持的网站

| 网站 | 是否需要登录 |
|---|---|
| YouTube | 会员／年龄限制内容需登录 |
| X（Twitter） | 受保护账号需登录 |
| 小红书 | 公开笔记无需登录 |
| TikTok | 无需登录 |
| 抖音 | 无需登录，反爬 Cookie 自动获取 |
| 哔哩哔哩 | 1080P 及以上需登录 |
| 今日头条 | 无需登录 |
| Instagram | 建议登录，否则常被拦截 |

其他网站也可以试——识别不出来时会交给 yt-dlp 的通用解析，成功率看站点。

需要登录的站点通过 App 内置的 WebView 登录，Cookie 只保存在本机的应用私有目录里，不会离开设备。

## 运行环境

- Android 8.0（API 26）及以上
- arm64-v8a（为控制体积只打包了这一个 ABI）
- 安装包约 63 MB —— 内置了 Python 运行时和 ffmpeg，所以偏大
- 首次安装需要允许「安装未知来源应用」

视频保存在 `Movies/VideoDownloader`，音频在 `Music/VideoDownloader`，走 MediaStore 写入，卸载 App 也不会删除。

## 从源码构建

需要 JDK 17 和 Android SDK（platform 35、build-tools 35.0.0、platform-tools）。

创建 `local.properties` 指向你的 SDK：

```properties
sdk.dir=/path/to/Android/sdk
```

然后：

```bash
./gradlew :app:assembleRelease
```

没有配置签名时，release 构建会退回用 debug 签名，装到手机上一样能跑。要用自己的签名，在项目根目录放一个 `keystore.properties`：

```properties
storeFile=release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

这两个文件都在 `.gitignore` 里，不会被提交。

## 代码结构

加一个新站点是刻意做得很轻的：

- `core/SiteHandler.kt` —— 站点接口：URL 匹配、画质选择、额外 yt-dlp 参数、登录地址、Cookie 域名。
- `core/handlers/*Handler.kt` —— 每个站点一个 object。
- `core/SiteRegistry.kt` —— **唯一需要注册的地方**。注册后，URL 匹配、设置页的登录列表、「支持的网站」表格、主页空状态、剪贴板识别全都自动跟上。
- `engine/YtDlpEngine.kt` —— 封装内置的 yt-dlp。
- `cookie/` —— WebView 登录、Cookie 导出成 yt-dlp 要的 Netscape 格式、以及反爬 Cookie 的自动获取。
- `download/` —— 下载队列（最多 2 个并行）和前台服务通知。

## 许可证

本项目使用 **GPL-3.0**。

这不是可选的：它链接了 [youtubedl-android](https://github.com/JunkFood02/youtubedl-android)（GPL-3.0），因此整个程序在分发时必须以 GPL-3.0 授权。完整条款见 [LICENSE](LICENSE)。

内置的 yt-dlp 本身是公有领域（Unlicense），见 [yt-dlp/yt-dlp](https://github.com/yt-dlp/yt-dlp)。

## 说明

这个工具只负责把视频文件取下来，请只用它下载你有权下载的内容。是否遵守目标网站的服务条款、以及下载后如何使用这些内容，由使用者自己负责。
