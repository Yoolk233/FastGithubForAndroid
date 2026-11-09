# 更新日志

## 1.2.1（2026-11-09）

首个开源版本。

- 生效方式从「改 hosts」改为**本地 DNS 隧道（VpnService）**：只接管 DNS 应答，命中 GitHub 域名时返回实测择优的 IP，其余查询转系统 DNS；TCP 流量完全不经过 App。
- 一键加速 / 停止，状态取自 VPN 服务真实运行状态。
- 节点列表：每个域名的优选 IP、延迟、状态，支持重新测速。
- 下载加速：6 个 GitHub 镜像前缀可切换（`ghfast.top` / `ghproxy.net` / `gh-proxy.com` / `mirror.ghproxy.com` / `gh.llkk.cc` / `ghp.ci`）。
- 剔除 `20.205.243.166` / `20.205.243.167` 这两个国内线路经常 504 的边缘 IP。
- 测速结果落盘缓存，冷启动先显示缓存再后台复测。
- 设置页：加速状态、系统 VPN 设置入口、运行日志（复制/清空）、常见问题、关于。
- 界面为 Jetpack Compose，Material 3 配色，适配深色模式。
