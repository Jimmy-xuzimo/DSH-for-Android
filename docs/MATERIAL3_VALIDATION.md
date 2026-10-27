> 历史记录：当前 v1.0.0 的测试与发布边界见 [验证报告](VALIDATION.md)。

# Material 3 界面重做验证

> 这是 2026-10-26 外壳迁移阶段的记录。之后的设置全面迁移见 [1.0.0-preview.2 验证记录](UNIFIED_SETTINGS_VALIDATION.md)，不应将这里的旧截图和测试数量当作新版结果。

2026-10-26，本地开发版本，尚未签名发布。固定 DSH 提交 `22ddee3792402c3e70ec5f86a63c3e3e895a2f60`，CLI `0.2.1-rc.3`。

## 实现与设计依据

- 应用导航、会话列表弹层、项目表单、引擎管理、权限说明与外观设置使用 Jetpack Compose Material 3 官方组件。已移除自绘玻璃底栏与字号加减控件。
- 使用 `NavigationSuiteScaffold` 和 `currentWindowAdaptiveInfo()` 根据实时窗口宽高选择导航栏或导航轨；键盘出现时隐藏底栏。使用系统安全区域、键盘内边距和可滚动设置列表。
- 字号使用 `Slider`，主题使用 `SingleChoiceSegmentedButtonRow`，权限使用 `Switch`，其他入口使用 `ListItem`、`Button`、`AlertDialog`、`OutlinedTextField`、`ModalBottomSheet`。页面使用 Compose `AnimatedContent` 默认过渡，组件保留内置反馈和动效。
- `MaterialTheme` 统一原生界面颜色与字体，Android 12+ 使用动态配色，字体随系统缩放。编译 SDK 37.2；targetSdk 36、minSdk 29 保持不变。
- 上游复杂对话、编辑器、终端、插件和详细设置仍由一个常驻 WebView 渲染，并非所有 DSH 内部控件都已经替换成 Compose。不能宣称整个产品已完全原生化或全部控件均为 Google 组件。

依据：[Android 移动设计](https://developer.android.google.cn/design/ui/mobile?hl=zh-cn)、[布局与导航](https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns)、[窗口尺寸分类](https://developer.android.google.cn/develop/ui/compose/layouts/adaptive/use-window-size-classes?hl=zh-cn)、[自适应导航组件](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation)。

## 本次实测范围

最新调试 APK 全套 Android 测试 **12 项通过**；JVM 单元测试 **3 项通过**；Node 主机测试 **6 项通过**；Debug Lint 通过，仍有依赖更新和平台兼容性警告。没有关闭检查或使用 lint 基线掩盖错误。最后全套回归耗时 93.777 秒。

设备为 Android Emulator / Android 10 API 29 / x86_64 / 4 KB 页 / WebView 133。窗口覆盖手机竖屏、手机横屏、平板竖屏、平板横屏；这是同一模拟器的窗口与密度调整，不是四台实体设备。API 29 会将过大的显示宽度限制到物理宽度的两倍，测试读取实际尺寸并依据实际窗口断言布局。

`MaterialShellTest` 覆盖：官方字号滑块写入 DSH 真实配置、等待异步保存后重启保留、导航切换保留 WebView、实时窗口改变后官方底栏/导航轨切换、1.6 倍系统字体与软键盘下表单仍可输入。同步 Compose 截图用于避免显示尺寸改变时系统截图抓到拉伸的旧帧。

本次发现并修复两个集成问题：原生设置须等待页面桥完成初始化，未就绪的动作暂存、字号与主题控件暂时禁用；Compose 内 WebView 须明确使用 MATCH_PARENT，避免 WRAP_CONTENT 造成 CSS 百分比高度为零。启动测试现在要求页面实际具有可见高度，不能仅以 DOM 挂载判定成功。

`NativeRuntimeTest` 新增真实 LocalFileSystem 的创建、读取、编辑、权限、无覆盖、并发与取消测试，修复 Android 不允许普通应用创建硬链接导致 agent 写文件失败的问题。原有引擎、身份校验、Node、Bash、PTY、Git、包管理器、Keystore、JS 插件、两类 MCP、导入导出测试继续保留。

模型测试使用本地 Anthropic 协议模拟服务驱动 APK 内真实工具，依次执行 write、read、edit、grep、bash，并检查每次返回的实际内容。模拟服务没有代替工具生成文件或 Shell 输出。真实 DeepSeek 协议另做过一次最小请求，HTTP 200、回复 OK、输入 8 / 输出 1 token；它不等于真实模型在 APK 内完成 agent 全流程。真实密钥未写入仓库、应用或报告。

## 交付边界

新版的 ARM64 真机、Android 12–16、16 KB 页运行、折叠姿态、系统分屏/自由窗口、读屏和多种中文输入法仍待复测。旧版的对应测试不能自动继承。运行中调整模拟器尺寸只验证布局行为，不代表全部硬件适配完成。桌面专属扩展和额外原生程序仍不支持。

上游候选更新工具已检查当前基线归档完整性和补丁锚点；上游主机端 8 项构建关卡、2 项类型检查通过。工具生成候选锁文件，不会自动替换当前基线或发布。

正式签名、本地正式预览版打包与 GitHub 发布继续等待用户确认。
