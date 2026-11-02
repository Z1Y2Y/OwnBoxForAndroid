# OwnBox for Android v26.11.2.1 预览版 (v26.11.2.1-preview)

### 🐛 稳定性与崩溃修复
* **紧急修复小米 HyperOS / MIUI 打开「分应用代理」及应用选择列表时由于线程超限导致的闪退（`OutOfMemoryError`）**：
  - **崩溃根因定位**：针对小米 HyperOS 4.0 / MIUI 深度定制的 `ThemeZipFile` / `ThemeResources` 在遍历系统已安装应用读取应用名称与图标时频繁新建系统线程导致 `pthread_create (1040KB stack) failed: Try again` 内存与系统线程配额耗尽闪退的问题进行底层加固与拦截；
  - **直读非本地化标签**：优先直读 `ApplicationInfo.nonLocalizedLabel`，直接绕过系统主题资源反射与多线程解析开销；
  - **引入高性能线程安全缓存**：在 `PackageCache` 中引入 `ConcurrentHashMap` 标签缓存与安全图标加载机制，避免主线程和协程多次重复触发系统 IPC；
  - **底层 `Throwable` 全面防御拦截**：为应用名称、图标加载及已安装列表扫描全面添加底层的 `Throwable`（含 `OutOfMemoryError`）防御捕获与降级兜底逻辑，彻底杜绝因系统资源耗尽或特定第三方应用异常导致页面闪退。
