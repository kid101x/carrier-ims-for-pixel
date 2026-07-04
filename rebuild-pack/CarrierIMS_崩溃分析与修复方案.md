# Carrier IMS (io.github.vvb2060.ims.pixel1) 崩溃分析与修复方案

> 面向修复的开发者。包含:根因、证据、修复代码、以及**无需真机**的离线验证方案。

## 0. 环境

- App: `io.github.vvb2060.ims.pixel1`,versionName `4.0.0-pixel1.d1.0ea26079`,versionCode 1
- minSdk 29 / targetSdk 33,DEBUGGABLE,arm64-v8a
- 复现设备:Pixel XL (marlin),**Android 10**,build `google/marlin/marlin:10/QP1A.191005.007.A3`
- 依赖 Shizuku(`moe.shizuku.privileged.api`)获取 shell 权限执行特权操作
- 关键类:`io.github.vvb2060.ims.privileged.SimReader`(以 instrumentation 形式经 Shizuku 特权运行)

## 1. 现象

用户视角:打开 App 后 UI 一闪即退、拿不到 SIM/IMS 信息,像"崩溃"。
`dropbox` / `logcat -b crash` **没有任何该包的崩溃栈** —— 因为它不是普通的未捕获异常,而是下述机制导致的功能失败 + 进程被系统强杀。

## 2. 根因(已实测复现)

App 用经典的「Shizuku + 自我 instrumentation」手法运行特权代码:UI 进程经 Shizuku 调
`IActivityManager.startInstrumentation(SimReader, ...)`。执行链:

1. `startInstrumentation` 会 **force-stop 目标包**,于是 UI 进程被 `signal 9 (Killed)` —— 这是"UI 一闪就没"的直接原因(该手法固有行为)。
2. 系统新起进程运行 `SimReader`。它先尝试
   `ISub.getActiveSubscriptionInfoList(String, String, boolean)`(Android 12+ 三参签名),
   在 Android 10 上不存在 → `NoSuchMethodError`,**但已被 catch 并回退** `SubscriptionManager`,回退成功读到 `size: 1`。此项非致命。
3. **致命点**:`SimReader` 把读到的 `SubscriptionInfo` 放进结果 Bundle,在 `SimReader.kt:51`
   调 `Instrumentation.finish(resultCode, results)` 回传。
   Android 10 的 `SubscriptionInfo` 携带图标 `Bitmap`(`mIconBitmap`),其 parcel 编码使用
   **ashmem 文件描述符(FD)**;而 instrumentation 结果在 AMS 侧以 `allowFds=false` 序列化,
   **禁止携带 FD** → 抛 `IllegalArgumentException: File descriptors passed in Intent`
   → `finishInstrumentation` 失败 → 宿主拿到 `results=Bundle[EMPTY_PARCEL]` → 整个读取失败。

> 为什么只在老设备复现:新版 Android 的 `SubscriptionInfo` 图标处理已改,不再随 parcel 带 FD;
> Android 10/11 才会命中。

## 3. 关键日志证据

```
SimReader: read sim info list size: 1                     # SIM 已通过回退读到
SimReader: failed to read sim info list
SimReader: java.lang.IllegalArgumentException: File descriptors passed in Intent
    at android.app.IActivityManager$Stub$Proxy.finishInstrumentation(IActivityManager.java:5496)
    at android.app.Instrumentation.finish(Instrumentation.java:252)
    at io.github.vvb2060.ims.privileged.SimReader.start(SimReader.kt:51)
    at io.github.vvb2060.ims.privileged.SimReader.onCreate(SimReader.kt:24)
  Caused by: android.os.RemoteException: Remote stack trace:
    at com.android.server.am.ActivityManagerService.finishInstrumentation(ActivityManagerService.java:15905)
ActivityManager: Force stopping ... : finished inst
ActivityManager: ... results=Bundle[EMPTY_PARCEL]
```

