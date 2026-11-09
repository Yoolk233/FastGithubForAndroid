# FastGithub for Android

FastGithub 的 Android 版：把 GitHub 相关域名的解析结果换成**实测可用的边缘 IP**，让手机上的 github.com / api.github.com / raw.githubusercontent.com 等直接走得通。

桌面版（Windows / Avalonia）：<https://github.com/Yoolk233/FastGithub>

## 和桌面版的区别

| | 桌面版 | 本仓库（Android） |
|---|---|---|
| 生效方式 | 改写系统 hosts | 本地 DNS 隧道（`VpnService`），只接管 DNS 应答 |
| 是否需要 root | 否（但要管理员权限写 hosts） | 否，走系统 VPN 授权 |
| 是否代理流量 | 不代理 | **不代理**：只改解析结果，流量仍直连 |
| 技术栈 | .NET 8 + Avalonia | Kotlin + Jetpack Compose |

原理很简单：加速时启动一个本地 VPN，它只处理 DNS 查询——命中 `Config.domains` 里的域名就返回测速择优后的 IP，其余查询转给系统 DNS。TCP 数据一律不经过 App，所以不会影响其它流量，也不会被当成全局代理。

## 功能

- **一键加速 / 停止**：启动时先测速择优，再拉起 VPN；停止即还原（关掉 VPN）。
- **节点列表**：每个域名的优选 IP、延迟、状态（可用 / 受限 / 失败），支持重新测速。
- **下载加速**：内置多个 GitHub 镜像前缀（`ghfast.top`、`ghproxy.net`、`gh-proxy.com`、`mirror.ghproxy.com`、`gh.llkk.cc`、`ghp.ci`），可切换并直接复制加速后的下载链接。
- **设置页**：加速状态（读的是 VPN 服务真实状态，不是界面自己记的）、系统 VPN 设置入口、运行日志（复制 / 清空）、常见问题、关于。
- **结果缓存**：上次的测速结果会缓存，冷启动时先用缓存结果，再后台复测。
- 已剔除 `20.205.243.166 / 20.205.243.167` 这两个国内线路经常 504 的边缘 IP。

## 版本

- `versionName` = **1.2.1**，`versionCode` = 3
- `minSdk` = 26（Android 8.0），`targetSdk` / `compileSdk` = 35
- 应用包名 `fastgithub.android`

## 自己编译

需要：JDK 17、Android SDK Platform 35、Gradle（工程用 AGP 8.10.1 / Kotlin 2.4.10；仓库未附 gradle wrapper，用本机 Gradle 或 Android Studio 打开均可）。

```bash
# 在工程根目录
gradle :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

用 Android Studio：直接 `Open` 本目录，等 Gradle Sync 完成后 Build → Build APK(s)。

> 注意：`app/build.gradle.kts` 里 release 用的是 **debug 签名**（`signingConfig = signingConfigs.getByName("debug")`），只为方便自己装来测试。要正式分发请换成自己的 keystore，别用这个包直接上架。

`local.properties`（SDK 路径）没有提交，Android Studio 会自动生成，命令行构建时自己写一行：

```properties
sdk.dir=C:\\Users\\<你>\\AppData\\Local\\Android\\Sdk
```

## 目录结构

```
app/src/main/java/fastgithub/android/
├── Config.kt          域名、IP 候选池、镜像预设、路径
├── Engine.kt          测速与择优（并发探测 + 结果缓存）
├── Net.kt             HTTPS 复验（状态码判定 GOOD / WEAK）
├── MappingCache.kt    域名→IP 结果落盘缓存
├── AppState.kt        全局状态（加速中/未加速、日志）
├── MainActivity.kt    入口
├── vpn/
│   ├── DnsVpnService.kt   VpnService：只接管 DNS
│   ├── DnsPacket.kt       DNS 报文解析/构造
│   └── DnsCache.kt        DNS 应答缓存
└── ui/
    ├── AppRoot.kt     底部导航与页面切换
    ├── HomeScreen.kt  首页（状态卡 + 一键加速）
    ├── NodesScreen.kt 节点列表
    ├── MirrorScreen.kt 下载加速
    ├── SettingsScreen.kt 设置
    ├── Components.kt  通用组件（卡片、按钮、提示）
    └── Theme.kt       配色与排版
```

## 免责声明

本项目仅用于学习网络原理与个人网络调试。请遵守你所在地区的法律法规与 GitHub 的服务条款；因使用本工具产生的任何后果由使用者自行承担。

## License

MIT，见 [LICENSE](LICENSE)。
