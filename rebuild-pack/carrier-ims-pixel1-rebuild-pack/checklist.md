# Checklist

## 构建与平台目标
- [x] `gradle/libs.versions.toml` 中 `android-minSdk` 已改为 `"29"`
- [x] `app/build.gradle.kts` 中 `applicationIdName` 已改为 `io.github.vvb2060.ims.pixel1`
- [x] `app/build.gradle.kts` 中 `ndk.abiFilters` 已包含 `arm64-v8a` 与 `armeabi-v7a`
- [x] `app-version` 已改为 `4.0.0-pixel1`
- [x] APK 可在 Android 10 (API 29) 模拟器或真机上成功安装（无 `INSTALL_FAILED_OLDER_SDK`）

## 代码降级与版本守护
- [x] `Feature.kt` 中 `VONR` / `FIVE_G_NR` / `FIVE_G_THRESHOLDS` / `FIVE_G_PLUS_ICON` / `SHOW_4G_FOR_LTE` / `TIKTOK_NETWORK_FIX` 枚举成员已删除
- [x] `FeatureConfigMapper.kt` 中对上述枚举的映射逻辑已删除
- [x] `ImsModifier.kt` 中 `Build.VERSION.SDK_INT >= UPSIDE_DOWN_CAKE` 守护块已删除（VoNR、`KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING` 分支直接跳过）
- [x] `ImsModifier.kt` 中 `applyCarrierTestMccOverride` 与 `clearCarrierTestOverride` 方法已整体删除
- [x] `ImsModifier.kt` 中 `buildBundle` 的 5G NR / 5G+ / 5G 阈值逻辑块已删除
- [x] `ImsModifier.kt` 中 `invokeOverrideConfig` 保留反射 + `NoSuchMethodException` 回退到两参版逻辑
- [x] `ImsStatusReader.kt` 中 `TelephonyFrameworkInitializer` 路径已替换为 `ServiceManager.getService(Context.TELEPHONY_SERVICE)`
- [x] `ImsResetter.kt` 中 ITelephony 与 ISub 获取路径已替换为 `ServiceManager` 路径或带兜底
- [x] `ImsStatusReader.kt` / `ImsResetter.kt` 在 binder 为 null 时返回带 `BUNDLE_RESULT_MSG` 的失败结果，不抛 NPE
- [x] 全项目无 `setCarrierTestOverride` / `clearCarrierTestOverride` / `TelephonyFrameworkInitializer` 调用残留（Grep 验证）

## 中国运营商配置矩阵
- [x] `CarrierProfile.kt` 数据模型已定义，字段包含 `name` / `mcc` / `mnc` / `dataApn` / `imsApn` / `carrierConfigKeys` / `experimental`
- [x] `CarrierProfileRegistry.kt` 已预置中国联通（460/01, wonet, 非实验性）
- [x] `CarrierProfileRegistry.kt` 已预置中国移动（460/00, cmnet, 非实验性）
- [x] `CarrierProfileRegistry.kt` 已预置中国电信（460/11, ctnet, 实验性）
- [x] `CarrierProfileRegistry.match("460", "01")` 返回联通预设
- [x] `CarrierProfileRegistry.match("460", "00")` 返回移动预设
- [x] `CarrierProfileRegistry.match("460", "11")` 返回电信预设
- [x] `ImsModifier.buildBundle` 已新增基于 `CarrierProfile` 的重载，遍历 `carrierConfigKeys` 注入 Bundle
- [x] `ApnModifier` 已新增 `applyProfile(profile, subId)` 方法，写入数据 APN + IMS APN 并回读校验
- [x] 三家预设的 CarrierConfig key 至少包含 `KEY_CARRIER_VOLTE_AVAILABLE_BOOL` / `KEY_EDITABLE_ENHANCED_4G_LTE_BOOL` / `KEY_HIDE_ENHANCED_4G_LTE_BOOL` / `KEY_CARRIER_VT_AVAILABLE_BOOL` / `KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL`

## VoWiFi 容错
- [x] `ImsStatusReader` 已新增 `isWfcEnabledByPlatform(subId)` 方法，通过 `ImsManager` 反射调用
- [x] `MainViewModel` 在应用配置后调用该方法，返回 false 时将 `Feature.VOWIFI` 开关置灰
- [x] UI 置灰时不弹错误对话框，仅显示"当前固件不支持"文案
- [x] 失败日志中记录 `VoWiFi platform disabled by firmware`

## UI 改造
- [x] 首页已移除 5G / VoNR / 5G+ / 4G-for-LTE / TikTok 修复 / 国家码修改卡片
- [x] 首页保留 VoLTE / VoWiFi / VT / UT / Cross-SIM 开关卡片
- [x] 首页新增"运营商配置"入口卡片，点击跳转 `CarrierProfileActivity`
- [x] `CarrierProfileActivity` 列出三家运营商预设，按 SIM 自动匹配项高亮
- [x] 选中预设后展示待写入的 CarrierConfig 摘要与 APN 列表（只读）
- [x] "应用"按钮调用 `MainViewModel.applyProfile(profile)`，触发 `ImsModifier` + `ApnModifier` 写入
- [x] 中国电信预设选中时弹出实验性警告对话框，需用户二次确认
- [x] 写入完成后展示 `ConfigReader` 回读校验结果
- [x] `AndroidManifest.xml` 已注册 `CarrierProfileActivity`
- [x] `strings.xml` 已新增 `carrier_profile_*` 相关文案

## 文档
- [x] `docs/PIXEL1_SUPPORTED_DEVICES.md` 已撰写，包含设备代号、系统要求、Shizuku 启动方式、已知限制
- [x] `docs/CHINA_VOLTE_PROFILES.md` 已撰写，包含三家运营商 MCC/MNC/APN 矩阵、CarrierConfig key 列表、写入路径、排障清单
- [x] `README.md` 徽章已改为 `Platform-Android 10+` / `Device-Pixel 1 / Pixel XL`
- [x] `README.md` 新增"分叉说明"段，明确与上游 Tensor 分支的差异
- [x] `CHANGELOG.md` 新增 `4.0.0-pixel1` 条目
- [x] `docs/plans/` 中与 5G / VoNR / UI 重设计相关的上游 plan 文档已删除或归档

## 验证
- [x] `CarrierProfileRegistryTest.kt` 单元测试通过，覆盖三家运营商匹配逻辑
- [x] 修改后的 `SupportRulesTest.kt` 编译通过，无引用已删除 `Feature` 的残留
- [x] `./gradlew :app:assembleDebug` 构建成功
- [x] `./gradlew :app:test` 单元测试全部通过
- [x] 全项目 Grep 验证：无 `setCarrierTestOverride` / `TelephonyFrameworkInitializer` / `KEY_VONR` / `5g_icon_configuration` 残留调用
- [ ] 真机验证：Pixel 1 (Android 10) + 联通 SIM 可写入配置且 IMS 注册成功（若有条件）
- [ ] 真机验证：Pixel 1 (Android 10) + 移动 SIM 可写入配置且 IMS 注册成功（若有条件）
- [ ] 真机验证：Pixel 1 (Android 10) + 电信 SIM 实验性警告对话框正常弹出，配置可写入（不要求 IMS 注册成功，若有条件）
- [ ] 真机验证：Pixel XL (Android 10) 重复上述三家运营商验证（若有条件）
