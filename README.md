# Car ADB

通过纯用户态 WireGuard，从远程访问 Android 车机本地 ADB。

适用于车机自身网络与热点客户端隔离、但车机上的 App 可以访问本地 ADB 的场景。**无需 root、不使用 VpnService 或系统 TUN，不修改系统路由和 DNS。** 仅用于自己拥有或获授权的设备，建议停车后操作。

## 工作方式

```text
电脑的 adb → 自行管理的访问入口 / WireGuard 网关
           → App 内的 WireGuard + netstack
           → 普通 TCP Socket → 车机本地 adbd
```

隧道侧监听 `车机虚拟 IPv4:15555`，转发到本地 ADB 地址的 `5555` 端口。虚拟 IP 仅存在于 App 进程内；App 只转发 TCP 字节，不解析 ADB，也不绕过设备授权。

## 功能与范围

- 单 Peer、IPv4，支持 CIDR 和逗号分隔的多个 AllowedIPs。
- Android Keystore 加密保存配置，提供手动连接、断开和本地端口检测。
- 前台通知、用户启用的开机连接、网络变化后的恢复与退避重试。
- 原生大字号界面，连接 / 配置 / 设置分区，按窗口宽高适配并跟随系统日夜模式。
- Android 6.0（API 23）以上，ARM64 / ARM32。厂商 ROM 的网络、后台和启动限制需实机确认。

不支持多 Peer、IPv6、系统 VPN、命令钩子或自动开启 adbd。车机需已启用传统 TCP ADB；本项目不实现 Android 配对式无线调试流程。

## 构建

构建脚本面向 **Linux x86_64**，需要 Bash、Go 1.24.4、JDK 21 和 Android SDK/NDK。Gradle 8.11.1 通过仓库 Wrapper 获取，无需全局安装 Gradle。

安装 Android command-line tools 后，准备以下包并完成相应许可确认：

```bash
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools' 'ndk;27.2.12479018'
```

设置自己的工具路径，然后从仓库根目录构建：

```bash
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-21
bash scripts/build.sh
```

产物为 `dist/caradb-debug.apk`。脚本先构建 Go AAR，再构建 Android App；AAR、APK 和缓存不提交到仓库。Go 依赖版本在 `core/go.mod` 中固定，gomobile 工具版本在构建脚本中固定。

可用 `ANDROID_SDK_ROOT`、`ANDROID_NDK_HOME`、`CARADB_TOOLS` 指定已有环境；`scripts/env.sh` 的工具目录仅是回退值，不要求机器采用特定目录结构。AGP 8.9.1、compile/target SDK 35、NDK 27.2.12479018 是当前构建配置。

## 配置和连接

1. 部署标准 WireGuard 网关，允许车机访问其 UDP Endpoint。
2. 分别为车机和网关准备密钥，填写 [车机示例](examples/car.conf.example) 与 [网关示例](examples/gateway.conf.example)。示例使用占位符，不含可用凭据。
3. 在 App 中输入配置和本地 ADB 地址（默认 `127.0.0.1`），保存并连接。
4. 将自己的访问入口转发至车机虚拟 IP 的 `15555`，或从已有 WireGuard 网络直接访问该地址。
5. 在电脑连接入口：

```bash
adb connect <入口地址>:<入口端口>
adb devices
```

若显示 `unauthorized`，按设备原有方式确认 ADB 授权。最近握手、TCP 端口可达和 ADB 已授权是不同状态。

### 配置语义

| 字段 | 行为 |
| --- | --- |
| `Address` | 一个 IPv4 CIDR；保留其中的主机地址，不为 Android 创建子网路由 |
| `AllowedIPs` | 一个或多个 IPv4 CIDR，规范化网段；用于 WireGuard 的目标匹配和内层来源校验 |
| `Table = off` | 可选，仅在 `[Interface]` 下接受，作为无操作兼容项；其他值不支持 |
| `Endpoint` | IPv4 或系统 DNS 可解析的域名，加 UDP 端口 |
| `PersistentKeepalive` | 默认 25 秒；车机在 NAT 后面时建议开启 |

车机收到的内层来源必须属于 AllowedIPs。最简单的入口安排是网关 DNAT + SNAT，或使用以网关隧道地址为源的 TCP 代理；若保留电脑真实源地址，需同时配置 AllowedIPs 和回程路由。

