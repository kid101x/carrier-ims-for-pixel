# Android 10 分阶段可用性修复 Spec

## Why

上一轮"避开 instrumentation"的修法是治标不治本：在 `LaunchedEffect` 里用 `SDK_INT >= UPSIDE_DOWN_CAKE` 守卫直接跳过 `queryCaptivePortalFixState` / `loadCurrentConfiguration` / `readImsRegistrationStatus`，结果 UI 看起来"就绪"但实际不读数据，SIM 列表也无法刷新——等同于把 Shizuku 状态"假装成就绪"而忽略功能落地。用户在 Android 10（Pixel 1 / marlin，API 29）上需要一个**真正可用**的 App：授权正常、UI 不闪退、SIM 能读、配置能应用。

根因回顾：
- Android 10–13 上 `IActivityManager.startInstrumentation` 的 `flags=8`（`INSTR_FLAG_NO_RESTART`）不被识别 → force-stop 目标包（本 App）→ 杀掉 UI 进程与同进程的 `IInstrumentationWatcher` → 结果无法回传 → "授权后一闪即退"。
- 6 个 privileged 文件中 `startDelegateShellPermissionIdentity` / `stopDelegateShellPermissionIdentity`（Android 11+ 才有）无 SDK_INT 守卫 → Android 10 上抛 `NoSuchMethodError`（被 catch 但功能失败）。
- 浮窗广告在调试期无意义，且其网络拉取（`fetchCommercialAds`）会增加启动期变量与不确定性。

## What Changes

### 阶段一：Shizuku 授权正确（不写死状态）
- 保留现有 `updateShizukuStatus()` 实时检测逻辑（`pingBinder` + `checkSelfPermission`），不引入任何"默认 READY"的写死值。
- 保留 `Shizuku.isPreV11()`、`NOT_RUNNING` / `NO_PERMISSION` / `NEED_UPDATE` / `READY` 四态真实反映。
- UI 根据**真实**状态展示对应提示，授权前不得显示功能面板的"可用"态。

### 阶段二：UI 不闪退（不经 instrumentation 读 SIM）
- `readSimInfoList` 在 `SDK_INT < UPSIDE_DOWN_CAKE` 时走**直接 Shizuku Binder** 调用 `ISub.getActiveSubscriptionInfoList`（已实现于 `readSimInfoListViaBinder`），不经 `startInstrumentation`，不触发 force-stop。
- 该 Binder 路径必须真实执行：经 `ShizukuBinderWrapper` 路由到 Shizuku shell 进程，具备 shell 权限读取 SIM 列表。
- SIM 列表刷新（手动/自动）在 Android 10 上必须能返回真实数据，而非被守卫跳过返回空。
- **撤销**上一轮在 `MainActivity` 三个 `LaunchedEffect` 中粗暴跳过的写法：Captive Portal / CarrierConfig / IMS 状态在 Android 10 上改为**直接 Binder 路径**（不经 instrumentation），而非"跳过不读"。

### 阶段三：SIM 读取正常
- `readSimInfoListViaBinder` 在 Android 10 上使用两参 `getActiveSubscriptionInfoList(String, String)`（反射调用），Android 12+ 用三参。
- 复用 `SimReader.toRaw` 提取纯字段，结果不含 `SubscriptionInfo`/`Bitmap`/FD。
- 失败时返回空列表并写日志（`readSimInfoListViaBinder: failed`），UI 显示"未读到 SIM"而非崩溃。

### 阶段四：功能落地可行（特权操作不经 instrumentation）
- 为以下特权操作在 `SDK_INT < UPSIDE_DOWN_CAKE` 时提供**直接 Shizuku Binder** 替代路径（与 `readSimInfoListViaBinder` 同思路），不经 `startInstrumentation`：
  - `overrideImsConfig`（ImsModifier）：写 IMS 配置
  - `readCarrierConfig`（ConfigReader）：读 CarrierConfig
  - `readImsRegistrationStatus`（ImsStatusReader）：读 IMS 注册状态
  - `restartImsRegistration`（ImsResetter）：重置 IMS
  - `applyApnConfig`（ApnModifier）：写 APN
  - `queryCaptivePortalConfig` / `applyCaptivePortalCnUrls` / `restoreCaptivePortalDefaultUrls`（CaptivePortalFixer）：Captive Portal 修复
- 同时修复 F-1：6 个 privileged 文件中 `startDelegateShellPermissionIdentity` / `stopDelegateShellPermissionIdentity` 加 `SDK_INT >= R` 守卫（Android 10 上跳过，instrumentation 本身已带 shell 权限）。
- `ShizukuProvider.startInstrumentation` 的 `flags` 动态化：`val flags = if (SDK_INT >= UPSIDE_DOWN_CAKE) 8 else 0`（兜底，阶段四完成后理论上不再走该路径）。

