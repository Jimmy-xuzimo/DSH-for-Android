# 上游更新与移动界面维护

引擎、数据格式、工具、插件与 MCP 继续使用锁定的 DSH。安卓应用导航、表单、设置、弹窗和底部表单使用 `app/src/main/java/com/dshmobile/shell/DshMaterialShell.kt` 中的 Jetpack Compose Material 3 官方组件。`patches/ui/AndroidNavigation.tsx` 只负责将会话与面板状态连接到原生界面，`runtime/android/mobile.css` 适配仍由 DSH 渲染的内容，入口补丁由 `scripts/patch-mobile-ui.mjs` 应用。复杂的对话、编辑器、插件页面和终端仍在一个常驻 WebView 中；不能把这些内容称为已全部原生化。

## 准备升级

`1.0.0-preview.2` 的设置由 `MaterialSettings.kt` 完整呈现原生控件；`app/src/main/assets/settings-bridge.js` 是底层 DSH 设置控制器的有限语义适配。它按来源在文档启动时注册，只传当前设置字段与有效控件 ID，密码值不进入快照。升级要复查设置槽位、语义角色、选项弹层、表单输入/保存/校验及首次引导协调器；不能把控制器页面隐藏后导致其停止加载，也不能同时恢复旧主题/字号面板。原生设置页使用“外观与文字”作为唯一主题、字号和字体入口。

在 Linux/WSL 使用固定的 Node 和 pnpm，指定上游完整 SHA：

```sh
python3 scripts/prepare-upstream-update.py --ref FULL_UPSTREAM_SHA --build
```

工具会建立独立候选目录、核对归档所属提交、应用补丁、执行冻结安装与上游构建和类型检查，然后生成候选锁文件及报告。它不会修改正在交付的锁文件，也不会自动发布。检查当前基线时可通过 `--archive runtime/downloads/upstream.tar.gz` 使用已校验的本地归档。

WSL 下建议追加 `--candidate-root /path/on/linux/filesystem`，避免在 Windows 挂载目录解压大量源码文件造成漫长等待。所有替换锚点都必须匹配；候选源码完成补丁检查仍不等于完成编译，正式升级须使用 `--build` 并完成设备回归。

核对候选目录的 `report.json`、上游变更、CLI 的 Node 要求及所有补丁后，再评审并替换两份锁文件。任何锚点失败都应停下来修复适配，不能绕过补丁或移除上游权限校验。

随后从新锁定源码重新构建原生依赖和调试 APK，在 Android 上运行 `./gradlew :app:connectedX64DebugAndroidTest`。其中 `MaterialShellTest` 验证官方字号滑块写入 DSH 的真实设置、重启后保留设置及原生导航不会移除内容；`NativeRuntimeTest` 覆盖真实文件、Shell、PTY、MCP 和安全边界。再用本地模型测试服务 `tests/fixtures/agent-model.mjs` 验证模型调用工具的完整往返。至少复查流式回复/取消、会话恢复、中文 PTY、Git、JS 插件、两种 MCP，以及新旧数据兼容。运行时清单和资产随 APK 更新，不能将新 ELF 下载到应用数据目录执行。

## 移动界面验收

按 [Google 窗口尺寸分类文档](https://developer.android.google.cn/develop/ui/compose/layouts/adaptive/use-window-size-classes?hl=zh-cn) 和 [自适应导航文档](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation)，使用 `currentWindowAdaptiveInfo()` 与 `NavigationSuiteScaffold` 的官方默认策略，根据当前窗口宽高动态选择底部导航栏或导航轨。四个主要入口为对话、工作区、工具、设置；插件面板放在工具页。窗口变化不能只按设备型号或启动时的方向判断。

使用官方 `Slider` 调整会话、代码、终端字号，使用 `Switch`、`DropdownMenu`、`ListItem`、`AlertDialog`、`OutlinedTextField`、`ModalBottomSheet` 及组件内置动效。主题由 `MaterialTheme` 统一提供，Android 12+ 使用系统动态配色。不要恢复自绘玻璃底栏、字号加减按钮或统一强制按钮高度。必须复查中文与英文、深浅色、字体缩放、软键盘、手机和平板横竖屏、运行中缩放和返回键。仍由 WebView 呈现的上游控件须单独检查，不能因外壳采用官方组件就视为全部通过。

WebView 只向受信任回环主框架开放有限的原生动作：本机项目菜单、引擎菜单、命令权限对话框与外观同步。消息不接受任意命令、文件路径或脚本。

## 发布边界

候选检查和模拟器调试不等于正式交付验收。ARM64 真机、真实模型、特定插件和桌面专属功能须分别记录。桌面电脑/浏览器控制等平台能力仍不可用，不能因为换了界面而声称支持。发布版本须在用户授权范围内完成签名、校验与验证记录；本轮用户已授权首次正式版发布。

新增产品适配位于 `scripts/patch-product-ui.mjs`；在线获取必须用可变目录的模拟端点验证实际网络请求，不能只检查按钮存在。统一字体、静态配色与全局动画开关需验证持久状态和原设置没有冲突。
