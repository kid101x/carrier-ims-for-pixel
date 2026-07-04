# Tasks

- [x] Task 1: 新增 ICarrierConfigLoader.aidl stub
  - [x] SubTask 1.1: 在 `/workspace/stub/src/main/aidl/com/android/internal/telephony/ICarrierConfigLoader.aidl` 创建文件，含 `getConfigForSubId(int)` 与三参 `overrideConfig(int, in PersistableBundle, boolean)`
  - [x] SubTask 1.2: 验证 stub 模块编译通过：`:stub:compileDebugAidl` BUILD SUCCESSFUL

- [x] Task 2: ShizukuProvider 改用 ICarrierConfigLoader stub
  - [x] SubTask 2.1: 新增 import `com.android.internal.telephony.ICarrierConfigLoader`（PersistableBundle 已有）
  - [x] SubTask 2.2: `getICarrierConfigViaShizuku()` 返回 `ICarrierConfigLoader?`，用 `Stub.asInterface(ShizukuBinderWrapper(binder))`，删除 Class.forName
  - [x] SubTask 2.3: `invokeOverrideConfigViaBinder` 先试三参 stub 调用，`NoSuchMethodError` 回退两参反射
  - [x] SubTask 2.4: `readCarrierConfigViaBinder` 直接调 stub `getConfigForSubId(subId)`
  - [x] SubTask 2.5: `dumpCarrierConfigViaBinder` 同样改用 stub `getConfigForSubId`
  - [x] SubTask 2.6: 三处 `getICarrierConfigViaShizuku()` 调用类型推断自动从 `Any?` 变为 `ICarrierConfigLoader?`，无需强转

- [x] Task 3: 增强错误消息可诊断
  - [x] SubTask 3.1: 所有 10 处 ViaBinder catch 块错误消息统一为 `${t.javaClass.simpleName}: ${t.message ?: "(no message)"}`
  - [x] SubTask 3.2: `Log.e(TAG, "xxxViaBinder: failed: ...", t)` 仍记录完整堆栈

- [x] Task 4: 构建与验证
  - [x] SubTask 4.1: `:app:assembleDebug :app:testDebugUnitTest` 构建成功且 SimResultBundleTest 单测全绿
  - [x] SubTask 4.2: APK 输出 `app-4.0.0-pixel1-b9-20260704-1142.apk`（带版本+时间戳）

# Task Dependencies
- Task 1 是基础（stub 先有），独立可做
- Task 2 依赖 Task 1（用 stub 类型）
- Task 3 独立，可与 Task 2 并行
- Task 4 依赖 Task 1–3 全部完成
