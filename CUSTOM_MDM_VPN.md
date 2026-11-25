# 自定义 MDM 服务器 VPN 模式

## 工作方式

原有 `vpnService` 仍支持“阻断领创联网”。新增的“转发到自定义 MDM 服务器”模式使用 Android 10 引入的 `VpnService.Builder.setHttpProxy()`，向被纳入 VPN 的领创进程发布本地 HTTP 代理。

领创 6.10 的 HTTP 客户端从系统 `ProxySelector` 读取代理，因此访问：

```text
https://cloud.linspirer.com:883/public-interface.php
```

时会向本地代理发送：

```text
CONNECT cloud.linspirer.com:883 HTTP/1.1
```

`MdmProxyServer` 将这一目标改连到设置页保存的主机和端口；其他 CONNECT 或 HTTP 目标连接原服务器。代理的上游 Socket 会调用 `VpnService.protect()`，避免再次进入 VPN。

自定义模式不向 TUN 添加默认路由。领创的非代理网络流量继续走底层网络；原“阻断模式”仍添加 IPv4/IPv6 默认路由并保持黑洞行为。

## 设置步骤

1. 打开“设置 → 领创 VPN 网络设置”。
2. 把“VPN 工作模式”设为“转发到自定义 MDM 服务器”。
3. 输入私人服务器域名或 IP，以及端口。
4. 开启“启用 VPN 功能”，接受系统 VPN 授权。
5. 查看日志：

```powershell
adb logcat -s LinspirerVpn MdmProxyServer
```

设置发生变化时，正在运行的 VPN 会无缝重建。`autostart` 会继续按照 `vpnmode` 保存值恢复服务。

## TLS 要求

代理转发原始 TLS 字节，不读取或修改业务数据。因此：

- TLS SNI 和证书校验主机仍为 `cloud.linspirer.com`；
- 私人服务器必须在配置端口提供 HTTPS；
- 服务器证书必须被设备信任，并覆盖 `cloud.linspirer.com`；
- 隔离测试设备通常需要将实验 CA 安装为系统 CA。

私人服务器需继续处理 `/public-interface.php`。项目工作区的 `outputs/linspirer-private-server` 已提供对应 AES/JSON-RPC 服务端。

## 兼容性

- 自定义模式：Android 10（API 29）及以上。
- 阻断模式：保持原项目兼容范围。
- `setHttpProxy()` 是 Android 提供给应用的代理建议。目标领创版本使用系统 `ProxySelector`，因此会采用该配置；不读取系统代理的其他 MDM 版本不会被此模式重定向。

## 构建

项目的 Android Gradle Plugin 7.4.2 需使用 JDK 17；JDK 21 会在 `JdkImageTransform` 阶段失败。

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:ANDROID_HOME='C:\Users\zhaox\AppData\Local\Android\Sdk'
cd LinspirerDemo-master
.\gradlew.bat :app:assembleLspdemo_bronyaDebug
```
