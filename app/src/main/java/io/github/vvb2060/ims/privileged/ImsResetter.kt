package io.github.vvb2060.ims.privileged

import android.app.Activity
import android.app.IActivityManager
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.ServiceManager
import android.system.Os
import android.telephony.SubscriptionManager
import android.util.Log
import com.android.internal.telephony.ISub
import com.android.internal.telephony.ITelephony
import rikka.shizuku.ShizukuBinderWrapper

class ImsResetter : Instrumentation() {
    companion object {
        private const val TAG = "ImsResetter"
        const val BUNDLE_SELECT_SIM_ID = "select_sim_id"
        const val BUNDLE_RESULT = "result"
        const val BUNDLE_RESULT_MSG = "result_msg"
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        if (arguments == null) {
            finish(Activity.RESULT_CANCELED, Bundle())
            return
        }

        val result = Bundle()
        if (!waitForShizukuBinderReady()) {
            result.putBoolean(BUNDLE_RESULT, false)
            result.putString(BUNDLE_RESULT_MSG, "shizuku binder is not ready")
            finish(Activity.RESULT_OK, result)
            return
        }
        val binder = ServiceManager.getService(Context.ACTIVITY_SERVICE)
        val am = IActivityManager.Stub.asInterface(ShizukuBinderWrapper(binder))
        var delegated = false
        try {
            am.startDelegateShellPermissionIdentity(Os.getuid(), null)
            delegated = true
            val subId = arguments.getInt(BUNDLE_SELECT_SIM_ID, -1)
            val sm = context.getSystemService(SubscriptionManager::class.java)
            val subIds: IntArray = if (subId == -1) {
                sm.javaClass.getMethod("getActiveSubscriptionIdList").invoke(sm) as IntArray
            } else {
                intArrayOf(subId)
            }

            // Android 10 没有 TelephonyFrameworkInitializer.getTelephonyServiceManager()，
            // 改用 ServiceManager.getService(...) + ShizukuBinderWrapper。
            val phoneBinder = ServiceManager.getService(Context.TELEPHONY_SERVICE)
            if (phoneBinder == null) {
                Log.e(TAG, "TELEPHONY_SERVICE binder is null")
                result.putBoolean(BUNDLE_RESULT, false)
                result.putString(BUNDLE_RESULT_MSG, "telephony service unavailable")
                finish(Activity.RESULT_OK, result)
                return
            }
            val telephony = ITelephony.Stub.asInterface(ShizukuBinderWrapper(phoneBinder))
            if (telephony == null) {
                Log.e(TAG, "ITelephony asInterface returned null")
                result.putBoolean(BUNDLE_RESULT, false)
                result.putString(BUNDLE_RESULT_MSG, "ITelephony unavailable")
                finish(Activity.RESULT_OK, result)
                return
            }
            // Android 10 的 isub 服务名为 "isub"（与 11+ 一致），不存在时回退到 slotIndex=0。
            val subBinder = ServiceManager.getService("isub")
            val sub = subBinder?.let { ISub.Stub.asInterface(ShizukuBinderWrapper(it)) }

            for (id in subIds) {
                val slotIndex = sub?.getSlotIndex(id) ?: 0
                Log.i(TAG, "resetIms for subId $id slot $slotIndex (subSvc=${if (sub == null) "fallback" else "isub"})")
                telephony.resetIms(slotIndex)
            }

            result.putBoolean(BUNDLE_RESULT, true)
        } catch (t: Throwable) {
            Log.e(TAG, "reset ims failed", t)
            result.putBoolean(BUNDLE_RESULT, false)
            result.putString(BUNDLE_RESULT_MSG, t.message ?: t.javaClass.simpleName)
        } finally {
            if (delegated) {
                runCatching { am.stopDelegateShellPermissionIdentity() }
                    .onFailure { Log.w(TAG, "stop delegate shell identity failed", it) }
            }
        }

        finish(Activity.RESULT_OK, result)
    }
}