不要照搬给网关的 `wg-quick` 配置添加 `Table = off`：在网关上，它会关闭自动添加路由的行为。未知/重复字段、IPv6 和 `PostUp` 等命令钩子会被明确拒绝。

## 保存与后台行为

- 保存只更新配置，不建立连接；连接会先校验并保存。
- 断开会停止当前隧道与重试，但不关闭“开机自动连接”。
- 开机连接默认关闭。启用后，下次开机并首次解锁会连接，即使上次手动断开过。
- 普通进程恢复和覆盖升级只恢复尚未被手动取消的连接意愿；仅打开界面不会擅自连接。
- 网络切换可能重建隧道，**不会续接旧的 ADB TCP 会话**，电脑可能需要重新 `adb connect`。
- 前台服务不能突破系统强制停止、厂商后台策略或深度休眠。自动启动 App 也不会替系统开启 adbd。

配置采用 Keystore AES-GCM 加密，保存在 App 私有免备份目录。输入页保留安全窗口与自动填充限制；存储错误不会退回明文。不要提交真实配置、私钥、签名口令或包含这些内容的日志/截图。

## Release 签名与升级

Release 不可调试，暂不启用 R8/资源缩减。签名材料必须自行准备并置于仓库之外，构建**不会生成或更换签名身份**。

### 普通单签名

在外部签名目录放置自己的 `release.p12` 和 `release.pass`。默认别名为 `caradb-release`，私钥口令应与 keystore 口令一致；密码文件只含一行口令。目录权限 0700、文件权限 0600。

```bash
CARADB_SIGNING_DIR=/secure/path/signing \
CARADB_SIGNING_MODE=single \
bash scripts/build.sh release
```

产物为 `dist/caradb-release.apk`。可通过 `CARADB_RELEASE_KEY_ALIAS` 调整别名。不要使用他人的签名材料，也不要将口令写入命令行明文或 Git。

### 已有安装的签名轮换

需要完整的 `legacy.keystore`、`legacy.pass`、`release.p12`、`release.pass` 和 `caradb.lineage`；旧别名默认 `androiddebugkey`，可通过 `CARADB_LEGACY_KEY_ALIAS` 调整。

默认 `CARADB_SIGNING_MODE=auto`：发现任一旧签名或 lineage 文件即要求完整轮换材料，否则使用普通单签名。也可显式选择 `rotation`；不会因轮换材料缺失而静默退回单签名。

轮换使用 Android 9+ 的 v3 证明，并为 Android 6–8 保留旧证书的 v1/v2 兼容性。**签名不同的 APK 不能直接覆盖安装。** 保留配置的升级必须保持受支持的签名关系、包名和 Android 用户；不要用卸载或清除数据绕过签名冲突，Keystore 数据可能因此丢失。

更新承载当前 ADB 隧道的 App 会暂时断开连接。请先完整传输 APK，再独立提交安装，恢复后核对版本和连接；不要仅凭 ADB 中断判断安装失败。妥善备份完整签名材料，遗失后不能随意生成替代密钥继续更新。

## 项目结构与维护

- `core/`：WireGuard/netstack、TCP 转发、配置解析和 gomobile 桥接。
- `android/`：原生界面、配置存储、前台服务和系统事件处理。
- `scripts/`：环境、构建和签名入口。
- `examples/`：无真实凭据的配置模板。

完成一次构建、生成 AAR 后，可按改动范围运行现有检查：

```bash
(cd core && go test ./...)
./android/gradlew -p android testReleaseUnitTest lintRelease
```

反馈问题请提供系统版本、CPU 架构、窗口尺寸及脱敏错误信息，不要附真实密钥或完整配置。构建成功不代表所有车机 ROM、开机和省电场景都已验证。

## 许可证

项目原创代码采用 [MIT License](LICENSE)。第三方依赖按各自许可证使用，包括 [wireguard-go](https://git.zx2c4.com/wireguard-go/)、[gVisor](https://github.com/google/gvisor)、[Go mobile](https://github.com/golang/mobile)、[Kotlin](https://github.com/JetBrains/kotlin) 和 [Gradle](https://github.com/gradle/gradle)。
