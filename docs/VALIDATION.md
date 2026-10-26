# 验证记录：1.0.0-preview.1

记录日期：2026-10-26。本版为签名预览版。ARM64 已构建、检查并签名，尚无 ARM64 真机运行证据。下列 x86_64 模拟器结果不能代替 ARM64 真机验收。

## 基线与构建

- 上游默认分支在实施开始时锁定为 `22ddee3792402c3e70ec5f86a63c3e3e895a2f60`，CLI `0.2.1-rc.3`；源码下载使用 SHA-256 校验。上游 `build:artifacts` 与 `build:check` 通过。
- 固定 Node 24.21.0、pnpm 11.28.5、Rust 1.85.1、NDK 28.2.13676358、Gradle 9.6.0、AGP 9.4.0、Kotlin 2.4.21、JDK 25.0.3。minSdk 29，targetSdk 36。
- 两种 ABI 的 Node、Bash、Git、curl、ripgrep、PTY、文件锁、原子发布模块、包管理器启动器和 C++ 运行库均已构建。26 个 ELF 的架构与 16 KB PT_LOAD 对齐检查通过。
- Kotlin 编译、2 个归档策略单元测试、Debug/Release Android Lint 通过。Lint 仍有警告，未关闭发布检查。安卓依赖由 `app/gradle.lockfile` 固定，[实际依赖清单](evidence/android-dependencies.txt)。
- 5 个 Node 测试通过：HTTP/WebSocket 认证与来源策略，凭据加密、重载、篡改与错误密钥，以及旧 WebView 兼容函数。
- 缺少签名设置时，发布检查明确以 `Missing release signing setting: DSH_KEYSTORE` 失败，未回退至调试签名。

## 实际运行矩阵

全部为独立 Android Emulator，均为 x86_64，未使用实体手机或平板。[设备记录](evidence/devices.json)

| 环境 | 页大小 | WebView | 证据 |
| --- | --- | --- | --- |
| Android 10 / API 29 | 4 KB | 133，更新后 | [8 项通过](evidence/api29.txt) |
| Android 12 / API 31 | 4 KB | 133，更新后 | [8 项通过](evidence/api31.txt) |
| Android 14 / API 34 | 4 KB | 113，镜像自带 | [运行记录](evidence/api34.txt) |
| Android 15 / API 35 | 4 KB | 镜像自带 | [8 项通过](evidence/api35.txt) |
| Android 16 / API 36 | 4 KB | 133，镜像自带 | [8 项通过](evidence/api36.txt) |
| 额外 Android 17 / SDK 37 测试镜像 | 16 KB | 镜像自带 | [8 项通过](evidence/16kb.txt) |

Android 10/12 镜像的旧 WebView 在测试前使用 Android SDK 镜像内 Google 签名的 WebView/Trichrome 133 更新。WebView 不随本应用分发。应用要求 Chromium 113 或更新版本，过旧时提示更新。

八个设备测试方法包含多个断言，覆盖：完整 DSH/WebView 启动、未认证请求返回 403、Keystore 包装密钥不可导出、APK 安装目录 Bash 执行、可写目录 ELF 被系统拒绝、Node 启动 Bash、中文 PTY 输入和缩放、Git 提交和 ripgrep、npm/pnpm、原子发布不覆盖已有文件、离线安装并加载/清理 JavaScript 插件、本地及 HTTP MCP 的发现和调用、ZIP 往返、凭据过滤、导入冲突与越界拒绝。MCP 使用本地夹具，不代表所有第三方服务的认证兼容性。

服务重启测试另覆盖连续两次请求共享重启所有者、重新就绪与令牌更新。Android 15 上额外验证正常重启保持同一来源与浏览器偏好、清除旧页面；端口被占用时停止连接，显式重试后更换端口恢复。后台重启被系统限制时显示可诊断状态，不承诺永久保活。

## 功能与界面

