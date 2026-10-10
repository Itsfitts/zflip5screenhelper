# 开发、构建与验证

本文件是当前构建、签名、迁移与本地验证的入口。项目根目录执行文中的命令；版本和依赖以 Gradle 配置为准，历史验收文档中的旧环境、旧包名和旧签名说明仅供追溯。

单人维护的提交、推送、CI 和正式发布入口见 [工程流程](engineering-workflow.md)，可移植检查命令见 [工程工具](../tools/project/README.md)。

本轮工程改造的实测范围和未验证项见 [工程验收](engineering-validation.md)；这份记录不替代后续提交和设备验收。

## 文档职责

| 文件或目录 | 应写内容 | 不应承担的内容 |
| --- | --- | --- |
| `README.md` | 软件是什么、解决什么需求、面向谁、有哪些功能，以及影响使用的权限和兼容性边界 | 构建命令、类名与算法、开发协作规则、验收流水和历史问题 |
| `AGENTS.md` | 稳定项目结构、模块责任、共享数据与样式的唯一来源、安全约束、交付约定和完成标准 | 长篇功能规格、调试步骤、实现参数、测试结果与任务日志 |
| `docs/` | 开发与使用步骤、模块详细契约、设计方案、配置协议和验证方法 | 临时日志与机器私有配置 |
| `dist/` | 最终交付入口、发布说明、原型和验收结论 | 本机构建工具和中间产物 |
| `Cache/` | 可丢弃构建环境、临时证据与中间产物，不入 Git | 唯一源码、正式交付和可发布凭据 |

维护原则：用户能用来判断软件用途的内容写 README；后续贡献者必须遵守的稳定边界写 AGENTS；说明“具体怎样实现、构建或验收”的内容写对应 docs。当前模块行为的详细约束汇总在[模块契约](module-contracts.md)，修改时同步维护相关专题文档，避免同义配置或相互冲突的说明。

## 从源码构建

常用开发参数集中在根目录 [`app-config.json`](../app-config.json)，分为更新、设置默认值、外观和动效，修改后重新构建生效；已保存的用户设置优先。包名/版本/SDK/依赖、固定约束和内部实现细节仍保留在原处，详见[配置说明](app-configuration.md)。

准备 JDK 17、Android SDK Platform 37.0、Build Tools 36.0.0 和 Platform Tools，通过 `ANDROID_HOME` 或 Android Studio 配置 SDK 路径。项目使用 Gradle Wrapper 9.3.1、Android Gradle Plugin 9.1.1 和 Kotlin 2.4.10。

在项目根目录执行：

编译、单元测试和 Lint 不需要私钥；干净克隆和公共 CI 可执行以下检查，不生成安装包（Windows 使用 `gradlew.bat`）：

```sh
./gradlew :app:compileDebugJavaWithJavac :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug :app:testReleaseUnitTest :app:lintRelease
```

生成 APK/AAB 前配置根目录 `keystore.properties`。debug 和 Release 统一使用包名 `io.github.flipcover.controls` 及同一签名；缺少签名配置时打包明确失败，不回退到默认 debug 签名。以下值须替换为自己的真实密钥配置：

```properties
storeFile=signing/flip-assistant-release.keystore
storePassword=YOUR_STORE_PASSWORD
keyAlias=flip-assistant
keyPassword=YOUR_KEY_PASSWORD
```

密钥和密码文件已被 Git 忽略，必须单独安全备份，并供后续两种构建持续使用。丢失密钥就无法按现有签名更新。源码克隆不包含密钥，构建前需自行配置。

```sh
# macOS / Linux
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

# Windows
gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

# Release 构建与检查（Windows 使用 gradlew.bat）
./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
```

