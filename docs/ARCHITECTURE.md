# 技术原理与维护边界

## 三层结构

1. **Android 外壳**：`MainActivity` 持有一个驻留 WebView；`DshMaterialShell` 用 Jetpack Compose Material 3 提供导航、会话抽屉/侧栏、工作区和工具入口；`MaterialSettings` 显示原生设置控件。
2. **DSH 内容与服务**：保留上游对话、流式输出、文件编辑、终端、插件及 MCP 逻辑。原生设置不复制配置服务，而是调用当前控制器中的有限操作。
3. **Android/Bionic 执行环境**：Node、Bash、Git、ripgrep、curl、PTY 和必要原生依赖交叉编译，分别为 ARM64 / x86_64 打包。

APK 是运行时的分发边界；用户模型 API 和 MCP 可以在远端，但不是运行在本项目的中转服务器。

## 原生程序与初始化

`app/src/main/jniLibs/<abi>/libdsh_*.so` 内既有动态库也有按 Android 打包规则命名的可执行 ELF。安装器提取到只读代码目录，运行时从 `ApplicationInfo.nativeLibraryDir` 启动，不在可写的 `filesDir` 中下载执行 ELF。

`RuntimeInstaller` 对资源清单逐文件校验，拒绝未声明、重复或越界条目，限制总解压大小，在 `.pending` 目录完成后切换；校验失败保留可诊断结果。运行时与项目目录分开，不用“重新初始化”覆盖用户工作区。

16 KB 页兼容通过链接参数和 ELF/ZIP 审计准备。静态对齐检查与真实设备运行是两项不同证据。

## 服务生命周期

`EngineService` 是唯一引擎所有者，负责启动、回环端口、健康检查、前台通知、停止和重启。重复请求串行处理，正常重启复用回环来源以保留页面存储，传输令牌轮换。发现端口被占用时失败关闭，不连接到未知本地服务。

前台服务允许用户看见并停止任务，不承诺系统永久保活。关闭应用和停止引擎是不同操作，系统终止或升级后应能恢复可诊断状态。

## 设置与凭据

DSH 设置页面在驻留 WebView 中继续作为控制器工作，但其透明、不可触控、不可读屏。`settings-bridge.js` 只读取当前设置范围内的语义角色、标签、选项和值，为当前活跃控件分配不可预测用途之外的临时 ID。原生请求只接受现存且可用控件的白名单操作：点击、输入、有限键盘排序和关闭；不会接收选择器、路径或任意脚本。

密码一旦被识别，即使切换显示也不进入快照。原生输入不写入恢复 Bundle；保存经过 DSH 凭据服务。Android Keystore 保护用于持久凭据文件的加密材料，签名私钥与这些应用内密钥是不同的东西。

通用 DSH 配置仍以 DSH 的持久层为准。Android 专属的配色、字体选择和动画开关保存为非敏感外观偏好；主题及字号写回 DSH 原设置。字体只有一个全局选项，更新三种内容角色，避免三个互相竞争的选择器。

## 自适应布局

`currentWindowAdaptiveInfo()` + `NavigationSuiteScaffold` 选择官方底部导航/导航轨。宽度与可用高度来自当前窗口，支持旋转和运行时缩放，而不是只看设备型号。低矮窗口和手机保持底部导航；键盘弹出时按 Insets 调整可用区域。

会话内容区域达到宽屏条件后显示 280dp 可收起侧栏，否则使用官方 `ModalNavigationDrawer`。设置在足够宽高时采用分类/详情双栏，小屏为分类→详情；可滚动内容限制最大阅读宽度，不将手机强制为平板宽度。

原生字体和系统 fontScale 保持独立。Noto Sans SC 的字体文件随 APK 提供，WebView 仅在受信任回环来源的固定字体路径请求时读取该资源；不开放文件目录或任意资产路径。

## 配色与动画

Android 12+ 动态颜色使用 `dynamicLightColorScheme` / `dynamicDarkColorScheme`；静态配色由 Google Material Color Utilities 0.3.0 从种子色生成对应角色及表面容器色，结果签入源码。

Compose 使用生命周期感知 Window Recomposer，注入 `MotionDurationScale`，同时读取应用动画偏好和系统动画倍率；不修改系统全局设置。官方 Switch、菜单、抽屉和自定义 `AnimatedContent` / `AnimatedVisibility` 共用该时钟。页面和侧栏采用非线性缓动，不增加无意义的等待。

应用级时钟使用已锁定 Compose 版本的 `WindowRecomposerPolicy` 工厂（需要 `InternalComposeUiApi` opt-in）。它在 `DshApplication` 初始化，不把 Activity 绑到不可替换的 Recomposer。升级 Compose 必须复查该内部 API 的契约，重跑显示密度变化、窗口重建及正式包测试。

密钥的显示/隐藏由原生输入框尾部按钮控制。当前编辑草稿仅保留在内存，滚动和失焦不会清空；控件/编辑器关闭后移除。已保存凭据不从服务中读回用于显示。旧网页控制器的显示按钮不再作为第二个可见入口。

Web 内容通过 CSS 减少动画规则和 Web Animations API 选项进行同步，系统 `prefers-reduced-motion` 始终有效。第三方插件可自带动画，需单独审查，不能声称已控制任意第三方脚本。

## 在线模型目录

提供商编辑器显示实际有效端点，优先顺序为用户草稿、已有配置、适配器默认值。新自定义提供商没有可推断的官方地址，须用户输入。已保存的地址覆盖不会被重置。

点击获取模型将当前地址和临时输入的密钥交给 DSH Host，或由 Host 解析已保存凭据。DeepSeek 模型目录使用该地址同源 `/models`；pi-ai 在显式端点存在时执行网络请求，而不是直接返回内置目录。重定向失败，防止认证头被自动传向其他来源。失败不改写已有模型；选定模型并保存后才写入配置。

目前可查询的格式为 DeepSeek、OpenAI 兼容和 Anthropic 原生目录。其他协议明确显示不支持并允许手动输入；每种协议未来的分页、能力字段和版本变化都需要独立升级验证。模型 ID 出现在目录中不等于 DSH 支持其全部能力。

## 更新

`patch-upstream.mjs` 处理系统集成，`patch-mobile-ui.mjs` 处理导航/设置/主题桥，`patch-product-ui.mjs` 处理提供商初始化与在线目录。补丁锚点变化必须失败。不得直接修改构建出的 bundle 作为长期维护方案。

详见 [上游升级流程](UPSTREAM_UPDATE.md)。完成 Host/Client 构建与类型检查后，仍须重新打包、在设备上验证真实服务与工具，记录签名和校验值。不会因旧版本通过而自动继承新版本运行结论。
