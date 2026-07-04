# Tasks

- [x] Task 1: 修复 ISub 方法签名三分支（核心修复）
  - [x] SubTask 1.1: `SimReader.readByISub` 改三分支：`>= UPSIDE_DOWN_CAKE` 三参、`>= R` 两参（反射，stub 不支持重载）、`< R` 一参反射
  - [x] SubTask 1.2: `ShizukuProvider.readSimInfoListViaBinder` 同步改三分支（加 context 参数）
  - [x] SubTask 1.3: `callingPackage` 参数传 `context.packageName`（非 null），Android 10 一参反射调用

- [x] Task 2: 声明 READ_PHONE_STATE 权限（兜底）
  - [x] SubTask 2.1: `AndroidManifest.xml` 添加 `<uses-permission android:name="android.permission.READ_PHONE_STATE" />`

- [x] Task 3: 增强 Binder 路径日志（便于真机诊断）
  - [x] SubTask 3.1: `readSimInfoListViaBinder` catch 块日志增强为 `failed: ${t.javaClass.name}: ${t.message}`，其余日志点保留

- [x] Task 4: 屏蔽调试期浮窗广告
  - [x] SubTask 4.1: `MainActivity.LaunchedEffect(Unit)` 跳过 `fetchCommercialAds` / `homeAdToShow` 计算（注释掉，不发起网络请求）
  - [x] SubTask 4.2: `CommercialAdDialog` 渲染分支因 `homeAdToShow` 恒为 null 而不执行
  - [x] SubTask 4.3: 保留 `CommercialAd` 数据模型与 `COOPERATION_CARD` 代码不删；不修改 `adFreeEnabled` 真实判定逻辑

- [x] Task 5: 构建与验证
  - [x] SubTask 5.1: `./gradlew :app:assembleDebug` 构建成功
  - [x] SubTask 5.2: 运行 `SimResultBundleTest` 单测全绿（无回归）
  - [x] SubTask 5.3: 产出 `app-debug.apk`（60M）供真机测试

# Task Dependencies
- Task 1 是核心，独立可做
- Task 2 独立
- Task 3 依赖 Task 1（在三分支代码基础上加日志）
- Task 4 独立，可与 Task 1–3 并行
- Task 5 依赖 Task 1–4 全部完成

# 偏离说明
- 两参签名本计划"直接调 stub，无需反射"，但 AIDL 不允许同名方法重载，stub 只编了三参。两参改用反射调用，运行时行为一致（均落到框架 ISub 的两参实现 + 正确事务码）。
