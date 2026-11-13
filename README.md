[![Website](https://img.shields.io/badge/website-AuroraVPN-1f6feb?style=for-the-badge)](https://paulvers-ui2.github.io/auroravpn/)
[![Donate](https://img.shields.io/badge/donate-support%20the%20project-ffc933?style=for-the-badge&labelColor=0a1f44)](https://paulvers-ui2.github.io/auroravpn/#donate)

🌐 **Website, downloads and donations:** https://paulvers-ui2.github.io/auroravpn/



<div align="center">
  <img src=".github/assets/logo.png" alt="AuroraVPN" width="120">

  # AURORAVPN FREE VPN + DNS + FIREWALL

  Encrypted DNS client, firewall, and WireGuard/WARP proxy for Android.

  [![Download APK](https://img.shields.io/github/v/release/paulvers-ui2/rethink-app-masque?label=download&style=for-the-badge)](https://github.com/paulvers-ui2/rethink-app-masque/releases/latest)
  [![Website](https://img.shields.io/badge/website-paulvers--ui.github.io%2Frethink-1f6f5c?style=for-the-badge)](https://paulvers-ui.github.io/rethink/)

  [English](#auroravpn-free-vpn--dns--firewall) | [简体中文](#auroravpn-简体中文)
</div>

---

This is a fork of [Rethink DNS + Firewall + VPN](https://github.com/celzero/rethink-app) with help of arcadesignpro, creatoreprints, diniboy1123, with these changes from the original:


- **WARP over MASQUE**, using [usque](https://github.com/paulvers-ui/usque) instead of Cloudflare's official WireGuard client.
- **Single theme** (Dark Plus) — every other option was removed from the picker.
- **Network status sound alerts**: tunnel down, unhealthy proxy, interface switch — debounced so a burst of related events doesn't fire repeatedly.
- **No Firebase or Google Play Services** in the `fdroid` build variant.
- **Own network backend**: the firestack this fork uses comes from [paulvers-ui/firestack](https://github.com/paulvers-ui/firestack), with 600+ commits on top of the last point where its API still matched this app — upstream's `n2` branch changed that API and broke compatibility here.
- Own sponsor link, and Cloudflare instead of Rethink as the default fallback DNS.

## Download

All builds are published on the [**Releases page**](https://github.com/paulvers-ui2/rethink-app-masque/releases/latest).

| Build | Direct link (always the latest release) | Use it for |
|-------|-----------------------------------------|------------|
| **Signed release** | [AuroraVPN.apk](https://github.com/paulvers-ui2/rethink-app-masque/releases/latest/download/AuroraVPN.apk) | Normal use. Updates an installed AuroraVPN. |
| **Unsigned (debug keystore)** | [AuroraVPN-debug.apk](https://github.com/paulvers-ui2/rethink-app-masque/releases/latest/download/AuroraVPN-debug.apk) | Testing only. |

- Every APK has a matching `.sha256` file on the release page.
- Older versions are on the [Releases page](https://github.com/paulvers-ui2/rethink-app-masque/releases).
- The signed and unsigned builds use different signing keys: Android will not install one over the other, so uninstall first when switching.
- **Requires a 64-bit ARM (arm64-v8a) device.**

## Install

- **[Obtainium](https://github.com/ImranR98/Obtainium)** (recommended): add `https://github.com/paulvers-ui2/rethink-app-masque` as a source for automatic updates. To track only the signed release, set the APK filter regex to `^AuroraVPN-v[0-9.]+\.apk$`.
- **[GitHub Releases](https://github.com/paulvers-ui2/rethink-app-masque/releases)**: manual download (links above).

## License

Same as the original project — see [LICENSE](LICENSE).

---

<div align="center">

# AuroraVPN (简体中文)

面向 Android 的加密 DNS 客户端、防火墙及 WireGuard/WARP 代理。

</div>

本项目 fork 自 [Rethink DNS + Firewall + VPN](https://github.com/celzero/rethink-app)，相较原版做了以下改动：

- **通过 MASQUE 使用 WARP**，采用 [usque](https://github.com/paulvers-ui/usque) 替代 Cloudflare 官方的 WireGuard 客户端。
- **单一主题**（Dark Plus）——选择器中的其他主题选项均已移除。
- **网络状态语音提示**：隧道断开、代理异常、接口切换等——已做防抖处理，避免同类事件短时间内反复提示。
- `fdroid` 构建版本**不包含 Firebase 或 Google Play 服务**。
- **独立的网络后端**：本 fork 使用的 firestack 来自 [paulvers-ui/firestack](https://github.com/paulvers-ui/firestack)，在其 API 与本应用仍兼容的最后一个节点之上又新增了 600 多次提交——上游的 `n2` 分支之后修改了该 API，导致与本项目不再兼容。
- 独立的赞赏链接，并以 Cloudflare 取代 Rethink 作为默认的备用 DNS。

## 下载

所有版本均发布在 [**Releases 页面**](https://github.com/paulvers-ui2/rethink-app-masque/releases/latest)。

| 版本 | 直接下载（始终为最新版本） | 用途 |
|------|---------------------------|------|
| **已签名正式版** | [AuroraVPN.apk](https://github.com/paulvers-ui2/rethink-app-masque/releases/latest/download/AuroraVPN.apk) | 日常使用，可覆盖更新已安装的 AuroraVPN。 |
| **未签名（调试密钥）** | [AuroraVPN-debug.apk](https://github.com/paulvers-ui2/rethink-app-masque/releases/latest/download/AuroraVPN-debug.apk) | 仅用于测试。 |

- 每个 APK 在发布页都有对应的 `.sha256` 校验文件。
- 历史版本见 [Releases 页面](https://github.com/paulvers-ui2/rethink-app-masque/releases)。
- 已签名版与未签名版使用不同的签名密钥，Android 不允许互相覆盖安装，切换时请先卸载。
- **需要 64 位 ARM（arm64-v8a）设备。**

## 安装

- **[Obtainium](https://github.com/ImranR98/Obtainium)**（推荐）：将 `https://github.com/paulvers-ui2/rethink-app-masque` 添加为源即可自动获取更新。若只想跟踪已签名版，请将 APK 过滤正则设为 `^AuroraVPN-v[0-9.]+\.apk$`。
- **[GitHub Releases](https://github.com/paulvers-ui2/rethink-app-masque/releases)**：手动下载（见上方链接）。

## 许可证

与原项目相同——详见 [LICENSE](LICENSE)。