- 在完整 APK 的上游界面配置模拟 API Key，获得流式 `DSH_ANDROID_MODEL_OK` 回复；重新打开已有会话并重载后回复仍在。加密文件为 `DSH-ANDROID-CREDENTIALS-1:` 格式，未包含模拟密钥明文。
- Android 15 / WebView 124 界面实测：命令关闭时终端明确拒绝；原生菜单说明权限边界后主动开启，终端输入中文、写入文件，再由上游文件预览打开同一文件成功。修复旧 WebView 对内部资源地址的解析差异。[终端记录](evidence/terminal-ui.txt)、[权限说明](screenshots/command-permission.png)、[中文终端](screenshots/terminal-chinese.png)、[文件预览](screenshots/file-preview.png)、[预览记录](evidence/file-preview-ui.txt)。
- 设备 WebView 实测宽度 360、412、844、1600 CSS 像素，覆盖窄屏、横屏和宽平板窗口，无页面横向溢出；调整窗口后已有回复保持可见。
- 实测 1.6 倍系统字体、系统深浅色和软键盘。键盘显示后可用高度从 683 降至 408 CSS 像素，输入区与发送按钮位于键盘上方。
- [手机](screenshots/phone.png)、[平板](screenshots/tablet.png)、[横屏](screenshots/landscape-phone.png)、[大字体](screenshots/phone-large-font.png)、[深色](screenshots/phone-dark.png)、[实际键盘](screenshots/phone-with-ime.png)。
- x86_64 正式签名包经卸载调试版后全新安装，普通应用 UID 下 Node 启动并显示上游界面；正式包没有 DEBUGGABLE 标志。[签名包画面](screenshots/signed-release.png)

## 安全与交付

- 回环监听、每次启动新令牌和 HTTP/WebSocket 来源验证保留。设备测试验证无凭据请求被拒绝；跨源和 WebSocket 情况由策略测试覆盖。
- 安卓消息桥按来源与主框架限制，外部导航交给浏览器；该边界已经代码检查，尚未完成独立渗透测试。
- 命令默认关闭，必须在原生菜单开启；保留工具审批。开启后是 Android 应用权限范围，不是严格的工作区内核隔离。
- ZIP 实测过滤 `.env`、`.npmrc`、SSH 目录和符号链接；重复导入产生不同目录，越界输入被拒绝且原工作区保持不变。系统文件选择器完整交互与权限撤回仍需人工验收。
- APK Signature Scheme v2 和 zipalign 16 KB 检查通过。独立 RSA 3072 位证书 SHA-256：`8b2b8e5c19de9998d99bf416a786994783b0e82f727c99605e196b95cde7b202`。[ARM64 签名](evidence/signature-arm64.txt)、[x86_64 签名](evidence/signature-x64.txt)
- 私钥独立备份，不在源码、APK 或公开附件中。
- 旧仓库 bundle 校验、独立恢复克隆及 git fsck 通过；旧 APK 校验值与 Release 元数据一致。

## 尚待验收

- ARM64 真机、ARM64 16 KB 页设备，以及厂商后台限制。
- 真实模型 API、长流取消/恢复、错误真实密钥和长时间网络中断；目前无可用真实 API 凭据，使用模拟服务。
- 实际折叠姿态、系统分屏/自由窗口、外接键鼠、多种中文输入法组合输入及读屏。显示尺寸调整不能替代这些硬件与系统场景。
- 磁盘耗尽、文件选择器权限撤回、长时间后台及系统强制终止的完整故障矩阵。
- 独立安全测试及全部上游插件、第三方 MCP。额外原生 ELF 和桌面专属扩展不受支持。

GitHub Actions 从干净检出构建依赖、检查、运行模拟器、签名并发布预览版。工作流配置本身不等于运行成功，以发布页关联的 Actions 记录为准。本地结果不能替代 ARM64 真机验收，故本版保持 preview。
