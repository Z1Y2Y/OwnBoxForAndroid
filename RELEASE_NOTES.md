# OwnBox for Android v26.11.2.2 预览版 (v26.11.2.2-preview)

### 🚀 全手动协议深度适配与修复
* **右上角「手动添加」菜单全协议（21 种）适配加固**：
  - **VLESS 节点保存修复**：彻底修复从手动菜单添加 VLESS 节点时保存被强制重置覆写为 VMess（`alterId=0`）导致节点失效的致命缺陷；
  - **移除静默闪退与无声关闭**：全面替换 `ProfileSettingsActivity` 中捕获异常直接 `finish()` 的静默行为，改为详细 Toast 错误提示，杜绝“点击无反应或一闪而过”；
  - **SOCKS5 协议版本错乱修复**：修复 `SocksSettingsActivity` 错误读取 Hysteria 的 `protocolVersion` 导致密码框显隐与版本控制错乱的问题；
  - **NaïveProxy 空指针闪退修复**：修复 `serverHeaders` 为空时调用 `.replace()` 引发的空指针 NPE 崩溃；
  - **Trojan-Go 加密选项 NPE 修复**：修复未选择加密时 `encryption.startsWith()` 导致的空指针崩溃与 ClassCastException；
  - **TUIC 默认参数合法性修复**：修复 `tuic_preferences.xml` 中拥塞控制算法与中继模式默认填为 `"https"` 导致 sing-box 内核报错 `unknown congestion controller` 的问题，恢复为标准 `"native"` 和 `"cubic"`；
  - **全实体初始化防御补强**：为 WireGuard、ShadowTLS、NaïveProxy、SOCKS 等配置页面补齐 `applyDefaultValues()`，确保新建时默认值完整；
  - **自定义配置与链式代理合法性预检**：增强配置保存时的非空和结构校验，避免空节点导致内核崩溃。

### ✈️ 机场订阅与节点可用性根治
* **彻底根治测试沙箱中的 DNS 死锁问题**：
  - 修复节点延迟/连通性测试时，`forTest` 模式由于 `dns.final_ = "dns-remote"` 导致域名节点解析自身时尝试通过未连接的代理自身造成循环死锁超时的严重 Bug；
  - 在测试规则中强制注入出站代理节点服务器域名直连 DNS 解析规则，确保握手建连前域名解析零延迟秒级响应。
* **Shadowsocks 加密方式大小写与别名兼容归一化**：
  - 接入 `normalizeShadowsocksCipher()`，自动将机场订阅中常见的 `AEAD_AES_256_GCM`、`AES-256-GCM`、`chacha20-poly1305`、`2022-BLAKE3-AES-256-GCM` 自动归一化为 sing-box 严格要求的标准小写名称，解决 sing-box 报错 `unknown method` 的问题。
* **Clash 订阅格式兼容拓展**：
  - 拓展协议别名识别：支持 `"shadowsocks"`、`"hy2"`、`"socks"`、`"shadowsocksr"` 等别名；
  - 增强 `ws-opts` 根级 `host` 与 `headers.Host` 兼容读取，杜绝 CDN 阻断 403/521；
  - 增强 `grpc-opts` 的 `service-name` 与 `grpc-service-name` 字段识别；
  - 补齐 Shadowsocks `udp-over-tcp` / `uot` 字段解析。
* **UI 诊断与友好报错信息优化**：
  - 重构 `Protocols.genFriendlyMsg()`，遇到错误时直观展示具体原因（如「DNS 解析失败」、「TLS 握手失败」、「加密方式不支持」、「缺少插件: xxx」等），不再使用单一红字「不可用」掩盖真实异常。
