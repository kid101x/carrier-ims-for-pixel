# Android 10 SIM 读取修复 Spec

## Why

上一轮把 `readSimInfoList` 改为直接 Shizuku Binder 路径（`readSimInfoListViaBinder`）以避开 `startInstrumentation` 的 force-stop，方向正确。但真机测试显示：Shizuku 授权正常、UI 不闪退，**但 SIM 卡无法读取、刷新也不行**。

根因（已确认）：**ISub 方法签名版本判断错误**。

AOSP 中 `ISub.getActiveSubscriptionInfoList` 的签名按 Android 版本演进：

| API 版本 | 签名 |
|---------|------|
| API 22 (Android 5.1) | `getActiveSubscriptionInfoList()` 无参 |
| API 23 (Android 6) | `getActiveSubscriptionInfoList(String callingPackage)` 一参 |
| **API 30 (Android 11, R)** | `getActiveSubscriptionInfoList(String, String)` 两参 |
| API 34 (Android 14) | `getActiveSubscriptionInfoList(String, String, boolean)` 三参 |

当前代码（`SimReader.kt:186-194`、`ShizukuProvider.kt:138-147`）的版本分支是：
- `SDK_INT >= S` (31)：三参（正确，Android 12+）
- `SDK_INT < S`（含 Android 10/11）：**反射两参 `(String, String)`**

但两参签名是 **API 30 (Android 11)** 才加入的。Android 10 (API 29) 上只有**一参 `(String)`** 签名。因此在 Android 10 上反射找不到两参方法 → `NoSuchMethodException` → 被 catch → 返回 null → SIM 列表为空。

次要问题：`READ_PHONE_STATE` 权限未声明。经 `ShizukuBinderWrapper` 路由到 Shizuku shell 进程后调用以 shell UID 执行，权限检查本身能通过；但声明该权限作为兜底（部分 ROM 的 ISub 实现会检查调用方包名对应的声明权限）更稳妥。

## What Changes

### 修复一：ISub 方法签名三分支（核心修复）
- `SimReader.readByISub` 和 `ShizukuProvider.readSimInfoListViaBinder` 的反射分支改为三分支：
  - `SDK_INT >= UPSIDE_DOWN_CAKE` (34)：三参 `getActiveSubscriptionInfoList(String, String, boolean)`
  - `SDK_INT >= R` (30)：两参 `getActiveSubscriptionInfoList(String, String)`（直接调 stub，无需反射）
  - `SDK_INT < R`（Android 10 及以下）：**一参** `getActiveSubscriptionInfoList(String)`（反射调用）
- `callingPackage` 参数传 `context.packageName`（非 null），避免 ISub 实现的包名校验拒绝。

### 修复二：声明 READ_PHONE_STATE 权限（兜底）
- `AndroidManifest.xml` 添加 `<uses-permission android:name="android.permission.READ_PHONE_STATE" />`。
- 该权限为 dangerous，但经 ShizukuBinderWrapper 路由的调用以 shell UID 执行，无需用户运行时授权即可通过权限检查；声明是为了兜底部分 ROM 的包名-权限校验。

### 修复三：增强 Binder 路径日志（便于真机诊断）
- `readSimInfoListViaBinder` 在每个失败点写明确日志：service 不可用 / 反射找不到方法 / 调用抛异常 / 返回空列表，附带异常类型与消息。

### 修复四：屏蔽调试期浮窗广告
- 在 `MainActivity` 的 `LaunchedEffect(Unit)` 中跳过 `fetchCommercialAds` / `homeAdToShow` 计算（不发起网络请求）。
- 跳过 `CommercialAdDialog` 渲染分支。
- 保留 `CommercialAd` 数据模型与 `COOPERATION_CARD` 代码不删；不修改 `adFreeEnabled` 真实判定逻辑。

## Impact
- Affected code:
  - `app/src/main/java/io/github/vvb2060/ims/privileged/SimReader.kt`（`readByISub` 三分支）
  - `app/src/main/java/io/github/vvb2060/ims/ShizukuProvider.kt`（`readSimInfoListViaBinder` 三分支 + 日志）
  - `app/src/main/AndroidManifest.xml`（READ_PHONE_STATE 权限）
  - `app/src/main/java/io/github/vvb2060/ims/ui/MainActivity.kt`（屏蔽广告浮窗）
- 影响范围：Android 10 (API 29) 的 SIM 读取；Android 11+ 与 Android 14+ 路径不变。
- **不**回滚用户对工作区的其他改动；**不**改动上一轮已生效的"UI 不闪退"逻辑（MainActivity 的 `canAutoInstrument` 守卫保持，待 SIM 读取确认后再决定是否恢复自动查询其他状态）。

## ADDED Requirements

### Requirement: ISub 方法签名按 Android 版本三分支
The system SHALL 在调用 `ISub.getActiveSubscriptionInfoList` 时按 `Build.VERSION.SDK_INT` 选择正确签名：`>= UPSIDE_DOWN_CAKE` (34) 用三参、`>= R` (30) 用两参、`< R`（含 Android 10）用一参 `(String)`。

#### Scenario: Android 10 反射一参签名成功
- **WHEN** 在 Android 10 (API 29) 上调用 `readSimInfoListViaBinder`
- **THEN** 反射找到一参 `getActiveSubscriptionInfoList(String)`，传入 `context.packageName`，返回真实 SIM 列表

#### Scenario: Android 11+ 直接调用两参 stub
- **WHEN** `SDK_INT >= R` (30)
- **THEN** 直接调用 stub 的两参 `getActiveSubscriptionInfoList(String, String)`，不反射

#### Scenario: Android 14+ 直接调用三参 stub
- **WHEN** `SDK_INT >= UPSIDE_DOWN_CAKE` (34)
- **THEN** 直接调用三参 `getActiveSubscriptionInfoList(String, String, boolean)`

### Requirement: 声明 READ_PHONE_STATE 权限
The system SHALL 在 `AndroidManifest.xml` 声明 `android.permission.READ_PHONE_STATE` 作为兜底，确保 ISub 调用的包名-权限校验通过。

#### Scenario: Manifest 含 READ_PHONE_STATE
- **WHEN** 检查 `AndroidManifest.xml`
- **THEN** 包含 `<uses-permission android:name="android.permission.READ_PHONE_STATE" />`

### Requirement: Binder 路径失败日志可诊断
The system SHALL 在 `readSimInfoListViaBinder` 的每个失败点写明确日志（service 不可用 / 反射失败 / 调用异常 / 空列表），附异常类型与消息。

#### Scenario: 反射失败日志
- **WHEN** 反射找不到方法
- **THEN** 日志含 `readSimInfoListViaBinder: failed` + 异常类名与消息，UI 返回空列表不崩溃

### Requirement: 调试期屏蔽首页浮窗广告
The system SHALL 在调试期不拉取商业广告、不弹出首页 `HOME_POPUP` 浮窗 `CommercialAdDialog`。

#### Scenario: 启动无浮窗
- **WHEN** 用户打开 App
- **THEN** 不发起 `fetchCommercialAds` 网络请求，不显示 `CommercialAdDialog`，UI 直接进入主界面

## MODIFIED Requirements

### Requirement: Shizuku 状态反映真实授权
`updateShizukuStatus()` SHALL 实时检测 Shizuku binder 存活与权限授予，按 `NOT_RUNNING` / `NO_PERMISSION` / `NEED_UPDATE` / `READY` 真实反映；不得为绕过崩溃而将状态写死。现状已正确，本 spec 保持不变。

## REMOVED Requirements
（无）