次要(非致命,仅告警):
```
SimReader: readByISub failed, fallback to SubscriptionManager
java.lang.NoSuchMethodError: No interface method
  getActiveSubscriptionInfoList(Ljava/lang/String;Ljava/lang/String;Z)Ljava/util/List; in class Lcom/android/internal/telephony/ISub;
    at SimReader.readByISub(SimReader.kt:71)
# 以及 A10 hidden-API 拦截:
Accessing hidden method ...startDelegateShellPermissionIdentity... (blacklist, linking, denied)
```

## 4. 修复方案

### 4.1 主修复(必须):结果 Bundle 不得携带 FD

`SimReader` 回传结果时,**不要**把原始 `SubscriptionInfo`(带图标 Bitmap → FD)放进
`Instrumentation.finish()` 的结果 Bundle。三选一,推荐 A。

**方案 A(推荐,跨版本最稳):只回传原始字段(基本类型),不传 Parcelable**

```kotlin
// SimReader.kt —— 把结果构造抽成纯函数,便于离线单测(见第 5 节)
fun buildResultBundle(list: List<SubscriptionInfo>): Bundle {
    val b = Bundle()
    b.putInt("count", list.size)
    list.forEachIndexed { i, info ->
        b.putInt("subId_$i", info.subscriptionId)
        b.putInt("slot_$i", info.simSlotIndex)
        b.putInt("carrierId_$i", info.carrierId)
        b.putString("iccId_$i", info.iccId)
        b.putString("mcc_$i", info.mccString)   // API 29+
        b.putString("mnc_$i", info.mncString)
        b.putString("name_$i", info.displayName?.toString())
        b.putString("number_$i", info.number)
        // …仅放 UI 真正需要的字段;绝不放 SubscriptionInfo / Bitmap / 任何带 FD 的对象
    }
    return b
}
// finish 前自检 + 兜底,避免再次静默失败
val result = buildResultBundle(list)
check(!result.hasFileDescriptors()) { "result bundle must not contain FDs" }
finish(Activity.RESULT_OK, result)
```
宿主侧按同样的 key 规则重建所需数据。

**方案 B(改动最小):反射置空图标后再传 `SubscriptionInfo`**
```kotlin
val iconField = SubscriptionInfo::class.java.getDeclaredField("mIconBitmap")
    .apply { isAccessible = true }
list.forEach { iconField.set(it, null) }   // 去掉 FD 来源
result.putParcelableArrayList("sim_info", ArrayList(list))
```
缺点:依赖隐藏字段名 `mIconBitmap`(不同版本可能变),不如 A 稳。

**方案 C(彻底但改动大):不经结果 Bundle 回传,改用文件/`ParcelFileDescriptor`/AIDL 回调**
(在 Bundle 里放 `IBinder` 是允许的,FD 才被禁);数据量大或以后要传更多内容时可选。

### 4.2 兜底修复(强烈建议):`finish` 失败要"可见"而非静默

当前把异常 catch 成一条日志,UI 侧只拿到 `EMPTY_PARCEL`,用户看不到任何原因。
应在 `SimReader.start()` 的 `finish` 处 try/catch,把可读错误写入结果 Bundle 的
`error` 字段(或落地到 app 私有文件),让 UI 显示"读取失败:<原因>"。

### 4.3 清理项(非致命,可选)

- `readByISub` 里的 `getActiveSubscriptionInfoList(pkg, featureId, boolean)` 三参签名仅 Android 12+ 有;
  可按 `Build.VERSION.SDK_INT` 分支选择签名,减少 A10/A11 上的 `NoSuchMethodError` 噪声(逻辑已能回退,属优化)。
- A10 上 `startDelegateShellPermissionIdentity` 被 hidden-API 黑名单拦截;instrumentation 本身已带 shell 权限,
  可在 `SDK_INT <= 29` 时跳过该调用,避免无谓的 denied 告警。

## 5. 离线验证方案(无需真机 / 无需模拟器)

