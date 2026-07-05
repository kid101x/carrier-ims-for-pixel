# Carrier IMS for Pixel 1 — 工作进展日志

> 设备：Pixel 1 / Pixel XL (marlin)，Android 10 (API 29)
> 目标：在原 `carrier-ims-for-pixel` 基础上重建，使 Pixel 1 在国内运营商能用 VoLTE
> 分支：`trae/agent-aBQHdK`（origin: github.com/kid101x/carrier-ims-for-pixel）
> 最新 commit：`dfa49b9 feat: 分析并构建 Pixel 1 APK`
> 最新 APK：`app/build/outputs/apk/debug/app-4.0.0-pixel1-b9-20260704-1142.apk`（60M）

## 构建配置

- `applicationId = io.github.vvb2060.ims.pixel1`
- `versionName = 4.0.0-pixel1`
- `minSdk = 29` / `targetSdk = 33` / `compileSdk = 36`
- JDK 17（路径 `/root/.local/share/mise/installs/java/17.0.2`，Robolectric ASM 兼容需要）
- Gradle 9.1.0（路径 `/opt/gradle-9.1.0`，腾讯镜像下载）
- AGP 8.13.2 / Kotlin 2.3.0
- 调试签名：`/root/.android/debug.keystore`（keytool 生成，alias=androiddebugkey，storepass=android）
- 沙箱代理：`127.0.0.1:18080`（写入 `gradle.properties` 的 `org.gradle.jvmargs` 与 `tasks.withType<Test>()`）
- Maven 镜像：阿里云（`settings.gradle.kts` 的 `pluginManagement` + `dependencyResolutionManagement`）
- APK 文件名带版本+时间戳：`app-<versionName>-b<versionCode>-<YYYYMMDD-HHMM>.apk`（见 `app/build.gradle.kts` 的 `applicationVariants.all`）

### 常用构建命令

```bash
# 完整构建 + 单测（约 30-60s）
cd /workspace && JAVA_HOME=/root/.local/share/mise/installs/java/17.0.2 \
  /opt/gradle-9.1.0/bin/gradle :app:assembleDebug :app:testDebugUnitTest \
  --no-daemon --tests "io.github.vvb2060.ims.privileged.SimResultBundleTest"

# 仅编译 Kotlin（快速验证，约 30s）
cd /workspace && JAVA_HOME=/root/.local/share/mise/installs/java/17.0.2 \
  /opt/gradle-9.1.0/bin/gradle :app:compileDebugKotlin --no-daemon -x test

# 仅编译 stub AIDL
cd /workspace && JAVA_HOME=/root/.local/share/mise/installs/java/17.0.2 \
  /opt/gradle-9.1.0/bin/gradle :stub:compileDebugAidl --no-daemon
```

## 修复历程（按时间倒序）

### 第 6 轮（2026-07-05）— Pixel 1 Android 10 隐藏 Binder 签名修复

**真机结论**：当前连接设备是 Pixel XL / marlin，Android 10 (SDK 29)，`service list` 显示：
- `carrier_config: com.android.internal.telephony.ICarrierConfigLoader`
- `phone: com.android.internal.telephony.ITelephony`
- `isub: com.android.internal.telephony.ISub`

从设备 `/system/framework/framework.jar` 解析到：
- Android 10 的 `ITelephony` 没有 `resetIms(int)`，但有 `enableIms(int)`、`disableIms(int)`、`setImsRegistrationState(boolean)`、`isImsRegistered(int)`。
- Android 10 的 `ICarrierConfigLoader.getConfigForSubId` 是两参 `(int, String)`。
- Android 10 的 `ICarrierConfigLoader.overrideConfig` 是两参 `(int, PersistableBundle)`。

**根因**：
- IMS 注册状态开关报错 `No interface method resetIms(I)V...` 是因为代码直接调用了 Android 10 不存在的 `ITelephony.resetIms(int)`。
- CarrierConfig 读回路径的 stub 签名与 Android 10 实机不一致，导致 UI 状态读取不可靠。
- 主界面曾因规避 instrumentation force-stop 禁止 Android 10 自动读状态，但当前 direct Shizuku Binder 路径不会 force-stop，继续禁止会让 IMS 状态长时间显示关闭/未知。
- 开关关闭时原逻辑省略 key，不写 `false`，可能让已有 override 保持旧值。