调试安装包输出到 `Cache/build-output.nosync/app/outputs/apk/debug/app-debug.apk`，正式安装包输出到 `Cache/build-output.nosync/app/outputs/apk/release/app-release.apk`，测试和 Lint 报告位于 `Cache/build-output.nosync/app/reports/`。后续 debug／Release 可互相覆盖安装并保留应用数据，前提是沿用这份密钥、相同包名且新包 `versionCode` 不低于已安装版本；新版本递增 `versionCode`，不支持用低版本包降级覆盖。

最终打包交付统一使用 `dist/update-release/<versionCode>/flipcover-<versionName>.apk`（Release）或 `dist/update-debug/<versionCode>/flipcover-<versionName>-debug.apk`（debug），同目录附带与实际 APK 对应的 `catalog.json`。不再将新 APK 散放在 `dist/` 根目录或仅交付 Cache 中间产物，详见[打包规范](online-update.md)。

旧版 debug 使用 `.debug` 包名及旧调试签名，首次迁移到本版会作为不同应用并存：先从旧版导出配置，再在新版导入，重新选择目标外屏并手动授权；原生组件身份不迁移，须重新添加绑定。确认迁移后再卸载旧版。成功构建和本地检查不等同于三星外屏真机验收；设备端检查及物理真机流程见下文。历史结果保留在[项目历史](project-history.md)，不作为当前环境配置。

## 本地与设备验证

移除“选择自动隐藏的应用”中的“三星外屏与系统面板”后，`shortcut-apps` 检查默认仅三星桌面、旧偏好清理、按名称／包名搜索无结果，以及独立状态栏名单仍可选系统界面；`configuration` 检查旧文件导入过滤及重新导出；`clock-dock` 通过生产 `Prefs.compactApps` 验证跟随首页在应用自动隐藏开启时仍使用所选快捷栏模式。真机安装成功不等于首页触摸及窗口路由已验收。

首页翻页防闪回归在 `DockVisibilityTest` 检查监听需求与首页目标收起判断，在 `clock-dock` 的隔离副屏夹具调用生产翻页入口：System UI 不在名单且自动隐藏开关两态下，窗口元数据晚到、两个启动器按钮由禁用转可用时，原快捷栏须先隐藏；随后只挂载收起箭头，不重建原按钮窗口。拒绝主屏、其他包及未知包事件，跟随常驻或已展开状态不预隐藏。夹具覆盖生产同步顺序，三星真实分页事件时序与视觉结果仍须真机确认。

`clock-card-exit` 是卡片退出的短回归入口，`clock-dock` 也包含同一检查。它在不触发翻页事件的情况下调用生产卡片可见性更新：原生卡片先报告不可见，窗口仍是原宿主或短暂失焦时，入口须保持灰显；首页收起期间不得显示可用图标，隐藏后才解除禁用。另检查其他原生卡片的有界超时、确认普通应用立即恢复和跟随常驻模式不延迟。`dumpsys` 的 `Launcher visibility transitions` 可核对真机最后16条状态顺序，记录仅驻留内存且不包含应用身份。

控制中心状态可用 `-e scenario control-states` 在可丢弃模拟器检查实际绘制的开启、关闭、未知、执行中、普通动作语义与旋转模式；不执行系统开关。`SystemSwitchesTest` 覆盖新状态切换、异步确认、数据 SIM／外屏失效取消和有界等待。系统服务状态及广播需以 Shell 身份另行检查；三星真机须确认双卡切换、系统外部修改后的同步、断连、息屏和关闭面板后的订阅释放。通用应用磁贴没有真实回读时仍为未知，不能把该 UI 检查当作第三方磁贴功能验收。

横屏布局兼容使用 `DockGeometryTest` 检查快捷栏对角换边、自动／手动定位和真实缺口避让；`-e scenario rotation-reverse` 在可丢弃模拟器四方向检查独立页面与控制详情的开关、方向高亮保持、默认关闭、本机持久化、0°／180°布局保持、双白条保持原保存侧及配置导入导出隔离。白条回归通过生产 `resolveContentGeometry` 覆盖横屏左／右顶部与开关两态。切换开关不下发旋转；改装三星物理外屏上的实际停靠、触摸和导航避让仍需真机验收。

