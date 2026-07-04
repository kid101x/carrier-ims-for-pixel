# Tasks

- [ ] Task 1: 阶段一 — 确认 Shizuku 授权逻辑真实（不写死状态）
  - [ ] SubTask 1.1: 核查 `MainViewModel.updateShizukuStatus()` 仍按 `pingBinder` + `checkSelfPermission` 真实判定四态，无任何"默认 READY"写死
  - [ ] SubTask 1.2: 核查 UI 在 `NOT_RUNNING` / `NO_PERMISSION` / `NEED_UPDATE` 态展示对应提示，授权前不显示功能可用态

- [ ] Task 2: 阶段二 — 撤销 MainActivity 粗暴跳过，改 Provider 层 Binder 路径
  - [ ] SubTask 2.1: 撤销 `MainActivity.kt` 中三个 `LaunchedEffect` 的 `canAutoInstrument`/`SDK_INT >= UPSIDE_DOWN_CAKE` 守卫分支，恢复对 `queryCaptivePortalFixState` / `loadCurrentConfiguration` / `readImsRegistrationStatus` 的真实调用
  - [ ] SubTask 2.2: 在 `ShizukuProvider` 各特权函数内按 `SDK_INT < UPSIDE_DOWN_CAKE` 选择 Binder 路径，使 UI 层无需感知版本（数据真实返回）

- [ ] Task 3: 阶段三 — 确保 SIM 读取 Binder 路径真实可用
  - [ ] SubTask 3.1: 核查 `readSimInfoListViaBinder` 经 `ShizukuBinderWrapper` 路由到 Shizuku shell 进程，Android 10 反射两参 `getActiveSubscriptionInfoList(String, String)`
  - [ ] SubTask 3.2: 失败时返回空列表 + 日志（`readSimInfoListViaBinder: failed`），不崩溃；成功时返回真实 `SimSelection` 列表

- [ ] Task 4: 阶段四 — 为其余特权操作新增直接 Binder 路径
  - [ ] SubTask 4.1: `overrideImsConfig`（ImsModifier）在 Android < 14 走直接 Binder：`ServiceManager.getService` + `ShizukuBinderWrapper` 调 ImsModifier 等价逻辑（写 IMS 配置）
  - [ ] SubTask 4.2: `readCarrierConfig`（ConfigReader）在 Android < 14 走直接 Binder：经 Shizuku shell 读 `CarrierConfigManager.getConfigForSubId`
  - [ ] SubTask 4.3: `readImsRegistrationStatus`（ImsStatusReader）在 Android < 14 走直接 Binder
  - [ ] SubTask 4.4: `restartImsRegistration`（ImsResetter）在 Android < 14 走直接 Binder
  - [ ] SubTask 4.5: `applyApnConfig`（ApnModifier）在 Android < 14 走直接 Binder
  - [ ] SubTask 4.6: `queryCaptivePortalConfig` / `applyCaptivePortalCnUrls` / `restoreCaptivePortalDefaultUrls`（CaptivePortalFixer）在 Android < 14 走直接 Binder

- [ ] Task 5: 阶段四（续）— 修复 delegate 守卫与 flags 动态化
  - [ ] SubTask 5.1: 6 个 privileged 文件（ImsModifier/ConfigReader/ImsStatusReader/ImsResetter/ApnModifier/CaptivePortalFixer/BrokerInstrumentation）的 `startDelegateShellPermissionIdentity` / `stopDelegateShellPermissionIdentity` 加 `SDK_INT >= R` 守卫（仿 `SimReader.kt:122`）
  - [ ] SubTask 5.2: `ShizukuProvider.startInstrumentation` 的 `flags` 动态化：`val flags = if (SDK_INT >= UPSIDE_DOWN_CAKE) 8 else 0`

- [ ] Task 6: 阶段五 — 屏蔽调试期浮窗广告
  - [ ] SubTask 6.1: 在 `MainActivity` 的 `LaunchedEffect(Unit)` 中跳过 `fetchCommercialAds` / `homeAdToShow` 计算（不发起网络请求）
  - [ ] SubTask 6.2: 跳过 `CommercialAdDialog` 渲染（`if (!adFreeEnabled) homeAdToShow?.let` 分支不执行）
  - [ ] SubTask 6.3: 保留 `CommercialAd` 数据模型与 `COOPERATION_CARD` 代码不删除；不修改 `adFreeEnabled` 真实判定逻辑

- [ ] Task 7: 构建与验证
  - [ ] SubTask 7.1: `./gradlew :app:assembleDebug` 构建成功
  - [ ] SubTask 7.2: 运行 `SimResultBundleTest` 单测全绿（无回归）
  - [ ] SubTask 7.3: 产出 `app-debug.apk` 供真机测试

# Task Dependencies
- Task 2 依赖 Task 1（先确认授权逻辑真实，再撤销 UI 跳过）
- Task 3 与 Task 4 可并行（不同特权操作的 Binder 路径独立）
- Task 5 依赖 Task 4（delegate 守卫与 Binder 路径同属 privileged 层，一并改）
- Task 6 独立，可与 Task 2–5 并行
- Task 7 依赖 Task 2–6 全部完成
