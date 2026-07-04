package io.github.vvb2060.ims.privileged

import android.app.Activity
import android.app.IActivityManager
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.ServiceManager
import android.system.Os
import android.util.Log
import com.android.internal.telephony.ITelephony
import rikka.shizuku.ShizukuBinderWrapper

class ImsStatusReader : Instrumentation() {
    companion object {
        private const val TAG = "ImsStatusReader"
        const val BUNDLE_SELECT_SIM_ID = "select_sim_id"
        const val BUNDLE_RESULT = "result"
        const val BUNDLE_RESULT_MSG = "result_msg"

        /**
         * 检测系统层 VoWiFi 平台能力（Task 11.1）。
         *
         * 在 App 进程中通过反射调用 `ImsManager.isWfcEnabledByPlatform()`（Android 10 可用，
         * hidden API，依赖 HiddenApiBypass 解锁）。供 [io.github.vvb2060.ims.viewmodel.MainViewModel]
         * 在应用配置后调用，若返回 false 则 UI 将 Feature.VOWIFI 开关置灰。
         */
        fun isWfcEnabledByPlatform(context: Context, subId: Int): Boolean {
            if (subId < 0) return false
            return try {
                val imsManagerClass = Class.forName("android.telephony.ims.ImsManager")
                val getInstance = imsManagerClass.getMethod(
                    "getInstance",
                    Context::class.java,
                    Int::class.javaPrimitiveType,
                )
                val imsManager = getInstance.invoke(null, context, subId) ?: run {
                    Log.w(TAG, "isWfcEnabledByPlatform: ImsManager.getInstance returned null for subId=$subId")
                    return false
                }
                val isWfcEnabled = imsManagerClass.getMethod("isWfcEnabledByPlatform")
                isWfcEnabled.invoke(imsManager) as? Boolean ?: false
            } catch (t: Throwable) {
                Log.w(TAG, "isWfcEnabledByPlatform failed for subId=$subId: ${t.javaClass.simpleName}: ${t.message}")
                false
            }
        }
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
            if (subId < 0) {
                result.putBoolean(BUNDLE_RESULT, false)
                result.putString(BUNDLE_RESULT_MSG, "invalid subId")
            } else {
                // Android 10 没有 TelephonyFrameworkInitializer.getTelephonyServiceManager()，
                // 改用 ServiceManager.getService(Context.TELEPHONY_SERVICE) + ShizukuBinderWrapper。
                val phoneBinder = ServiceManager.getService(Context.TELEPHONY_SERVICE)
                if (phoneBinder == null) {
                    Log.e(TAG, "TELEPHONY_SERVICE binder is null")
                    result.putBoolean(BUNDLE_RESULT, false)
                    result.putString(BUNDLE_RESULT_MSG, "telephony service unavailable")
                } else {
                    val telephony = ITelephony.Stub.asInterface(ShizukuBinderWrapper(phoneBinder))
                    if (telephony == null) {
                        Log.e(TAG, "ITelephony asInterface returned null")
                        result.putBoolean(BUNDLE_RESULT, false)
                        result.putString(BUNDLE_RESULT_MSG, "ITelephony unavailable")
                    } else {
                        val isRegistered = telephony.isImsRegistered(subId)
                        Log.i(TAG, "IMS registration status: subId=$subId registered=$isRegistered")
                        result.putBoolean(BUNDLE_RESULT, isRegistered)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "read ims status failed", t)
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
