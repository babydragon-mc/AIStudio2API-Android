# 真机验证记录

日期：2026-11-04。设备：iQOO Neo10 Pro+，V2463A，Android 15，arm64-v8a。

已通过：

- 上游 v0.3.0 ARM64 发布包 SHA-256 与 GitHub 发布元数据一致；ELF 无动态解释器依赖。
- APK 编译及 APK Signature Scheme v3 校验；USB 安装并打开原生界面。
- 应用沙箱内启动打包核心，18765/18766 端口仅监听 127.0.0.1。
- 管理端 /health 返回 {"status":"ok"}；已排除手机已有 Termux PG Local 占用 8765 导致的假阳性。
- 经用户明确授权，通过 USB 本地转发导入一个测试账号会话；测试账号未加入源码或构建产物。
- 手机上的 SpiderMonkey 完成 WAA Bootstrap，账户同步到 45 个上游模型，公开模型接口返回 72 个模型/别名条目。
- 手机核心调用 gemini-3.8-flash，SSE 正常结束，3 个事件，约 4.23 秒返回 Android standalone proxy works.。
- 上游请求使用手机本地 HTTP 代理 127.0.0.1:7890；电脑只作为 USB 测试客户端。

仍待验证：长时间锁屏、系统回收后的行为、网络切换、Cookie 过期后的续签、其他安卓版本及机型。原生 Google 登录尚未实现，首版采用 Cookie 导入。

发布包通过 AIS2A_RELEASE=1 构建并关闭 debuggable，使用本地保管的长期签名密钥，保持测试安装的签名连续性。管理接口已验证：未登录访问配置返回 401，登录后生成返回 OK。开发包仅供本机调试。
