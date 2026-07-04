package io.github.vvb2060.ims.privileged

import android.annotation.SuppressLint
import android.app.Activity
import android.app.IActivityManager
import android.app.Instrumentation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.ServiceManager
import android.system.Os
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import com.android.internal.telephony.ISub
import rikka.shizuku.ShizukuBinderWrapper

/**
 * 经 Shizuku 以 instrumentation 方式运行，读取当前激活 SIM 列表。
 *
 * **Android 10/11 关键约束**：`Instrumentation.finish()` 的结果 Bundle 在 AMS 侧以
 * `allowFds=false` 序列化。Android 10/11 的 `SubscriptionInfo` 携带 `mIconBitmap`，
 * 其 parcel 编码使用 ashmem FD —— 若直接把 `SubscriptionInfo` 放进结果 Bundle，会抛
 * `IllegalArgumentException: File descriptors passed in Intent`，导致 `finishInstrumentation`
 * 失败、宿主拿到 `Bundle[EMPTY_PARCEL]`，UI 进程又被 `startInstrumentation` 触发的
 * force-stop 杀掉，表现为"打开 App 一闪即退"。
 *
 * 修复：结果 Bundle 只放基本类型字段（int/string），绝不放 `SubscriptionInfo` / `Bitmap`
 * / 任何带 FD 的对象。详见 `buildResultBundle`。
 */
class SimReader : Instrumentation() {

    /**
     * `SubscriptionInfo` 的纯字段视图，便于离线单测（`SubscriptionInfo` 无公开构造器）。
     * 只含 UI 真正需要的字段，绝不放 `Bitmap` / 任何带 FD 的对象。
     */
    data class SubInfoRaw(
        val subId: Int,
        val slot: Int,
        val carrierId: Int,
        val iccId: String?,
        val mcc: String?,
        val mnc: String?,
        val countryIso: String?,
        val displayName: String?,
        val carrierName: String?,
        val number: String?,
    )