`unmeasuredLandscapeKeepsButtonsInClearTailBesidePagingDots` 覆盖90°／270°手动定位及密度导致自动尾部未入选的回退路径：目标区域未碰到摄像头时，按钮应留在突出区域，翻页点仍保持原来的物理边缘距离。此检查使用合成缺口验证几何，不代表已确认其他手机的固件根因；真机还需核对实际缺口、导航避让设置及按钮触摸。

完成工作应有成功构建和与改动相关的本地检查。涉及设备测试时先构建设备端测试包：

```sh
./gradlew :app:assembleDebugAndroidTest
```

`app/src/androidTest/` 的 `UiSmokeInstrumentation` 提供分场景检查。只在专用、可重置配置的测试模拟器上运行；这些检查可能重置应用偏好，不在日常使用的手机上运行。安装应用及测试 APK 后，按相关专题文档选择场景，例如：

```sh
adb -s <TEST_EMULATOR_SERIAL> shell am instrument -w -e scenario app-update io.github.flipcover.controls.test/io.github.flipcover.controls.UiSmokeInstrumentation
```

包名与签名规则见上文；不要沿用历史文档中的 `.debug.test` 包名。场景的操作范围、前置条件与断言以对应检查源码和专题文档为准。

快捷栏自动回页使用 `-e scenario dock-auto-return` 在专用模拟器检查真实5秒延迟、新操作重置、长按保护、开关、收起与卸载取消；`-e scenario configuration` 检查配置往返、布局备份与撤销、缺省值和错误类型原子拒绝。三星物理外屏的窗口显隐及实际触摸仍需真机验收。

定位入口可用 `-e scenario location` 检查：仅在测试模拟器上运行，会重置应用配置，验证三个位置的动作保存及配置往返、默认控制中心、定位状态只读接口、广播订阅释放及控制中心/详情布局，不执行定位切换。实际 Shizuku 开关、系统外部变更后的状态同步、断连与锁屏、三星原生侧栏点击仍需物理真机验收。

省电磁贴使用 `-e scenario power-save-tile`：仅在可丢弃模拟器上执行，临时模拟拔电并开关系统省电模式，检查组件精确识别、初始状态、真实系统广播驱动的高亮与无障碍说明、执行中不预判成功、关闭后停止更新及重开回读；结束恢复省电与电池模拟状态。三星磁贴点击转发仍须在物理设备上确认。

该场景还用实际 `dumpsys power` 检查电源服务摘要解析，并确认省电 API 阻止玻璃时不取样且保留原因。`PowerDiagnosticsTest` 覆盖 full／adaptive 差异、配置缺失、转储格式不支持与隐私字段排除。遇到三星设置开关关闭但 API 为 true，先打开一次控制中心，再到问题诊断点击“通过 Shizuku 检测”并复制；对照 API、标准配置、服务侧 full／adaptive／advertise 与玻璃原因，不能把模拟器测试当作三星固件根因验证。

诊断刷新使用 `-e scenario diagnostic-refresh -e rotation 0/1/2/3`，按单个方向运行；检查当前状态采集、采集时间、详情展开与滚动保留、复制和显示一致、刷新后拒绝旧 Shizuku 回调以及未连接时可重试。Shizuku 补充结果使用受控测试服务，不执行系统命令；原始页面截图只作为 Cache 验证素材。

本地编译、单元测试、Lint、模拟器 UI 检查和三星物理真机验证分别报告。系统窗口路由、缺口与导航边衬、锁屏与解锁恢复、Shizuku 实际执行、三星原生卡片及第三方小组件须在所选物理外屏确认，不能用原型或模拟器结果替代。系统调用失败、未连接设备或未执行的项目明确标为未验证。