目标:在纯 JVM 上确定性地验证 (a) 旧逻辑会因 FD 失败、(b) 新逻辑不再带 FD 且能通过
`allowFds=false` 的序列化。用 **Robolectric**(JVM 跑 Android 框架,不接设备)。

### 5.1 前置:让结果构造可单测

把结果 Bundle 的构造抽成纯函数 `buildResultBundle(...)`(见 4.1 方案 A),
与 Shizuku / instrumentation / telephony 解耦。

### 5.2 测试用例

```kotlin
// build.gradle: testImplementation "org.robolectric:robolectric:4.x"
//               testImplementation "junit:junit:4.13.2"
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])   // 对齐 Android 10
class SimResultBundleTest {

    /** 复现 bug 的前置条件:含 FD 的 Bundle 在 allowFds=false 下写 parcel 必失败 */
    @Test fun bundleWithFd_failsUnderInstrumentationConstraint() {
        val pipe = ParcelFileDescriptor.createPipe()        // 造一个真实 FD,无需设备
        val bad = Bundle().apply { putParcelable("x", pipe[0]) }
        assertTrue(bad.hasFileDescriptors())
        val p = Parcel.obtain().apply { setAllowFds(false) } // instrumentation 结果就是这么序列化的
        try {
            assertThrows(RuntimeException::class.java) { bad.writeToParcel(p, 0) }
        } finally { p.recycle(); pipe.forEach { it.close() } }
    }

    /** 验证修复:新 Bundle 不含 FD,且能在 allowFds=false 下安全序列化 */
    @Test fun sanitizedBundle_hasNoFds_andSurvives() {
        val good = buildResultBundle(fakeSubscriptions())    // 传入伪造的最小数据
        assertFalse(good.hasFileDescriptors())
        val p = Parcel.obtain().apply { setAllowFds(false) }
        try { good.writeToParcel(p, 0) }                     // 不得抛异常
        finally { p.recycle() }
        assertEquals(1, good.getInt("count"))
        assertEquals(1, good.getInt("slot_0"))
    }
}
```

> 说明:`SubscriptionInfo` 无公开构造器,难直接造。方案 A 让 `buildResultBundle` 接收自定义小数据类
> (只含 subId/slot/mcc/mnc… 基本字段),测试即可完全脱离框架 telephony,只依赖 `Bundle`/`Parcel`
> (Robolectric 提供)。若坚持传 `SubscriptionInfo`,可用反射/Mockito 造对象并塞入带 FD 的 Bitmap,
> 断言 `sanitize()` 后 `hasFileDescriptors()==false`。

### 5.3 通过标准

- `bundleWithFd_failsUnderInstrumentationConstraint`:**能重现**含 FD 时的写失败(证明诊断正确)。
- `sanitizedBundle_hasNoFds_andSurvives`:修复后 `hasFileDescriptors()==false` 且 `writeToParcel` 不抛。
- 两条均绿 = 逻辑修复得到离线证实,无需真机。

### 5.4 真机回归(交给有设备的一方,可选)

修复后在 Android 10/11 真机复跑,日志应出现类似
`SimReader: read sim info list size: N` 且**不再**出现
`IllegalArgumentException: File descriptors passed in Intent`,`results` 不再是 `EMPTY_PARCEL`,UI 正常显示 SIM/IMS 信息。
抓取命令:`adb logcat -c` → 打开 App → `adb logcat -d -s SimReader:* ActivityManager:E`。

## 6. 附:本次分析所用 adb 命令(供复现)

```
adb shell dumpsys package io.github.vvb2060.ims.pixel1        # 组件/权限/版本
adb shell cmd package resolve-activity --brief <pkg>          # 找启动 Activity
adb logcat -c; adb shell am start -n <pkg>/io.github.vvb2060.ims.ui.MainActivity
adb logcat -d -s SimReader:* AndroidRuntime:E DEBUG:F ActivityManager:E   # 抓 SimReader 栈
adb shell dumpsys dropbox --print                             # 确认无普通崩溃栈
```
