# Tasks

## 阶段 0：分叉与构建配置

- [x] Task 1: 基于上游 carrier-ims-for-pixel 主分支创建独立 fork `carrier-ims-pixel1`，并切出专用分支 `pixel1-android10`
  - [x] SubTask 1.1: 在 fork 仓库根目录新增 `docs/PIXEL1_SUPPORTED_DEVICES.md` 占位
  - [x] SubTask 1.2: 调整应用包名为 `io.github.vvb2060.ims.pixel1`（在 `app/build.gradle.kts` 修改 `applicationIdName`）
  - [x] SubTask 1.3: 修改 `gradle/libs.versions.toml`：`android-minSdk = "29"`，保留 `android-compileSdk = "36"`、`android-targetSdk = "33"`
  - [x] SubTask 1.4: 修改 `app/build.gradle.kts` 的 `ndk.abiFilters`，增加 `armeabi-v7a`
  - [x] SubTask 1.5: 同步修改 `app/src/main/AndroidManifest.xml` 中 `${applicationId}` 相关 authority（ShizukuProvider、FileProvider），无硬编码包名改动

- [x] Task 2: 调整签名与版本号
  - [x] SubTask 2.1: 在 `signing.gradle` 中保留原签名逻辑，但 versionName 改为 `4.0.0-pixel1`
  - [x] SubTask 2.2: 在 `libs.versions.toml` 修改 `app-version = "4.0.0-pixel1"`

## 阶段 1：代码降级与版本守护

- [x] Task 3: 精简 `Feature` 枚举与 `FeatureConfigMapper`
  - [x] SubTask 3.1: 在 `Feature.kt` 删除 `VONR`、`FIVE_G_NR`、`FIVE_G_THRESHOLDS`、`FIVE_G_PLUS_ICON`、`SHOW_4G_FOR_LTE`、`TIKTOK_NETWORK_FIX` 枚举成员
  - [x] SubTask 3.2: 在 `FeatureConfigMapper.kt` 同步删除上述枚举的映射逻辑
  - [x] SubTask 3.3: 在 `strings.xml` 中删除上述功能对应的 `volte_desc` / `vonr` / `_5g_*` / `tiktok_*` / `show_4g_for_lte*` 文案（保留 `volte` / `vowifi` / `vt` / `ut` / `cross_sim` / `carrier_name` / `country_iso`）
  - [x] SubTask 3.4: 在 `MainViewModel.kt` / `MainActivity.kt` 中移除对已删除 Feature 的引用