原始截图与日志保留在 `Cache/` 的对应任务目录；正式 APK、发布说明和最终 UI 效果按 [AGENTS](../AGENTS.md) 的交付规则放入 `dist/`。历史结果见[项目历史](project-history.md)，在线更新与打包专项见[在线更新](online-update.md)。

箭头的本地 UI 回归使用 `-e scenario clock-dock`，覆盖两种模式的首页名单例外、首页临时展开与退出恢复、首页淡出、非首页持续半透明、快速换页取消旧计时、设置模式同步、透明入口单击、区域外透传、动画反向、多指／尺寸取消和窗口清理；首页规则开关检查行／开关单次保存、摘要及滚动原位更新、生产偏好刷新。`DockVisibilityTest` 检查首页跟随规则两态、强制收起的临时状态与原展开状态隔离、同步时名单优先级、键盘避让、未知身份与会话重置。`configuration` 检查该开关的配置往返、布局备份／撤销、旧字段缺省及非法类型原子拒绝。`ClockDockGeometryTest` 检查四方向和真实缺口；`lock-wake` 检查显示器与亮屏生命周期。三星窗口路由、正常位置的实际点击、系统导航和锁屏仍需真机验收。

快捷栏模式的本地回归复用 `DockVisibilityTest`、`ClockDockGeometryTest` 和 `-e scenario clock-dock`：覆盖模式进入、应用／桌面／时钟页切换、键盘与配置刷新、息屏重置会话、设置开关与白条快捷动作双向同步，以及复用箭头的展开／收起动画和清理。`-e scenario configuration` 检查模式的配置导出导入、布局备份／撤销、旧配置默认值及非法类型的原子拒绝；`-e scenario panel-entry` 检查真实长按、滑动互斥与取消。物理三星外屏仍须确认跨应用与时钟页保持、四方向箭头避让、系统导航透传和锁屏行为。

箭头的导航区回归在 `ClockDockGeometryTest` 覆盖748×720外屏、右下369×66缺口及底部0/32/64/66px导航边衬，检查收起位置始终跟随原快捷栏，尺寸不变且不额外上移；用户导航避让仍由快捷栏位置统一决定。`clock-dock` 以隔离副屏身份和应用面板测试窗口覆盖生产挂载、模式回调、自动隐藏移除与恢复；首帧检查收起箭头位于原位置、窗口重挂和名单恢复没有入场位移，同时保留点击展开动画；`WindowStabilityChecks` 另以8组停靠方向／导航避让组合通过生产 `reconcile` 改变状态栏与导航栏边衬，检查4类窗口实例和挂载连续、状态订阅保留、白条内部绘制更新、箭头动画取消与触摸恢复。该夹具不替代真实应用切换和系统触摸投递。真机 `Dock arrow geometry` 诊断输出模式及两个位置。

## 项目结构

| 路径 | 内容 |
| --- | --- |
| `app/` | Android 源码、资源、单元测试和设备端测试 |
| `app-config.json` | 更新、设置默认值、主要外观与动效的常用调节参数 |
| `gradle/`、`gradlew`、`gradlew.bat` | 可复用的 Gradle 构建入口 |
| `docs/` | 开发流程、模块契约、功能边界、设计方案与验证文档 |
| `dist/` | `html/` 静态网站、`开发设计/` 历史资料、标准设备框及版本交付；`update-debug/` 和 `update-release/` 整目录不入库，APK 与配套元数据独立分发；`开发设计/readme-gallery/` 的展示图片入库 |
| `tools/` | 设备外框、macOS 带框投屏工具和独立通知测试 App 源码 |
| `tools/project/` | 跨平台工程检查、网页组装、真实 APK 验证及正式版本准备 |
| `.github/workflows/ci.yml` | main 自动检查/Pages 部署；版本标签检查、正式签名打包及 GitHub 预发布 |
| `.agents/`、`AGENTS.md` | 项目协作与设置界面设计规则 |
| `Cache/` | 被 Git 忽略的本机工具、构建产物及临时验证资料 |
