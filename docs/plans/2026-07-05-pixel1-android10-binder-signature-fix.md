# Pixel 1 Android 10 IMS Binder 签名修复

日期：2026-07-05

## 目标

本项目当前只面向 Pixel 1 / Pixel XL，系统版本 Android 10 (API 29)。本次修复只按这台真机的 framework 接口处理，不再扩展 Android 11+ / Android 14+ 兼容性。

## 真机确认

测试设备：
- 型号：Pixel XL / marlin
- 系统：Android 10，SDK 29
- 指纹：`google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys`
- 已安装包：`io.github.vvb2060.ims.pixel1`

设备侧服务：
- `carrier_config`: `com.android.internal.telephony.ICarrierConfigLoader`
- `phone`: `com.android.internal.telephony.ITelephony`
- `isub`: `com.android.internal.telephony.ISub`

从设备 `/system/framework/framework.jar` 解析到的关键签名：
- `ICarrierConfigLoader.getConfigForSubId(int, String)`
- `ICarrierConfigLoader.overrideConfig(int, PersistableBundle)`
- `ITelephony.enableIms(int)`
- `ITelephony.disableIms(int)`
- `ITelephony.setImsRegistrationState(boolean)`
- `ITelephony.isImsRegistered(int)`
- `ITelephony.resetIms(int)` 不存在

设备当前 `dumpsys carrier_config` 已显示 `mOverrideConfigs` 中存在以下覆盖：
- `carrier_volte_available_bool = true`
- `carrier_wfc_ims_available_bool = true`
- `carrier_vt_available_bool = true`
- `carrier_supports_ss_over_ut_bool = true`
- `carrier_name_string = 中国联通`

因此当前主要问题不是 CarrierConfig 完全写不进去，而是 Android 10 隐藏接口签名不匹配导致读状态、IMS 重启和 UI 同步失败。

## 根因

1. `resetIms(int)` 是当前代码直接调用的方法，但 Android 10 Pixel 1 的 `ITelephony` 没有这个接口。运行时会抛：
   `No interface method resetIms(I)V in class Lcom/android/internal/telephony/ITelephony;`

2. Android 10 的 `ICarrierConfigLoader.getConfigForSubId` 是两参 `(int, String)`，当前 stub 曾写成一参 `(int)`。这会导致 CarrierConfig 读回路径不稳定，开关 UI 可能不按系统实际值显示。

3. Android 10 的 `overrideConfig` 是两参 `(int, PersistableBundle)`。旧实现曾以三参 stub 为主再失败回退，本次已改成 Android 10 两参直调。

4. 主界面此前为了避开 Android 10 instrumentation force-stop，禁用了自动读 CarrierConfig / IMS 状态。但这些方法现在走直接 Shizuku Binder，不会 force-stop；继续禁用会导致 IMS 状态开关长期显示关闭或未知。

5. 功能开关关闭时，旧 `buildBundle()` 是省略对应 key，而不是写入 `false`。在 override bundle 已存在时，这容易造成 UI 有反应但系统覆盖项仍保持旧值。

## 已修改代码

- `stub/src/main/aidl/com/android/internal/telephony/ICarrierConfigLoader.aidl`
  - `getConfigForSubId` 改为 Android 10 实机存在的两参签名：`getConfigForSubId(int subId, String callingPackage)`。
  - `overrideConfig` 改为 Android 10 实机存在的两参签名：`overrideConfig(int subId, PersistableBundle values)`。

- `stub/src/main/java/com/android/internal/telephony/ITelephony.java`
  - 补充 Android 10 实机存在的 `enableIms(int)`、`disableIms(int)`、`setImsRegistrationState(boolean)`。
  - 移除 Android 10 实机不存在的 `resetIms(int)` 编译期声明，避免后续重新引入直接调用。

- `app/src/main/java/io/github/vvb2060/ims/ShizukuProvider.kt`
  - CarrierConfig 读取走两参 `getConfigForSubId(subId, context.packageName)`。
  - CarrierConfig 写入走两参 `overrideConfig(subId, values)`；Android 10 没有 persistent 参数，`prefer_persistent` 仅记录日志后忽略。
  - IMS 重启不再直接调用 `telephony.resetIms(slotIndex)`。
  - IMS 重启顺序：先用 Android 10 实机存在的 `disableIms(slot)` + `enableIms(slot)`，再失败时回退 `setImsRegistrationState(false/true)`。

- `app/src/main/java/io/github/vvb2060/ims/privileged/ImsResetter.kt`
  - 同步移除直接 `resetIms` 调用，避免 instrumentation 路径再次触发同类错误。

- `app/src/main/java/io/github/vvb2060/ims/privileged/ImsModifier.kt`
  - 开关关闭时明确写入 `false`，不再省略 key。

- `app/src/main/java/io/github/vvb2060/ims/ui/MainActivity.kt`
  - Shizuku READY 后允许直接 Binder 读取当前 CarrierConfig 和 IMS 注册状态，不再用 Android 14 instrumentation 条件挡住 Android 10。

## 不在本机执行的事项

这台机器没有完整 Android 构建环境，不在此环境构建 APK。后续由有完整 JDK / Android SDK / Gradle 环境的人执行构建和真机安装。

## 交接验证计划

1. 构建人员在完整开发环境执行 debug 构建。

2. 安装到 Pixel 1 / Android 10 后，先清 log：
   ```bash
   adb logcat -c
   ```

3. 启动 App，授予 Shizuku 权限。预期：
   - App 不闪退。
   - SIM 列表能显示中国联通 SIM。
   - 首页开关从 CarrierConfig 读回，不再全靠本地 SharedPreferences 默认值。

4. 验证 IMS 注册状态读取。关注日志：
   ```bash
   adb logcat -s ShizukuProvider MainViewModel ImsStatusReader ImsResetter ImsModifier TelephonyShellCommand ImsServiceSub
   ```
   预期：
   - 不再出现 `No interface method resetIms(I)V`。
   - `readImsRegistrationStatusViaBinder` 能输出 `subId=1 registered=...` 或明确错误。

5. 点击 IMS 注册状态开关。预期：
   - 先应用 VoLTE/VoWiFi 配置。
   - 再尝试 IMS 重启。
   - 不再弹出 `IMS重启失败：No interface method resetIms(I)V...`。

6. 点击 VoLTE / VoWiFi / VT / UT 开关后查看：
   ```bash
   adb shell dumpsys carrier_config
   ```
   预期：
   - 关闭时对应 `mOverrideConfigs` 项变为 `false`，不是保留旧的 `true`。
   - 开启时对应项为 `true`。

7. 如果 IMS 仍无法注册，但不再报接口错误，继续检查：
   - `adb logcat` 中 `ImsServiceSub: Request turn on/off IMS failed`
   - IMS APN 是否存在且类型为 `ims`
   - 运营商侧 VoLTE 是否开通
   - Qualcomm IMS 服务 `org.codeaurora.ims` 是否接受当前 SIM 的配置

## 残留风险

- 真机上执行 `cmd phone ims disable/enable -s 0` 时，Qualcomm IMS 日志出现过 `Request turn on/off IMS failed`。因此修复接口错误后，IMS 仍可能因基带/运营商/APN/IMS 服务拒绝而不能注册。
- `READ_PHONE_STATE` 当前设备上显示未授权。直接 Shizuku Binder 路径主要以 shell 身份执行，但普通 app 侧实时网络状态读取可能仍受影响。验收时可手动授予该权限，避免诊断页误判。
