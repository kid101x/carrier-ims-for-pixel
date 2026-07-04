# 修复 CarrierConfig Binder 类名错误 Spec

## Why

上一轮把特权操作改为直接 Shizuku Binder 路径后，SIM 卡读取、Shizuku 授权、UI 稳定性都已正常。但点击 IMS 开关 / 应用配置按钮时报错 `配置失败:android.telephony.ICarrierConfig$Stub`，VoLTE 等开关显示"开"但实际未生效（无法通话）。

根因（已确认）：[ShizukuProvider.kt:414](file:///workspace/app/src/main/java/io/github/vvb2060/ims/ShizukuProvider.kt#L414) 反射加载 CarrierConfig 的 Stub 类时用错了类名。

AOSP 中 `carrier_config` 服务的真实接口名是 `com.android.internal.telephony.ICarrierConfigLoader`（经 `service list` 实测确认，多个 AOSP 设备 dump 一致），而代码里写的是 `android.telephony.ICarrierConfig$Stub`——这个类在 Android 10/11/12/13/14 上**根本不存在**（`android.telephony` 包下只有面向 App 的 `CarrierConfigManager`，没有 `ICarrierConfig` 这个 AIDL 接口）。

后果：
- `Class.forName("android.telephony.ICarrierConfig$Stub")` 抛 `ClassNotFoundException`
- 被 `catch(Throwable)` 捕获，错误消息 `${t.javaClass.name}: ${t.message}` 里 `t.message` 恰好是类名字符串
- 最终展示给用户：`配置失败:android.telephony.ICarrierConfig$Stub`
- 所有依赖 CarrierConfig 的操作（应用 VoLTE/WFC/VT 开关、读 CarrierConfig、dump CarrierConfig）全部失败
- IMS 注册状态读取（用 ITelephony.isImsRegistered）本身是对的，但 UI 入口可能联动调用 CarrierConfig，所以也受影响

## What Changes

### 修复一：用正确的接口名 `com.android.internal.telephony.ICarrierConfigLoader`
- 在 `stub` 模块新增 `com/android/internal/telephony/ICarrierConfigLoader.aidl`，包含项目实际用到的方法：
  - `PersistableBundle getConfigForSubId(int subId)`（读 CarrierConfig）
  - `void overrideConfig(int subId, in PersistableBundle values, boolean persistent)`（Android 11+ 三参版）
  - `void overrideConfig(int subId, in PersistableBundle values)`（Android 10 两参版，AIDL 不允许重载，需用事务码反射或单独方法名）
- 由于 AIDL 不允许同名方法重载，stub 只编三参版（与 ISub 的处理方式一致），两参版在运行时反射调用框架真实实现（运行时框架类被加载，stub 仅用于编译期）。

### 修复二：ShizukuProvider 改用 stub 类型而非反射 Class.forName
- `getICarrierConfigViaShizuku()` 改为：`ICarrierConfigLoader.Stub.asInterface(ShizukuBinderWrapper(binder))`，与 `getISubViaShizuku` / `getITelephonyViaShizuku` 风格一致。
- `invokeOverrideConfigViaBinder` 改为：直接调 stub 的三参 `overrideConfig(subId, values, persistent)`；Android 10 两参路径用反射（`ICarrierConfigLoader::class.java.getMethod("overrideConfig", Int::class.javaPrimitiveType, PersistableBundle::class.java)`），三参 NoSuchMethodException 时回退两参。
- `readCarrierConfigViaBinder` / `dumpCarrierConfigViaBinder` 改为：直接调 stub 的 `getConfigForSubId(subId)`。

### 修复三：增强错误消息（避免再次出现"配置失败:类名"这种不可诊断消息）
- `overrideImsConfigViaBinder` 等 ViaBinder 函数的 catch 块，错误消息格式统一为 `"${t.javaClass.simpleName}: ${t.message ?: "(no message)"}"`，并 `Log.e` 完整堆栈。
- 确保 UI 层展示的错误消息包含可诊断信息（异常类简称 + 消息），而不是裸类名。

### 不修改项
- `ImsModifier.kt` / `ConfigReader.kt` 等 Instrumentation 子类保持不变（Android 14+ 仍走 instrumentation 路径，它们的 `CarrierConfigManager` 调用经 `startDelegateShellPermissionIdentity` 委托，是另一条路径，不在本次修复范围）。
- `ITelephony.isImsRegistered` / `resetIms` 路径保持不变（用户反馈 IMS 注册状态本身接口正确，只是被 CarrierConfig 错误连带影响）。

## Impact
- Affected code:
  - `stub/src/main/aidl/com/android/internal/telephony/ICarrierConfigLoader.aidl`（新增）
  - `app/src/main/java/io/github/vvb2060/ims/ShizukuProvider.kt`（`getICarrierConfigViaShizuku`、`invokeOverrideConfigViaBinder`、`readCarrierConfigViaBinder`、`dumpCarrierConfigViaBinder`、错误消息格式）
- 影响范围：Android < 14 的 CarrierConfig 读写路径（应用 VoLTE/WFC/VT 开关、读/dump CarrierConfig）。Android 14+ 走 instrumentation 不受影响。
- **不**回滚用户对工作区的其他改动。

## ADDED Requirements

### Requirement: CarrierConfig Binder 接口名正确
The system SHALL 通过 `com.android.internal.telephony.ICarrierConfigLoader` 接口（AOSP `carrier_config` 服务的真实接口名）访问 CarrierConfig，而非不存在的 `android.telephony.ICarrierConfig`。

#### Scenario: Android 10 应用 VoLTE 开关
- **WHEN** 用户在 Android 10 上点击 VoLTE 开关
- **THEN** 经 `ICarrierConfigLoader.overrideConfig` 写入 `KEY_CARRIER_VOLTE_AVAILABLE_BOOL=true`，不抛 `ClassNotFoundException`，开关状态真实生效

#### Scenario: Android 10 读 CarrierConfig
- **WHEN** App 在 Android 10 上调用 `readCarrierConfigViaBinder`
- **THEN** 经 `ICarrierConfigLoader.getConfigForSubId` 返回真实 PersistableBundle，而非因类名错误返回空

### Requirement: ICarrierConfigLoader stub 提供 overrideConfig 三参签名
The system SHALL 在 `stub` 模块新增 `ICarrierConfigLoader.aidl`，包含 `overrideConfig(int subId, in PersistableBundle values, boolean persistent)` 与 `getConfigForSubId(int subId)` 方法，供编译期类型检查使用。

#### Scenario: stub 编译通过
- **WHEN** 构建 stub 模块
- **THEN** `ICarrierConfigLoader.aidl` 编译生成 `ICarrierConfigLoader.java`，含三参 overrideConfig 与 getConfigForSubId

### Requirement: 错误消息可诊断
The system SHALL 在 ViaBinder 函数的 catch 块中返回 `"${异常类简称}: ${消息}"` 格式的错误消息，并记录完整堆栈到日志。

#### Scenario: 失败时用户可见可诊断消息
- **WHEN** 任意 ViaBinder 调用抛异常
- **THEN** UI 显示 `配置失败: <异常类简称>: <消息>`（如 `配置失败: SecurityException: Permission denied`），日志含完整堆栈

## MODIFIED Requirements

### Requirement: overrideConfig 版本回退
`invokeOverrideConfigViaBinder` SHALL 优先调用三参 `overrideConfig(int, PersistableBundle, boolean)`（Android 11+），`NoSuchMethodException` 时反射回退两参 `overrideConfig(int, PersistableBundle)`（Android 10），与 `ImsModifier.invokeOverrideConfig` 现有逻辑一致。

#### Scenario: Android 10 走两参回退
- **WHEN** 在 Android 10 上调用 overrideConfig
- **THEN** 三参反射抛 NoSuchMethodException → 回退两参反射调用成功

## REMOVED Requirements
（无）