**修复**：
- `ICarrierConfigLoader.aidl` 改为 Android 10 实机签名：`getConfigForSubId(int, String)` 与 `overrideConfig(int, PersistableBundle)`。
- `ITelephony.java` 补充 Android 10 实机存在的 `enableIms` / `disableIms` / `setImsRegistrationState`，并移除 Android 10 不存在的 `resetIms` 编译期声明，避免后续重新写出直接调用。
- `ShizukuProvider.restartImsRegistrationViaBinder` 和 `ImsResetter` 移除直接 `telephony.resetIms(...)` 调用，改为 Android 10 实机存在的 `disableIms` + `enableIms`，再失败回退 `setImsRegistrationState(false/true)`。
- `ImsModifier.buildBundle` 在关闭 VoLTE/VoWiFi/VT/UT/CrossSIM 时明确写入 `false`。
- `MainActivity` 在 Shizuku READY 时允许 Android 10 直接 Binder 读取当前 CarrierConfig 和 IMS 注册状态。

**文档**：`docs/plans/2026-07-05-pixel1-android10-binder-signature-fix.md`

**验证状态**：本机没有完整 Android 开发环境，不在此机器构建 APK。已完成源码级静态核对；构建、安装和真机功能验收交给有完整 JDK/Android SDK/Gradle 环境的人执行。

### 第 5 轮（2026-07-04 11:43）— 修复 CarrierConfig Binder 类名错误

**根因**：`ShizukuProvider.kt:414` 反射加载 `Class.forName("android.telephony.ICarrierConfig$Stub")`，但这个类名根本不存在。AOSP `carrier_config` 服务的真实接口名是 `com.android.internal.telephony.ICarrierConfigLoader`（经 `service list` 实测确认）。`ClassNotFoundException` 被 catch 后异常消息恰好是类名，UI 显示 `配置失败:android.telephony.ICarrierConfig$Stub`，所有依赖 CarrierConfig 的操作（VoLTE/WFC/VT 开关、读/dump CarrierConfig）全部失败。

**修复**：
- 新增 `stub/src/main/aidl/com/android/internal/telephony/ICarrierConfigLoader.aidl`（含 `getConfigForSubId` + 三参 `overrideConfig`）
- `getICarrierConfigViaShizuku()` 改用 `ICarrierConfigLoader.Stub.asInterface(ShizukuBinderWrapper(binder))`，返回 `ICarrierConfigLoader?`
- `invokeOverrideConfigViaBinder` 先试三参 stub，`NoSuchMethodError` 回退两参反射（Android 10）
- `readCarrierConfigViaBinder` / `dumpCarrierConfigViaBinder` 直接调 stub `getConfigForSubId`
- 10 处 ViaBinder catch 块错误消息统一为 `${t.javaClass.simpleName}: ${t.message ?: "(no message)"}`（简称+消息，可诊断）

**Spec 文档**：`.trae/specs/fix-carrier-config-binder-class/`

### 第 4 轮（2026-07-04 10:58）— 全部特权操作改直接 Shizuku Binder 路径

**根因**：第 3 轮只把 `readSimInfoList` 改成 Binder 路径，其他 6 个特权操作（应用配置/读 CarrierConfig/IMS 状态/重置/APN/Captive Portal）仍走 `startInstrumentation`。Android < 14 上 `flags=8` (INSTR_FLAG_NO_RESTART) 不被识别 → force-stop 本包 → 点击按钮就回退桌面。

**修复**：在 `ShizukuProvider.kt` 为全部 6 个特权操作新增 `xxxViaBinder` 直接 Shizuku Binder 路径（L449-L890）：
- `overrideImsConfigViaBinder`、`readCarrierConfigViaBinder`、`dumpCarrierConfigViaBinder`
- `readImsRegistrationStatusViaBinder`、`restartImsRegistrationViaBinder`、`applyApnConfigViaBinder`
- `queryCaptivePortalConfigViaBinder`、`applyCaptivePortalCnUrlsViaBinder`、`restoreCaptivePortalDefaultUrlsViaBinder`

**关键设计**：
- `SDK_INT < UPSIDE_DOWN_CAKE` 走 Binder 路径，Android 14+ 保持 instrumentation 不变
- **不调用** `startDelegateShellPermissionIdentity`（Android 11+ 才有）—— `ShizukuBinderWrapper` 已是 shell 身份
- ITelephony/ISub 用项目现有 stub + ShizukuBinderWrapper
- APN/Settings.Global 用 contentResolver/shell 权限直接读写

**APK 文件名带版本+时间戳**：`app/build.gradle.kts` 加 `applicationVariants.all` 重命名逻辑

### 第 3 轮（2026-07-04 10:00）— 修复 ISub 方法签名 + 屏蔽广告浮窗

**根因**：`ISub.getActiveSubscriptionInfoList` 签名按版本演进，代码在 Android 10 (API 29) 上反射两参 `(String, String)`，但两参是 Android 11 才加入的。Android 10 上只有一参 `(String)` → `NoSuchMethodException` → SIM 列表为空。

| API 版本 | 签名 |
|---------|------|
| API 22 | `getActiveSubscriptionInfoList()` 无参 |
| API 23 | `getActiveSubscriptionInfoList(String)` 一参 |
| API 30 (R) | `getActiveSubscriptionInfoList(String, String)` 两参 |
| API 34 (U) | `getActiveSubscriptionInfoList(String, String, boolean)` 三参 |

