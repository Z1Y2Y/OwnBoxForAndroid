# OwnBox for Android v26.11.2.4 预览版 (v26.11.2.4-preview)

### ⚡ 核心出站节点解析与 Hysteria 2 协议深度修复
* **出站代理节点服务器域名解析死锁彻底根治**：
  - **原生注入 `domain_resolver`**：在内核所有代理出站（outbounds）拨号器上显式绑定直连/独立 DNS 解析器（`dns-direct` 或订阅专属 `dns-sub-$gid`），依据 sing-box 1.12+ 官方架构规范，彻底规避出站拨号解析自身服务器域名时误落入 FakeIP 虚拟地址池的问题；
  - **终结 FakeIP 闭环回环与超时**：杜绝节点域名被错误分配 FakeIP（`198.18.x.x`）后导致出站数据包被 Android VPN 重新捕获陷入内部无限自解死锁的致命缺陷，秒级建立直连握手。
* **Hysteria 2 协议握手规范化与标准对齐**：
  - **移除硬编码 ALPN `["h3"]` 兜底**：修复未在节点声明 ALPN 时强制注入 `h3` 导致标准 Hysteria 2 服务端在 TLS 握手时直接报错拒绝连接的问题，恢复与 3.2.0 正式版一致的标准握手行为，由内核与服务端按标准协议协商；
  - **保障机场 HYR 订阅与节点完美兼容**：全面支持各类机场导入的 Hysteria 2 / HYR 节点连接与延迟测速。
* **DNS 直连白名单规则与缓存隔离加固**：
  - 在 `domainListDNSDirectForce` DNS 规则中注入 `disable_cache: true`，杜绝因本地 `cache.db` 历史 FakeIP 映射导致直连规则被缓存击穿的问题。
