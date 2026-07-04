# Pixel 1 / Pixel XL 中国 VoLTE 支持 Spec

## Why

[carrier-ims-for-pixel](https://github.com/ryfineZ/carrier-ims-for-pixel) 当前以 Tensor Pixel（Pixel 6+）为目标，`minSdk = 33`（Android 13），且依赖 `setCarrierTestOverride`（Android 12+）、`TelephonyFrameworkInitializer.getTelephonyServiceManager()`（Android 11+）、`KEY_VONR_*` / `KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING`（Android 14+）等系统 API，导致 Pixel 第一代（sailfish / marlin，Snapdragon 821，官方最终版本 Android 10）完全无法安装与运行。

而 Pixel 1 / Pixel XL 在中国大陆使用时，VoLTE 几乎是日常通话与上网并发的唯一可行路径，但设备出厂固件并不携带中国移动 / 联通 / 电信的 IMS 配置，用户也无从自行写入。需要在本项目基础上分叉一条**专用分支**，移除所有不适用 Pixel 1 的能力，并预置三家运营商的 VoLTE 配置文件，让普通用户在 Android 10 + Shizuku 环境下能一键启用 VoLTE。

## What Changes

### 构建与平台目标
- **BREAKING**：`minSdk` 由 `33` 降至 `29`（Android 10），`targetSdk` 维持 `33`（Android 13）以保留 `overrideConfig` 持久化分支可用性（仅当检测到时调用）。
- **BREAKING**：ABI 过滤器由 `arm64-v8a` 扩展为 `arm64-v8a` + `armeabi-v7a`（Pixel 1 为 arm64，但保留 32 位兜底以适配第三方 ROM）。
- 应用包名调整为 `io.github.vvb2060.ims.pixel1`，与上游 Tensor 版本共存。

### 代码降级与版本守护
- **MODIFIED** `ImsModifier`：移除 `Build.VERSION.SDK_INT >= UPSIDE_DOWN_CAKE` 守护块（VoNR、`KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING`）相关分支，因目标系统永不命中。
- **MODIFIED** `ImsModifier.applyCarrierTestMccOverride` / `clearCarrierTestOverride`：**整体删除**调用 `ITelephony.setCarrierTestOverride` 的逻辑（Android 12+ 才有，Pixel 1 上必然抛错）。MCC/MNC 覆盖改由 CarrierConfig 的 `country_mcc_override` 私有 key 完成。
- **MODIFIED** `ImsStatusReader` / `ImsResetter`：移除 `TelephonyFrameworkInitializer.getTelephonyServiceManager().getTelephonyServiceRegisterer().get()` 路径，统一回退为 `ServiceManager.getService(Context.TELEPHONY_SERVICE)` + `ITelephony.Stub.asInterface(ShizukuBinderWrapper(binder))`，与 `ImsModifier` 一致。
- **MODIFIED** `invokeOverrideConfig`：保留反射 + `NoSuchMethodException` 回退到两参版（Android 10 仅有两参版）。
- **MODIFIED** `Feature` 枚举：移除 `VONR`、`FIVE_G_NR`、`FIVE_G_THRESHOLDS`、`FIVE_G_PLUS_ICON`、`TIKTOK_NETWORK_FIX`、`SHOW_4G_FOR_LTE`（Pixel 1 无 5G，且 LTE 显示为 4G 在 Android 10 上由系统设置控制，App 干预无效）。保留 `VOLTE`、`VOWIFI`、`VT`、`UT`、`CROSS_SIM`、`CARRIER_NAME`、`COUNTRY_ISO`。

### 中国运营商 VoLTE 配置矩阵（新增）
预置三家运营商的 VoLTE 配置文件，按 SIM 的 MCC/MNC 自动匹配，用户也可手动选择：

| 运营商 | MCC | MNC | 数据 APN | IMS APN | 优先级 |
|---|---|---|---|---|---|
| 中国联通 (CUCC) | 460 | 01 | `wonet` | `ims` | 高（首发优先） |
| 中国移动 (CMCC) | 460 | 00 | `cmnet` | `ims` | 中 |
| 中国电信 (CTCC) | 460 | 11 | `ctnet` | `ims` | 中 |

每家配置文件包含：
- CarrierConfig 键值对（`KEY_CARRIER_VOLTE_AVAILABLE_BOOL`、`KEY_EDITABLE_ENHANCED_4G_LTE_BOOL`、`KEY_HIDE_ENHANCED_4G_LTE_BOOL`、`KEY_CARRIER_VT_AVAILABLE_BOOL`、`KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL`、`KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL` 等）；
- 推荐 APN 写入参数（名称、APN、类型、MCC、MNC、协议、承载系统）；
- 默认开关状态（VoLTE on / VT on / UT on / VoWiFi 可选）。

### VoWiFi 处理
- 保留 `VOWIFI` 开关与 `KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL` 等写入逻辑。
- 增加运行时检测：写入后回读 `ImsManager.isWfcEnabledByPlatform()`（Android 10 可用），若系统层不支持则静默将开关置灰并在日志中记录，不弹错、不阻塞主流程。

### UI 与资源
- 首页移除 5G / VoNR / 5G+ / 4G-for-LTE / TikTok 修复 / 国家码修改卡片。
- 新增"运营商配置"入口：列出联通 / 移动 / 电信三家预设，选中后展示待写入的 CarrierConfig 摘要与 APN 列表，确认后通过 `ImsModifier` 一次性写入。
- 应用名称、启动图、桌面图标与上游做视觉区分（如副标题标注 "Pixel 1 Edition"）。

### 文档
- **新增** `docs/PIXEL1_SUPPORTED_DEVICES.md`：列出 sailfish / marlin，标注必须 Android 10 + Shizuku，说明 Pixel 1 固件层的已知限制（详见"已知限制"）。
- **新增** `docs/CHINA_VOLTE_PROFILES.md`：完整列出三家运营商的 MCC/MNC、APN、CarrierConfig key 矩阵与默认值，附引用来源（CarrierConfigManager 官方常量）。
- **修改** `README.md`：徽章改为 `Platform-Android 10+`、`Device-Pixel 1 / Pixel XL`，新增独立"适用范围"段说明本分支与上游 Tensor 分支的差异。
- **修改** `CHANGELOG.md`：新增 4.0-pixel1 版本条目。

### 已知限制（写入文档，不视为缺陷）
- Pixel 1 / Pixel XL 的基带固件（特别是不在国行 ROM 上的海外固件）对 VoLTE 的支持因 carrier lock 而异。本分支通过 CarrierConfig 覆盖让系统"认为"VoLTE 可用，但**最终是否能成功注册 IMS** 仍取决于基带是否携带对应运营商的 IMS 配置文件。
- 中国电信（CTCC）因 SRLTE / 1x/EVDO 历史原因，在 Pixel 1 上即便写入配置也大概率无法注册 VoLTE，spec 中标记为"实验性支持"。
- VoWiFi 在 Pixel 1 上几乎不可能工作（缺 IPSec / IKEv2 carrier profile），开关保留但默认置灰。

## Impact

- **Affected specs**：本分支为独立 fork，不与上游 Tensor 分支共享 spec；后续若上游有 bugfix，需手工 cherry-pick `ImsModifier` / `ConfigReader` / `BrokerInstrumentation` 等通用类。
- **Affected code**：
  - 构建：[app/build.gradle.kts](file:///tmp/carrier-ims/app/build.gradle.kts)、[gradle/libs.versions.toml](file:///tmp/carrier-ims/gradle/libs.versions.toml)
  - 核心 Instrumentation：`ImsModifier.kt`、`ImsStatusReader.kt`、`ImsResetter.kt`、`BrokerInstrumentation.kt`、`ConfigReader.kt`、`CaptivePortalFixer.kt`、`ApnModifier.kt`
  - 模型与 UI：`Feature.kt`、`FeatureConfigMapper.kt`、`MainActivity.kt`、`MainViewModel.kt`、`QsTiles.kt`
  - 资源：`strings.xml`（移除 5G/TikTok 相关文案，新增运营商配置文案）、`AndroidManifest.xml`（移除 SIM2 IMS 状态 tile 可选）
  - 文档：`README.md`、`CHANGELOG.md`、新增 `docs/PIXEL1_SUPPORTED_DEVICES.md`、`docs/CHINA_VOLTE_PROFILES.md`
- **删除依赖**：`material-icons-extended` 可保留（图标仍在用），其他依赖不动。

## ADDED Requirements

### Requirement: Pixel 1 / Pixel XL 设备支持

系统 SHALL 在 Android 10 (API 29) 及以上的 Pixel 1 (sailfish) / Pixel XL (marlin) 上正常安装、启动并完成 Shizuku 授权。

#### Scenario: 安装与启动
- **WHEN** 用户在 Android 10 的 Pixel 1 上安装本分支 APK
- **THEN** 应用正常启动，无 `INSTALL_FAILED_OLDER_SDK` 错误
- **AND** Shizuku 授权后可读取到当前 SIM 信息

#### Scenario: 老旧 API 回退
- **WHEN** 应用在 Android 10 上调用 IMS 状态读取
- **THEN** 通过 `ServiceManager.getService(Context.TELEPHONY_SERVICE)` 获取 ITelephony binder
- **AND** 不调用 `TelephonyFrameworkInitializer`（该方法在 Android 10 上不可靠）
- **AND** `isImsRegistered(subId)` 正常返回布尔值

### Requirement: 中国联通 VoLTE 配置预设

系统 SHALL 提供中国联通（MCC=460, MNC=01）的预置 VoLTE 配置文件，作为本分支的"首发优先"运营商。

#### Scenario: 联通 SIM 自动匹配
- **WHEN** 用户插入联通 SIM 并授权 Shizuku
- **THEN** 应用自动识别 MCC=460 / MNC=01
- **AND** 在"运营商配置"页高亮推荐"中国联通"预设
- **AND** 用户确认后，`ImsModifier` 写入如下 CarrierConfig 至少包含：
  - `KEY_CARRIER_VOLTE_AVAILABLE_BOOL = true`
  - `KEY_EDITABLE_ENHANCED_4G_LTE_BOOL = true`
  - `KEY_HIDE_ENHANCED_4G_LTE_BOOL = false`
  - `KEY_CARRIER_VT_AVAILABLE_BOOL = true`
  - `KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL = true`
  - `KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL = true`（仅当用户开启 VoWiFi 时）
- **AND** 同时通过 `ApnModifier` 写入 IMS APN：名称=`IMS`、APN=`ims`、类型=`ims`、MCC=`460`、MNC=`01`、协议=`IPV4V6`、承载=`LTE`

#### Scenario: 写入回读校验
- **WHEN** 配置写入完成
- **THEN** 应用通过 `ConfigReader` 回读 `KEY_CARRIER_VOLTE_AVAILABLE_BOOL`
- **AND** 若回读值与写入值一致，提示用户"配置已生效，请重启 IMS 注册"
- **AND** 若不一致，提示"系统拒绝写入，可能是固件层不支持"，并写入失败日志

### Requirement: 中国移动 VoLTE 配置预设

系统 SHALL 提供中国移动（MCC=460, MNC=00）的预置 VoLTE 配置文件。

#### Scenario: 移动 SIM 应用预设
- **WHEN** 用户在移动 SIM 上选择"中国移动"预设并确认
- **THEN** 写入与联通一致的 VoLTE CarrierConfig key（值相同）
- **AND** 写入 IMS APN：名称=`IMS`、APN=`ims`、类型=`ims`、MCC=`460`、MNC=`00`
- **AND** 额外写入数据 APN：名称=`中国移动`、APN=`cmnet`、类型=`default,supl`、MCC=`460`、MNC=`00`

### Requirement: 中国电信 VoLTE 配置预设（实验性）

系统 SHALL 提供中国电信（MCC=460, MNC=11）的预置 VoLTE 配置文件，但 SHALL 在 UI 上明确标注"实验性支持"。

#### Scenario: 电信 SIM 应用预设
- **WHEN** 用户在电信 SIM 上选择"中国电信"预设
- **THEN** UI 弹出确认对话框，明确告知"Pixel 1 电信 VoLTE 受基带固件限制，可能无法注册 IMS，是否继续？"
- **AND** 用户确认后，写入 VoLTE CarrierConfig 与 IMS APN（APN=`ims`、MCC=460、MNC=11）
- **AND** 不写入 `cmnet` / `wonet` 等数据 APN（电信数据走 `ctnet`，由用户单独写入）

### Requirement: VoWiFi 容错

系统 SHALL 保留 VoWiFi 开关，但 SHALL 在系统不支持时静默降级，不阻塞主流程。

#### Scenario: VoWiFi 不支持时静默降级
- **WHEN** 用户在 Pixel 1 上开启 VoWiFi 开关并应用配置
- **AND** 系统层 `ImsManager.isWfcEnabledByPlatform()` 返回 false
- **THEN** 配置仍写入（保留用户意图）
- **AND** UI 将 VoWiFi 开关置灰，标注"当前固件不支持"
- **AND** 主流程继续执行，不弹错误对话框
- **AND** 在失败日志中记录 `VoWiFi platform disabled by firmware`

## MODIFIED Requirements

### Requirement: ImsModifier 配置写入

`ImsModifier` SHALL 在 Android 10 上通过 `overrideConfig(int, PersistableBundle)` 两参版本写入 CarrierConfig，反射调用失败时回退至两参版本，不再尝试三参持久化版本。

`ImsModifier` SHALL NOT 调用 `ITelephony.setCarrierTestOverride` 或 `clearCarrierTestOverride`，相关方法（`applyCarrierTestMccOverride`、`clearCarrierTestOverride`）SHALL 被整体删除。MCC/MNC 覆盖需求由 `BUNDLE_COUNTRY_MCC_OVERRIDE` 私有 key 通过 CarrierConfig 路径完成。

`ImsModifier` SHALL NOT 写入以下 Android 11+ 才存在的 CarrierConfig key：
- `KEY_VONR_ENABLED_BOOL`、`KEY_VONR_SETTING_VISIBILITY_BOOL`（Android 14）
- `KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING`（Android 14）
- `KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY`、`5g_icon_configuration_string`、`nr_advanced_threshold_bandwidth_khz_int` 等 5G 相关 key（Android 11/12，且 Pixel 1 无 5G 基带）

### Requirement: IMS 状态读取与重置

`ImsStatusReader` 与 `ImsResetter` SHALL 通过 `ServiceManager.getService(Context.TELEPHONY_SERVICE)` + `ShizukuBinderWrapper` + `ITelephony.Stub.asInterface` 获取 telephony 服务，SHALL NOT 使用 `TelephonyFrameworkInitializer.getTelephonyServiceManager()` 路径。

### Requirement: Feature 枚举精简

`Feature` 枚举 SHALL 仅保留以下成员：`CARRIER_NAME`、`COUNTRY_ISO`、`VOLTE`、`VOWIFI`、`VT`、`UT`、`CROSS_SIM`。

SHALL 移除：`VONR`、`FIVE_G_NR`、`FIVE_G_THRESHOLDS`、`FIVE_G_PLUS_ICON`、`SHOW_4G_FOR_LTE`、`TIKTOK_NETWORK_FIX`。

`FeatureConfigMapper` SHALL 同步移除上述已删除 Feature 的映射逻辑。

## REMOVED Requirements

### Requirement: TikTok 网络修复

**Reason**：依赖 `ITelephony.setCarrierTestOverride`（Android 12+），Pixel 1 上不可用；用户明确表示该功能"不那么需要"。
**Migration**：直接删除 `Feature.TIKTOK_NETWORK_FIX`、`ImsModifier.applyCarrierTestMccOverride` / `clearCarrierTestOverride` 全部代码与 UI 入口；不提供替代方案。

### Requirement: 5G NR / 5G+ 图标 / 5G 阈值 / VoNR

**Reason**：Pixel 1 / Pixel XL 为 Snapdragon 821 平台，无 5G 基带与射频，相关 CarrierConfig key 在 Android 10 上也不存在。
**Migration**：删除 `Feature.VONR`、`FIVE_G_NR`、`FIVE_G_THRESHOLDS`、`FIVE_G_PLUS_ICON` 及对应 UI 卡片；`ImsModifier.buildBundle` 中 5G 块整体移除。

### Requirement: LTE 显示为 4G

**Reason**：该开关通过 `show_4g_for_lte_data_icon_bool` 写入，Android 10 系统状态栏图标策略不读取该 key；Pixel 1 上无法生效。
**Migration**：删除 `Feature.SHOW_4G_FOR_LTE` 及 UI 入口；不提供替代方案（用户可使用第三方状态栏 mod）。

### Requirement: SIM_COUNTRY_ISO_OVERRIDE_STRING 修改

**Reason**：该 key 为 Android 14 (UPSIDE_DOWN_CAKE) 才有，Android 10 上写入无效。
**Migration**：删除 `Feature.COUNTRY_ISO` 写入路径中的该 key 调用；保留 `COUNTRY_ISO` 枚举仅用于展示当前 SIM 国家码（只读），不提供修改能力。
