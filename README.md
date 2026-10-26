# DSH for Android

独立 Android 移植工程，基于 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)。Android 集成由 Jimmy-xuzimo 维护，与 DeepSeek 官方 Android 产品无关。

本次重构目标：Android 10+ 手机和平板、本机运行、无需 Termux/root/Shizuku。当前版本为 `1.0.0-preview.1`；实际完成与未验证项目以 [验证记录](docs/VALIDATION.md) 为准。模型推理通过用户配置的服务商 API 进行，不包含离线大模型。

## 安装与使用

使用发布页中适合设备的 APK。ARM64 用于手机和平板，x86_64 用于模拟器。系统 WebView 需为 Chromium 113 或更新版本；过旧时应用会提示更新。安装后等待首次校验和初始化，在 DSH 设置中配置模型服务商。

旧版 APK 使用调试签名。安装新版前先导出旧数据，再卸载旧版；新版不承诺覆盖安装，也不自动迁移旧会话。

项目文件存放在应用私有工作区，菜单支持创建、HTTPS Git 克隆和 ZIP 导入导出。应用卸载会删除私有数据，卸载前请先导出。默认导出会过滤常见凭据文件，但用户应检查自己放入项目中的其他敏感内容。

命令执行默认关闭。开启前会说明权限边界：命令运行在 Android 应用权限内，可访问应用内多个工作区、使用中的凭据和网络；这不是严格的单工作区隔离。保留上游工具审批，不静默取消安全限制。

开启命令权限后，新会话使用“应用权限 / App access”。已有会话保留原权限设置，可在会话权限菜单切换；应用菜单仍可统一关闭命令执行。

## 从源码构建

运行时交叉编译在 Linux 上进行；Windows 可使用 WSL。需要 Node 24.21.0、pnpm 11.28.5、Python 3、C/C++ 构建工具、Rust 1.85.1（安装两个 Android target），以及 Android NDK `28.2.13676358`。APK 构建需要 JDK 25.0.3、Android SDK 36 和 Gradle Wrapper。

```sh
npm install --global pnpm@11.28.5
rustup toolchain install 1.85.1 --profile minimal
rustup target add --toolchain 1.85.1 aarch64-linux-android x86_64-linux-android
export ANDROID_NDK_HOME=/path/to/android-ndk-r28c
bash scripts/build-runtime.sh
./gradlew :app:testX64DebugUnitTest :app:lintX64Debug :app:assembleX64Debug :app:assembleArm64Debug
```

`sources.lock.json` 固定源码下载地址和 SHA-256；`upstream.lock.json` 固定 DSH 提交。构建顺序为校验源码、应用可追踪补丁、上游编译和检查、构建原生程序、整理实际依赖、打包校验清单、生成 APK。缺少运行时或正式签名配置时构建失败。

正式签名使用 `DSH_KEYSTORE`、`DSH_STORE_PASSWORD`、`DSH_KEY_ALIAS`、`DSH_KEY_PASSWORD`。私钥须独立备份，不能提交仓库。调试签名仅供开发测试，不能作为正式发布包。

## 同步上游

基线为 `22ddee3792402c3e70ec5f86a63c3e3e895a2f60`，DSH `0.2.1-rc.3`。升级须更新源码校验值，重新验证补丁锚点、全部原生模块和数据兼容性。运行时原生程序通过 APK 更新，不向应用可写目录下载 ELF 后执行。

支持兼容的 JavaScript 插件、网络 MCP，以及使用已打包解释器的本地 MCP；插件依赖新的原生程序时需要重新打包。桌面专属电脑控制、桌面浏览器启动和外部程序打开功能不属于安卓支持范围。

## 版权

本仓库的独立移植代码使用 MIT 许可证。DSH、Node、Bash、Git、curl、OpenSSL、zlib、node-pty 和其他依赖保留各自版权与许可证；完整说明见 [第三方声明](THIRD_PARTY_NOTICES.md)。GitHub 提交贡献者与依赖版权归属是不同信息。