### 阶段五：屏蔽调试期浮窗广告
- 屏蔽首页浮窗广告（`HOME_POPUP` 的 `CommercialAdDialog`）与启动期 `fetchCommercialAds` 网络拉取，**仅调试期**。
- 实现方式：在 `MainActivity` 的 `LaunchedEffect(Unit)` 中跳过 `fetchCommercialAds` / `homeAdToShow` 计算，并跳过 `CommercialAdDialog` 渲染。
- 保留 `CommercialAd` 数据模型与合作卡片（`COOPERATION_CARD`）代码，不删除；仅屏蔽启动浮窗的触发与渲染。
- 不修改 `adFreeEnabled` 的真实判定逻辑（付款验证仍可用）。

## Impact
- Affected code:
  - `app/src/main/java/io/github/vvb2060/ims/ShizukuProvider.kt`（核心：新增多个 Binder 替代路径、flags 动态化）
  - `app/src/main/java/io/github/vvb2060/ims/privileged/ImsModifier.kt`、`ConfigReader.kt`、`ImsStatusReader.kt`、`ImsResetter.kt`、`ApnModifier.kt`、`CaptivePortalFixer.kt`、`BrokerInstrumentation.kt`（delegate 守卫）
  - `app/src/main/java/io/github/vvb2060/ims/ui/MainActivity.kt`（撤销粗暴跳过、屏蔽广告浮窗）
  - `app/src/main/java/io/github/vvb2060/ims/viewmodel/MainViewModel.kt`（Binder 路径透传）
- 影响范围：Android 10–13 上的全部特权操作；Android 14+ 保持现有 instrumentation 路径不变。
- **不**回滚用户对工作区的其他改动。

## ADDED Requirements

### Requirement: 直接 Shizuku Binder 特权操作路径（Android < 14）
The system SHALL 在 `Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE` 时，为以下特权操作提供不经 `startInstrumentation` 的直接 Shizuku Binder 调用路径：`readSimInfoList`、`overrideImsConfig`、`readCarrierConfig`、`readImsRegistrationStatus`、`restartImsRegistration`、`applyApnConfig`、`queryCaptivePortalConfig`、`applyCaptivePortalCnUrls`、`restoreCaptivePortalDefaultUrls`。

#### Scenario: Android 10 上读取 SIM 列表不闪退
- **WHEN** 用户在 Android 10 设备上授权 Shizuku 后打开 App
- **THEN** UI 不闪退，SIM 列表通过直接 Binder 路径读取并真实显示

#### Scenario: Android 10 上读 IMS 注册状态
- **WHEN** App 在 Android 10 上调用 `readImsRegistrationStatus(subId)`
- **THEN** 经直接 Binder 路径返回真实布尔值，而非被守卫跳过返回 null

#### Scenario: Android 14+ 保持原路径
- **WHEN** `SDK_INT >= UPSIDE_DOWN_CAKE`
- **THEN** 继续走 `startInstrumentation` 路径（`flags=8` 生效，不 force-stop）

### Requirement: 调试期屏蔽首页浮窗广告
The system SHALL 在调试期不拉取商业广告、不弹出首页 `HOME_POPUP` 浮窗 `CommercialAdDialog`。

#### Scenario: 启动无浮窗
- **WHEN** 用户打开 App
- **THEN** 不发起 `fetchCommercialAds` 网络请求，不显示 `CommercialAdDialog`，UI 直接进入主界面

## MODIFIED Requirements

### Requirement: Shizuku 状态反映真实授权
`updateShizukuStatus()` SHALL 实时检测 Shizuku binder 存活与权限授予，按 `NOT_RUNNING` / `NO_PERMISSION` / `NEED_UPDATE` / `READY` 真实反映；不得为绕过崩溃而将状态写死为 `READY`。

#### Scenario: 未授权显示真实状态
- **WHEN** Shizuku 未运行或未授权
- **THEN** UI 显示 `NOT_RUNNING` / `NO_PERMISSION` / `NEED_UPDATE` 对应提示，不显示功能可用态

### Requirement: startDelegateShellPermissionIdentity 版本守卫
所有 privileged Instrumentation 子类 SHALL 在调用 `startDelegateShellPermissionIdentity` / `stopDelegateShellPermissionIdentity` 前用 `Build.VERSION.SDK_INT >= Build.VERSION_CODES.R` 守卫；Android 10 上跳过该调用（instrumentation 本身已带 shell 权限）。

#### Scenario: Android 10 不抛 NoSuchMethodError
- **WHEN** 在 Android 10 上运行任意 privileged Instrumentation
- **THEN** 不调用 `startDelegateShellPermissionIdentity`，不抛 `NoSuchMethodError`，特权操作正常执行

## REMOVED Requirements

### Requirement: LaunchedEffect 粗暴跳过 instrumentation
**Reason**: 上一轮在 `MainActivity` 三个 `LaunchedEffect` 中用 `canAutoInstrument = SDK_INT >= UPSIDE_DOWN_CAKE` 守卫直接跳过 `queryCaptivePortalFixState` / `loadCurrentConfiguration` / `readImsRegistrationStatus`，导致 UI 看似就绪但不读数据。改为阶段二的 Binder 替代路径，恢复真实数据读取。
**Migration**: 删除 `canAutoInstrument` 守卫分支，改为在 ViewModel/Provider 层按 SDK_INT 选择 Binder 路径（数据真实返回），UI 层无需感知版本差异。
