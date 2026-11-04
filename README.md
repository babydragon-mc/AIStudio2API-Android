# AIS2A Android — experimental standalone port

Community Android packaging of [Mag1cFall/AIStudio2API](https://github.com/Mag1cFall/AIStudio2API), pinned to v0.3.0. This project is independent of the upstream maintainers.

## 当前状态

实验性原型。APK 内包含上游静态 Linux ARM64 核心，通过安卓前台服务启动，管理页面和 API 仅监听手机本机。已在 iQOO Neo10 Pro+（Android 15）实测应用内核心、账号导入、模型列表及 SSE 生成；详细记录见 VALIDATION.md。其他机型与长时间后台运行仍待验证。

- 安卓 10 及以上；默认 ARM64（arm64-v8a）。
- 本机核心启动/停止、常驻通知停止入口、管理端健康检查、代理地址设置、本地 API 密钥和独立管理密码。管理用户名为 admin，密码在原生 App 中复制。
- 管理页：http://127.0.0.1:18765/；OpenAI 兼容地址：http://127.0.0.1:18765/v1。
- 首版使用管理页中的“粘贴 Cookie”添加账号。桌面 Chrome 导入和 Camoufox 登录未适配 Android。不能假定普通 Google 登录令牌可替代网页会话。
- 网络需能访问 Google。静态 Linux 核心在 Android 上的 DNS 行为待验证，建议配置手机上已有的 HTTP/SOCKS5 出站代理。
- 管理端的核心在线更新暂不适用于安卓；核心升级随 APK 发布。不要在管理端直接更新 Linux 二进制。
- 配置与会话存储在应用私有目录；禁止备份，卸载会删除。项目不自带账号、Cookie、API key 或私人日志。

## 构建

需要 Python 3、JDK 17+ 和官方 Android SDK：`platforms;android-36`、`build-tools;36.0.0`。

```sh
export ANDROID_HOME=/path/to/android-sdk
python build.py
```

Windows 使用 PowerShell 设置 `$env:ANDROID_HOME` 后运行 Python。构建脚本校验上游发布包的固定 SHA-256，并检查 ELF 架构和静态链接。可设置 `AIS2A_ABIS=arm64-v8a,x86_64` 生成带模拟器核心的通用包。

构建产物在 `dist/`，默认是开发签名包。GitHub Actions 默认仅上传开发包构建产物，不自动公开发布、不使用用户账号测试。

## 发布签名

正式发布前由仓库所有者保管长期签名密钥。设置 `AIS2A_RELEASE=1`、`AIS2A_KEYSTORE`、`AIS2A_KEY_ALIAS`、`AIS2A_STORE_PASSWORD`、`AIS2A_KEY_PASSWORD` 后构建。密码通过环境传入签名工具。勿将密钥和密码加入 Git。

## 真机验证顺序

1. 安装开发 APK，点击“启动本机核心”，允许显示运行通知。
2. “检查本机管理端”返回已验证 HTTP 200。复制管理密码，打开管理页，以 admin 登录。
3. 配置手机出站代理，重启核心；在管理页导入测试账号 Cookie。
4. 启动生成服务，检查模型列表和一轮流式对话。
5. 使用另一个本机客户端连接 API，测试锁屏、网络切换、停止和重新启动。

USB 调试与 USB 网络共享通常可共存；测试无需关闭网络共享或修改手机网络。未授权的调试设备由用户在手机上确认。

## 开源声明

上游 AIStudio2API 的 MIT 许可见 `LICENSE.upstream`。原生核心的第三方依赖保留各自许可证，完整声明见 THIRD_PARTY_NOTICES.md；prepare_notices.py 生成的许可材料和对应源码包随 APK 一同分发。新增 Android 包装代码采用 MIT，见 `LICENSE`。