- [x] Task 4: 改造 `ImsModifier.kt` 适配 Android 10
  - [x] SubTask 4.1: 删除 `Build.VERSION.SDK_INT >= UPSIDE_DOWN_CAKE` 守护块（VoNR、`KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING`），改为直接跳过这些 key
  - [x] SubTask 4.2: 删除 `applyCarrierTestMccOverride` 与 `clearCarrierTestOverride` 两个方法整体
  - [x] SubTask 4.3: 删除 `ImsModifier.buildBundle` 中 5G NR / 5G+ / 5G 阈值整块逻辑（约 [ImsModifier.kt#L145-L179](file:///tmp/carrier-ims/app/src/main/java/io/github/vvb2060/ims/privileged/ImsModifier.kt)）
  - [x] SubTask 4.4: 删除 `buildBundle` 中 `enableShow4GForLTE` 参数与 `show_4g_for_lte_data_icon_bool` 写入
  - [x] SubTask 4.5: 保留 `invokeOverrideConfig` 反射 + `NoSuchMethodException` 回退到两参版的逻辑（Android 10 仅有两参版）
  - [x] SubTask 4.6: `overrideConfig` 中移除对 `BUNDLE_COUNTRY_MCC_OVERRIDE` / `BUNDLE_COUNTRY_MNC_HINT` 走 `setCarrierTestOverride` 的分支，改为通过 CarrierConfig 私有 key 写入

- [x] Task 5: 改造 `ImsStatusReader.kt` 与 `ImsResetter.kt`
  - [x] SubTask 5.1: 在 `ImsStatusReader.kt` 删除 `TelephonyFrameworkInitializer` 路径，改为 `ServiceManager.getService(Context.TELEPHONY_SERVICE)` + `ShizukuBinderWrapper` + `ITelephony.Stub.asInterface`
  - [x] SubTask 5.2: 在 `ImsResetter.kt` 同样改造 ITelephony 与 ISub 获取路径：`ISub` 改用 `ServiceManager.getService("isub")` 或反射 `TelephonyFrameworkInitializer` 失败时回退（需调研 Android 10 是否有 `isub` service 名，若无则用 `ITelephony` 路径获取 slotIndex）
  - [x] SubTask 5.3: 增加 null 兜底：binder 为 null 时返回带 `BUNDLE_RESULT_MSG` 的失败结果，不抛 NPE

- [x] Task 6: 改造 `BrokerInstrumentation.kt` 与 `ConfigReader.kt`
  - [x] SubTask 6.1: `BrokerInstrumentation.kt` 检查并确认无 Android 11+ API 调用（已与 ImsModifier 一致，应无需改动，验证即可）
  - [x] SubTask 6.2: `ConfigReader.kt` 检查 `cm.getConfigForSubId(subId)` 在 Android 10 上是否需要 `READ_PHONE_STATE` 或 shell 委托权限；确认现有 `startDelegateShellPermissionIdentity` 路径已覆盖

- [x] Task 7: 改造 `CaptivePortalFixer.kt` 与 `ApnModifier.kt`
  - [x] SubTask 7.1: `CaptivePortalFixer.kt` 验证 `Settings.Global.putString` 在 Android 10 + shell 委托权限下可用
  - [x] SubTask 7.2: `ApnModifier.kt` 验证 `insertApn` / `updateApn` 走的 `TelephonyProvider` URI 在 Android 10 上路径一致（`content://telephony/carriers`），无 Android 11+ 改动

## 阶段 2：中国运营商配置矩阵

- [x] Task 8: 新增 `CarrierProfile` 数据模型与预设注册表
  - [x] SubTask 8.1: 在 `app/src/main/java/io/github/vvb2060/ims/model/` 新增 `CarrierProfile.kt`，定义 `data class CarrierProfile(val name: String, val mcc: String, val mnc: String, val dataApn: ApnDraftConfig, val imsApn: ApnDraftConfig, val carrierConfigKeys: Map<String, Any>, val experimental: Boolean = false)`
  - [x] SubTask 8.2: 新增 `CarrierProfileRegistry.kt`，预置三家运营商：
    - 中国联通：MCC=460, MNC=01, dataApn=wonet, imsApn=ims, experimental=false
    - 中国移动：MCC=460, MNC=00, dataApn=cmnet, imsApn=ims, experimental=false
    - 中国电信：MCC=460, MNC=11, dataApn=ctnet, imsApn=ims, experimental=true
  - [x] SubTask 8.3: 在 `CarrierProfileRegistry` 提供 `match(mcc: String, mnc: String): CarrierProfile?` 方法供自动匹配

- [x] Task 9: 在 `ImsModifier.buildBundle` 中支持基于 `CarrierProfile` 写入
  - [x] SubTask 9.1: 新增重载 `buildBundle(profile: CarrierProfile, enableVoLTE: Boolean, enableVoWiFi: Boolean, enableVT: Boolean, enableUT: Boolean): Bundle`，遍历 `profile.carrierConfigKeys` 注入到 Bundle
  - [x] SubTask 9.2: 保留原 `buildBundle(...)` 作为底层入口，新重载内部调用原 `buildBundle`
  - [x] SubTask 9.3: 在 `MainViewModel` 中，"应用预设"按钮触发新重载

- [x] Task 10: 在 `ApnModifier` 中支持按 `CarrierProfile` 写入数据 APN + IMS APN
  - [x] SubTask 10.1: 新增方法 `applyProfile(profile: CarrierProfile, subId: Int): Boolean`，依次写入 `profile.dataApn` 与 `profile.imsApn`
  - [x] SubTask 10.2: 写入前查重，若已有相同 APN 则跳过
  - [x] SubTask 10.3: 写入后回读校验，失败时返回 false 并记录日志

- [x] Task 11: 新增 VoWiFi 容错检测
  - [x] SubTask 11.1: 在 `ImsStatusReader` 中新增方法 `isWfcEnabledByPlatform(subId: Int): Boolean`，通过 `ImsManager` 反射调用（Android 10 可用）
  - [x] SubTask 11.2: 在 `MainViewModel` 中，应用配置后调用该方法，若返回 false 则将 `Feature.VOWIFI` 开关置灰并在日志中记录
  - [x] SubTask 11.3: UI 置灰时不弹错误对话框，仅显示"当前固件不支持"文案

## 阶段 3：UI 改造

- [x] Task 12: 重构首页 UI 移除不支持能力
  - [x] SubTask 12.1: 在 `MainActivity.kt` 中移除 5G / VoNR / 5G+ / 4G-for-LTE / TikTok 修复 / 国家码修改卡片
  - [x] SubTask 12.2: 保留 VoLTE / VoWiFi / VT / UT / Cross-SIM 开关卡片
  - [x] SubTask 12.3: 新增"运营商配置"入口卡片，点击后跳转到 `CarrierProfileActivity`（新 Activity）

- [x] Task 13: 新增 `CarrierProfileActivity` 与对应 ViewModel
  - [x] SubTask 13.1: 在 `app/src/main/java/io/github/vvb2060/ims/ui/` 新增 `CarrierProfileActivity.kt` 与 `CarrierProfileViewModel.kt`
  - [x] SubTask 13.2: UI 列出三家运营商预设，按 SIM 自动匹配项高亮
  - [x] SubTask 13.3: 选中后展示待写入的 CarrierConfig 摘要与 APN 列表（只读）
  - [x] SubTask 13.4: "应用"按钮调用 `MainViewModel.applyProfile(profile)`，触发 `ImsModifier` + `ApnModifier` 写入
  - [x] SubTask 13.5: 中国电信预设选中时弹出实验性警告对话框，需用户二次确认
  - [x] SubTask 13.6: 写入完成后展示 `ConfigReader` 回读校验结果

- [x] Task 14: 调整 `AndroidManifest.xml` 与 `QsTiles`
  - [x] SubTask 14.1: 评估是否保留 SIM2 VoLTE / IMS 状态 tile（Pixel 1 单卡，可删除 SIM2 tile 简化）
  - [x] SubTask 14.2: 在 `strings.xml` 中新增 `carrier_profile_title`、`carrier_profile_apply`、`carrier_profile_experimental_warning`、`carrier_profile_apply_success`、`carrier_profile_apply_failed` 等文案
  - [x] SubTask 14.3: 在 `AndroidManifest.xml` 注册 `CarrierProfileActivity`

## 阶段 4：文档与发布

- [x] Task 15: 撰写 `docs/PIXEL1_SUPPORTED_DEVICES.md`
  - [x] SubTask 15.1: 列出 sailfish (Pixel 1) / marlin (Pixel XL) 设备代号
  - [x] SubTask 15.2: 标注系统要求 Android 10（QQ3A.200805.001 最终版或后续 AOSP based ROM）
  - [x] SubTask 15.3: 标注 Shizuku 启动方式（adb 激活或 root 激活）
  - [x] SubTask 15.4: 列出已知限制：基带固件 carrier lock、电信实验性、VoWiFi 几乎不可用

- [x] Task 16: 撰写 `docs/CHINA_VOLTE_PROFILES.md`
  - [x] SubTask 16.1: 完整列出三家运营商的 MCC / MNC / 数据 APN / IMS APN 矩阵
  - [x] SubTask 16.2: 列出每个运营商使用的 CarrierConfig key 与默认值，附 `CarrierConfigManager` 官方常量引用
  - [x] SubTask 16.3: 说明配置写入路径（`overrideConfig` 反射调用）与回读校验流程
  - [x] SubTask 16.4: 附排障清单：写不入 / 写入但 IMS 不注册 / 重启后失效 等场景的处理建议

- [x] Task 17: 更新 `README.md` 与 `CHANGELOG.md`
  - [x] SubTask 17.1: 在 `README.md` 修改徽章为 `Platform-Android 10+`、`Device-Pixel 1 / Pixel XL`，新增"适用范围"段说明与上游 Tensor 分支的差异
  - [x] SubTask 17.2: 在 `README.md` 新增"分叉说明"段，明确本分支基于 `carrier-ims-for-pixel`，移除了 5G / VoNR / TikTok 等不适用能力
  - [x] SubTask 17.3: 在 `CHANGELOG.md` 新增 `4.0.0-pixel1 (2026-07-XX)` 条目，列明新增、删除、修改项
  - [x] SubTask 17.4: 删除 `docs/plans/` 中与 5G / VoNR / UI 重设计相关的上游 plan 文档（避免误导）

## 阶段 5：验证

- [x] Task 18: 单元测试与构建验证
  - [x] SubTask 18.1: 在 `app/src/test/java/io/github/vvb2060/ims/model/` 新增 `CarrierProfileRegistryTest.kt`，验证三家运营商的匹配逻辑
  - [x] SubTask 18.2: 修改 `SupportRulesTest.kt` 中引用已删除 `Feature` 的用例（若有），确保编译通过
  - [x] SubTask 18.3: 运行 `./gradlew :app:assembleDebug` 验证构建成功
  - [x] SubTask 18.4: 运行 `./gradlew :app:test` 验证单元测试通过

- [ ] Task 19: 真机验证（需用户提供设备）
  - [ ] SubTask 19.1: 在 Pixel 1 (Android 10) + 联通 SIM 上验证 VoLTE 配置写入与 IMS 注册
  - [ ] SubTask 19.2: 在 Pixel 1 (Android 10) + 移动 SIM 上验证 VoLTE 配置写入与 IMS 注册
  - [ ] SubTask 19.3: 在 Pixel 1 (Android 10) + 电信 SIM 上验证实验性警告对话框与配置写入（不要求 IMS 注册成功）
  - [ ] SubTask 19.4: 在 Pixel XL (Android 10) 上重复上述三家运营商验证

# Task Dependencies

- Task 2 依赖 Task 1（包名与分支需先就位）
- Task 3 → Task 4 → Task 5 / Task 6 / Task 7（Feature 枚举先精简，ImsModifier 改造依赖 Feature 已删，其他 Instrumentation 改造可与 ImsModifier 并行）
- Task 8 → Task 9 → Task 10（CarrierProfile 模型先行，ImsModifier 与 ApnModifier 适配依赖模型）
- Task 11 依赖 Task 5（VoWiFi 容错检测复用 ImsStatusReader 改造结果）
- Task 12 / Task 13 依赖 Task 8 / Task 9 / Task 10（UI 入口需调用已就绪的 profile 应用链路）
- Task 14 依赖 Task 13（QsTiles 与 Manifest 调整需配合新 Activity）
- Task 15 / Task 16 / Task 17 可在 Task 8 完成后并行启动
- Task 18 依赖 Task 4 / Task 5 / Task 8 / Task 9 / Task 10（核心代码改造完成才能编译）
- Task 19 依赖 Task 18（构建通过后才能装机验证）