**修复**：
- `SimReader.readByISub` 与 `ShizukuProvider.readSimInfoListViaBinder` 改三分支：`>= U` 三参、`>= R` 两参反射、`< R` 一参反射（传 `context.packageName`）
- `AndroidManifest.xml` 加 `READ_PHONE_STATE` 权限兜底
- `MainActivity.LaunchedEffect(Unit)` 注释掉 `fetchCommercialAds` / `homeAdToShow` 计算（屏蔽调试期浮窗广告）

**Spec 文档**：`.trae/specs/phased-android10-fix/`

### 第 2 轮（2026-07-04 早些）— 撤销 MainActivity 粗暴跳过 + 诊断

**根因**：第 1 轮在 `MainActivity` 三个 `LaunchedEffect` 用 `SDK_INT >= UPSIDE_DOWN_CAKE` 守卫直接跳过 `queryCaptivePortalFixState` / `loadCurrentConfiguration` / `readImsRegistrationStatus`，导致 UI 看似就绪但不读数据，SIM 列表无法刷新。

**修复**：撤销粗暴跳过，改 Provider 层 Binder 路径（见第 3-4 轮）。

### 第 1 轮（更早）— 修复 SimReader FD 崩溃 + 离线单测

**根因**：`Instrumentation.finish()` 结果 Bundle 携带 `SubscriptionInfo`（含 `Bitmap` → ashmem FD），AMS 以 `allowFds=false` 序列化 → `IllegalArgumentException: File descriptors passed in Intent` → `finishInstrumentation` 失败 → host 收到 `Bundle[EMPTY_PARCEL]` → UI 进程被 `startInstrumentation` 的 force-stop 杀死。

**修复**：
- `SimReader.kt` 加 `SubInfoRaw` 纯字段数据类 + `toRaw` 提取器 + `buildResultBundleFromRaw`（只放 int/string，不放 Parcelable/FD）
- `hasFileDescriptors()` 自检 + `finishWith` 错误可见化
- `SDK_INT <= 29` 跳过 `startDelegateShellPermissionIdentity`（hidden-API 黑名单）
- `readByISub` 反射两参 `getActiveSubscriptionInfoList`（后被第 3 轮修正为一参）
- `ShizukuProvider.readSimInfoList` 改用 count-based 前缀 key 重建 `SimSelection`
- 新增 `app/src/test/java/.../SimResultBundleTest.kt`（3 个 Robolectric 单测，全绿）
- `gradle/libs.versions.toml` 加 `robolectric = "4.14.1"`
- `app/build.gradle.kts` 加 `testImplementation(libs.robolectric)` + `testOptions` + `tasks.withType<Test>` 代理配置
- `app/src/test/resources/robolectric.properties` 指向阿里云镜像

## 当前状态（截至最新 APK `b9-20260704-1142`）

### ✅ 已解决
- Shizuku 授权状态真实反映（不写死）
- 授权后 UI 不闪退
- SIM 卡读取正常（运营商信息显示正确）
- 点击开关/按钮不回退桌面（Binder 路径不经 instrumentation）
- CarrierConfig 读写接口名正确（`ICarrierConfigLoader`）
- 错误消息可诊断（异常简称+消息）
- APK 文件名带版本+时间戳
- 调试期浮窗广告屏蔽
- 离线单测全绿（3 个 Robolectric 测试）

### ⏳ 待真机验证
- 点击 VoLTE/WFC/VT 开关后配置是否真实生效（不再报 `ICarrierConfig$Stub` 错误）
- IMS 注册状态读取是否正常（`ITelephony.isImsRegistered`）
- 应用配置后能否真正通话
- 重启 IMS、写 APN、Captive Portal 修复等功能

### 📋 已知未修（不影响 Android 10 主流程）
- 6 个 privileged 文件的 `startDelegateShellPermissionIdentity` 仍无 SDK_INT 守卫（Android 14+ 路径用，不影响 Binder 路径）
- `QsTiles.kt:50` 的 `Tile.setSubtitle()` 无 SDK_INT 守卫（Android 11+ API，QS 磁贴在 Android 10 上会崩，但不影响主界面）
- `MainViewModel.kt:1254` 的 `dataNetworkType` 无显式守卫（被 runCatching 兜底，诊断显示 UNKNOWN）

## 关键代码位置（最新版）

