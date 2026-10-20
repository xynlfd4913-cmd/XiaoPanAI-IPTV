# 小潘AI很稳

面向 Android TV / 小米电视的轻量直播播放器。打开应用后自动播放，遥控器按 OK 显示频道列表，上下选择频道，再按 OK 播放并隐藏列表。

## 功能

- 内置 UTF-8 中文频道列表，按 CCTV、卫视、地方频道等顺序显示。
- 一个频道可配置多条线路，缓冲超时或播放失败时自动切换备用源。
- 兼容 HTTP 明文直播、HTTP/HTTPS 跳转和 Android TV 常用请求头。
- 支持遥控器方向键、频道键和 OK 键。
- 提供脚本从公开 IPTV 列表刷新内置频道。

## 构建

需要 JDK 17 和 Android SDK 35：

```bash
./gradlew assembleDebug
```

Windows：

```powershell
.\gradlew.bat assembleDebug
```

调试 APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

如需签名 release APK，将 `keystore.properties.example` 复制为 `keystore.properties`，填写自己的密钥路径和密码。密钥与 `keystore.properties` 已被 `.gitignore` 排除。

## 刷新频道

准备一个你有权使用的 M3U 地址，并通过环境变量传入。需要 Node.js 18 或更高版本：

```bash
IPTV_SOURCE_URL="https://example.com/your-authorized-list.m3u" node tools/refresh_sources.js
```

Windows PowerShell：

```powershell
$env:IPTV_SOURCE_URL="https://example.com/your-authorized-list.m3u"
node .\tools\refresh_sources.js
```

脚本会下载最新公开 M3U，过滤公告和非直播条目，清理播放器专用后缀，并生成：

- `app/src/main/assets/channels.txt`
- `dist/live.m3u`
- `dist/live.txt`

仓库默认只带一个公开测试流，用于验证播放器能够启动和播放。应用界面只显示频道名称，不显示数据源信息。

## 说明

直播地址来源于公开互联网，仅供个人学习和测试。频道可用性受网络、地区、运营商和上游线路状态影响，本项目不存储或转播视频内容，也不保证任何频道持续可用。项目采用 Apache License 2.0。