    companion object {
        private const val TAG = "SimReader"
        const val BUNDLE_RESULT = "sim_list"
        const val BUNDLE_RESULT_MSG = "sim_list_msg"
        const val BUNDLE_COUNT = "count"

        /** 从 `SubscriptionInfo` 提取纯字段。 */
        fun toRaw(info: SubscriptionInfo): SubInfoRaw = SubInfoRaw(
            subId = info.subscriptionId,
            slot = info.simSlotIndex,
            carrierId = info.carrierId,
            iccId = info.iccId,
            mcc = info.mccString,
            mnc = info.mncString,
            countryIso = info.countryIso,
            displayName = info.displayName?.toString(),
            carrierName = info.carrierName?.toString(),
            number = info.number,
        )

        /**
         * 把 `SubscriptionInfo` 列表编码成不含 FD 的结果 Bundle。
         *
         * 每条 SIM 用前缀 `i_` 加字段名存基本类型，跨版本稳定，便于离线单测。
         * 只放 UI 真正需要的字段；绝不放 `SubscriptionInfo` / `Bitmap` / 任何带 FD 的对象。
         */
        fun buildResultBundle(list: List<SubscriptionInfo>): Bundle =
            buildResultBundleFromRaw(list.map { toRaw(it) })

        /**
         * 纯字段版本，离线单测入口。结果 Bundle 不含任何 Parcelable/FD。
         */
        fun buildResultBundleFromRaw(raws: List<SubInfoRaw>): Bundle {
            val b = Bundle()
            b.putInt(BUNDLE_COUNT, raws.size)
            raws.forEachIndexed { i, info ->
                val prefix = "${i}_"
                b.putInt("${prefix}subId", info.subId)
                b.putInt("${prefix}slot", info.slot)
                b.putInt("${prefix}carrierId", info.carrierId)
                b.putString("${prefix}iccId", info.iccId)
                b.putString("${prefix}mcc", info.mcc)
                b.putString("${prefix}mnc", info.mnc)
                b.putString("${prefix}countryIso", info.countryIso)
                b.putString("${prefix}displayName", info.displayName)
                b.putString("${prefix}carrierName", info.carrierName)
                b.putString("${prefix}number", info.number)
            }
            return b
        }
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    @SuppressLint("MissingPermission")
    override fun start() {
        super.start()
        if (!waitForShizukuBinderReady()) {
            finishWith(Activity.RESULT_CANCELED, "shizuku binder not ready", Bundle())
            return
        }
        val binder = ServiceManager.getService(Context.ACTIVITY_SERVICE)
        if (binder == null) {
            finishWith(Activity.RESULT_CANCELED, "activity service unavailable", Bundle())
            return
        }
        val am = IActivityManager.Stub.asInterface(ShizukuBinderWrapper(binder))
        // Android 10 上 startDelegateShellPermissionIdentity 被 hidden-API 黑名单拦截；
        // instrumentation 本身已带 shell 权限，SDK_INT<=29 时跳过该调用避免无谓 denied 告警。
        val needDelegate = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        var delegated = false
        try {
            if (needDelegate) {
                Log.i(TAG, "starting shell permission delegation")
                am.startDelegateShellPermissionIdentity(Os.getuid(), null)
                delegated = true
            } else {
                Log.i(TAG, "skip shell permission delegation on API ${Build.VERSION.SDK_INT}")
            }
            Log.d(TAG, "start read sim info list")
            val resultList = readByISub() ?: run {
                val subManager =
                    context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                val subList = subManager.activeSubscriptionInfoList
                subList ?: emptyList()
            }
            Log.i(TAG, "read sim info list size: ${resultList.size}")
            val result = buildResultBundle(resultList)
            // 自检：结果 Bundle 绝不能携带 FD，否则 finishInstrumentation 会静默失败。
            if (result.hasFileDescriptors()) {
                Log.e(TAG, "result bundle unexpectedly contains FDs; aborting finish")
                finishWith(Activity.RESULT_CANCELED, "result bundle contains FDs", Bundle())
                return
            }
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            Log.e(TAG, "failed to read sim info list", t)
            finishWith(Activity.RESULT_CANCELED, t.message ?: t.javaClass.simpleName, Bundle())
        } finally {
            if (delegated) {
                runCatching {
                    am.stopDelegateShellPermissionIdentity()
                    Log.i(TAG, "stopped shell permission delegation")
                }.onFailure {
                    Log.w(TAG, "failed to stop shell permission delegation", it)
                }
            }
        }
    }

    /**
     * finish 失败要"可见"而非静默：把可读错误写入结果 Bundle，让 UI 能显示原因。
     */
    private fun finishWith(code: Int, msg: String, extras: Bundle) {
        val b = Bundle(extras)
        b.putString(BUNDLE_RESULT_MSG, msg)
        try {
            finish(code, b)
        } catch (t: Throwable) {
            // 兜底：连 finish 都抛了，只能落地日志。
            Log.e(TAG, "finish($code) failed: $msg", t)
        }
    }

    private fun readByISub(): List<SubscriptionInfo>? {
        return try {
            // Android 10 没有 TelephonyFrameworkInitializer.getTelephonyServiceManager()，
            // 改用 ServiceManager.getService("isub") + ShizukuBinderWrapper。
            val binder = ServiceManager.getService("isub") ?: return null
            val sub = ISub.Stub.asInterface(ShizukuBinderWrapper(binder))
            // 三参签名 getActiveSubscriptionInfoList(pkg, featureId, boolean) 仅 Android 12+ 有；
            // Android 10/11 用两参 getActiveSubscriptionInfoList(String, String)。
            // stub 只编了三参，故 Android 10/11 走反射调用两参签名，避免 NoSuchMethodError 噪声。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sub.getActiveSubscriptionInfoList(null, null, true)
            } else {
                val m = ISub::class.java.getMethod(
                    "getActiveSubscriptionInfoList",
                    String::class.java, String::class.java,
                )
                @Suppress("UNCHECKED_CAST")
                (m.invoke(sub, null, null) as? List<SubscriptionInfo>)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "readByISub failed, fallback to SubscriptionManager", e)
            null
        }
    }
}