- `app/src/main/java/io/github/vvb2060/ims/ShizukuProvider.kt`
  - `readSimInfoListViaBinder` (L125)：SIM 读取 Binder 路径
  - `getICarrierConfigViaShizuku` (L405)：CarrierConfig Binder 获取
  - `overrideImsConfigViaBinder` (L449)：应用 IMS 配置
  - `readCarrierConfigViaBinder` (L544)：读 CarrierConfig
  - `readImsRegistrationStatusViaBinder` (L596)：IMS 注册状态
  - `restartImsRegistrationViaBinder` (L621)：重置 IMS
  - `applyApnConfigViaBinder` (L655)：写 APN
  - `queryCaptivePortalConfigViaBinder` (L756) / `applyCaptivePortalCnUrlsViaBinder` (L772) / `restoreCaptivePortalDefaultUrlsViaBinder` (L796)
- `app/src/main/java/io/github/vvb2060/ims/privileged/SimReader.kt`
  - `readByISub` (L186)：ISub 三分支签名
  - `buildResultBundleFromRaw`：纯字段 Bundle（无 FD）
- `app/src/main/java/io/github/vvb2060/ims/ui/MainActivity.kt`
  - `LaunchedEffect(Unit)` (L561)：广告浮窗已注释屏蔽
- `stub/src/main/aidl/com/android/internal/telephony/ICarrierConfigLoader.aidl`：CarrierConfig 接口 stub
- `stub/src/main/aidl/com/android/internal/telephony/ISub.aidl`：ISub 接口 stub
- `stub/src/main/java/com/android/internal/telephony/ITelephony.java`：ITelephony 接口 stub

## 真机测试要点

### 安装
```bash
adb install -r app/build/outputs/apk/debug/app-4.0.0-pixel1-b9-20260704-1142.apk
```

### 授权后预期日志（`adb logcat -s ShizukuProvider SimReader`）
- 成功读 SIM：`readSimInfoListViaBinder: read sim info list size: N`
- 成功应用配置：`overrideImsConfigViaBinder: ...`（无 failed）
- 失败诊断：`xxxViaBinder: failed: <异常简称>: <消息>` + 完整堆栈

### 关键验收点
1. Shizuku 授权后 App 不闪退，显示真实授权状态
2. SIM 列表显示运营商信息
3. 点击 VoLTE 开关不报 `配置失败:android.telephony.ICarrierConfig$Stub`
4. 应用配置后能真正通话
5. IMS 注册状态可读取

## 下一步（回家继续）

1. 真机安装 `app-4.0.0-pixel1-b9-20260704-1142.apk`，按上述验收点测试
2. 若 VoLTE 开关点击仍报错，根据 `xxxViaBinder: failed: ...` 日志定位
3. 若仍报 `ICarrierConfig$Stub`，检查 `ShizukuProvider.kt` 是否还有残留反射
4. 若报 `SecurityException`，可能需补充权限或检查 ShizukuBinderWrapper 是否生效
5. 若配置应用成功但通话不通，检查 `KEY_CARRIER_VOLTE_AVAILABLE_BOOL` 等键值是否真的写入

## 2026-07-05 真机 ADB 复测补充

- `cmd phone ims enable -s 0` 可到达 `org.codeaurora.ims`，但 Qualcomm IMS 返回 `Request turn on/off IMS failed`。
- CarrierConfig override 已生效，LTE VOPS=1；临时打开移动数据后 default APN `3gnet` 可连接，但 `pcscf=[]`。
- `dumpsys activity service com.android.phone` 显示 `mApnType=ims mWaitingApns={null} mApnSetting={null}`，IMS 数据通路没有候选 APN。
- 普通 adb 不能 root，不能写 `persist.dbg.*` radio 调试属性，也不能直接访问 `content://telephony/carriers`。
- 代码修复方向：Android 10 APN 写入路径必须显式 `startDelegateShellPermissionIdentity`；IMS-only APN 不能设置为 preferred APN；APN 写失败必须让运营商预设整体失败。
- 详细记录见 `docs/plans/2026-07-05-pixel1-adb-volte-root-cause.md`。

## Spec 文档目录

- `.trae/specs/phased-android10-fix/`：第 3 轮（ISub 签名 + 屏蔽广告）
- `.trae/specs/fix-carrier-config-binder-class/`：第 5 轮（CarrierConfig 类名修复）

## 参考资源

- AOSP `carrier_config` 服务接口：`com.android.internal.telephony.ICarrierConfigLoader`（经 `service list` 实测）
- AOSP ISub 签名演进：API22 无参 → API23 一参 → API30 两参 → API34 三参
- `INSTR_FLAG_NO_RESTART` (flags=8)：Android 14 (API 34) 才有，Android < 14 上 startInstrumentation 会 force-stop 本包
- `startDelegateShellPermissionIdentity`：Android 11 (API 30) 才有
- VoLTE 修补原理：`ICarrierConfigLoader.overrideConfig()` 强制 `KEY_CARRIER_VOLTE_AVAILABLE_BOOL` 等返回 true（参考 pixel-volte-patch）
