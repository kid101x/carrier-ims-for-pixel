package io.github.vvb2060.ims.privileged

import android.app.Activity
import android.app.IActivityManager
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.PersistableBundle
import android.os.ServiceManager
import android.system.Os
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.util.Log
import io.github.vvb2060.ims.LogcatRepository
import io.github.vvb2060.ims.model.CarrierProfile
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

class ImsModifier : Instrumentation() {
    companion object Companion {
        private const val TAG = "ImsModifier"
        private const val BUNDLE_COUNTRY_MCC_OVERRIDE = "country_mcc_override"
        private const val BUNDLE_COUNTRY_MNC_HINT = "country_mnc_hint"
        const val BUNDLE_SELECT_SIM_ID = "select_sim_id"
        const val BUNDLE_RESET = "reset"
        const val BUNDLE_PREFER_PERSISTENT = "prefer_persistent"
        const val BUNDLE_RESULT = "result"
        const val BUNDLE_RESULT_MSG = "result_msg"

        fun buildResetBundle(): Bundle = Bundle().apply {
            putBoolean(BUNDLE_RESET, true)
        }

        fun buildBundle(
            carrierName: String?,
            countryISO: String?,
            countryMcc: String?,
            countryMncHint: String?,
            enableVoLTE: Boolean,
            enableVoWiFi: Boolean,
            enableVT: Boolean,
            enableCrossSIM: Boolean,
            enableUT: Boolean,
        ): Bundle {
            val bundle = Bundle()
            // 运营商名称
            if (carrierName?.isNotBlank() == true) {
                bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL, true)
                bundle.putString(CarrierConfigManager.KEY_CARRIER_NAME_STRING, carrierName)
                bundle.putString(CarrierConfigManager.KEY_CARRIER_CONFIG_VERSION_STRING, ":3")
            }
            // 国家码覆盖走 CarrierConfig 私有 key（Android 10 无 KEY_SIM_COUNTRY_ISO_OVERRIDE_STRING，
            // 该 key 为 Android 14+ 才存在，整体跳过写入）。
            normalizeMccForOverride(countryMcc)?.let {
                bundle.putString(BUNDLE_COUNTRY_MCC_OVERRIDE, it)
            }
            normalizeMncForOverride(countryMncHint)?.let {
                bundle.putString(BUNDLE_COUNTRY_MNC_HINT, it)
            }
            // VoLTE 配置
            bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, enableVoLTE)
            bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL, true)
            bundle.putBoolean(CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL, false)
            bundle.putBoolean(CarrierConfigManager.KEY_HIDE_LTE_PLUS_DATA_ICON_BOOL, false)

            // VT (视频通话) 配置
            bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL, enableVT)

            // UT 补充服务配置
            bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL, enableUT)

            // 跨 SIM 通话配置
            bundle.putBoolean(
                CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL,
                enableCrossSIM
            )
            bundle.putBoolean(
                CarrierConfigManager.KEY_ENABLE_CROSS_SIM_CALLING_ON_OPPORTUNISTIC_DATA_BOOL,
                enableCrossSIM
            )

            // VoWiFi 配置
            bundle.putBoolean(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL, enableVoWiFi)
            bundle.putBoolean(
                CarrierConfigManager.KEY_CARRIER_WFC_SUPPORTS_WIFI_ONLY_BOOL,
                enableVoWiFi
            )
            bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL, true)
            bundle.putBoolean(CarrierConfigManager.KEY_EDITABLE_WFC_ROAMING_MODE_BOOL, true)
            // KEY_SHOW_WIFI_CALLING_ICON_IN_STATUS_BAR_BOOL
            bundle.putBoolean("show_wifi_calling_icon_in_status_bar_bool", enableVoWiFi)
            // KEY_WFC_SPN_FORMAT_IDX_INT
            bundle.putInt("wfc_spn_format_idx_int", if (enableVoWiFi) 6 else 0)

            return bundle
        }

        /**
         * 基于 [CarrierProfile] 构建 Bundle：先调用底层 [buildBundle] 写入通用 VoLTE/VoWiFi/VT/UT/CrossSIM
         * 开关，再遍历 [CarrierProfile.carrierConfigKeys] 注入运营商专属键值。
         */
        fun buildBundle(
            profile: CarrierProfile,
            enableVoLTE: Boolean,
            enableVoWiFi: Boolean,
            enableVT: Boolean,
            enableUT: Boolean,
            enableCrossSIM: Boolean,
        ): Bundle {
            val bundle = buildBundle(
                carrierName = profile.name,
                countryISO = null,
                countryMcc = profile.mcc,
                countryMncHint = profile.mnc,
                enableVoLTE = enableVoLTE,
                enableVoWiFi = enableVoWiFi,
                enableVT = enableVT,
                enableCrossSIM = enableCrossSIM,
                enableUT = enableUT,
            )
            profile.carrierConfigKeys.forEach { (key, value) ->
                when (value) {
                    is Boolean -> bundle.putBoolean(key, value)
                    is Int -> bundle.putInt(key, value)
                    is String -> bundle.putString(key, value)
                    is Long -> bundle.putLong(key, value)
                    is IntArray -> bundle.putIntArray(key, value)
                    else -> Log.w(TAG, "buildBundle(profile): unsupported type for key $key: ${value.javaClass.name}")
                }
            }
            return bundle
        }

        private fun normalizeMccForOverride(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val firstPart = raw.trim().substringBefore('-')
            val digits = firstPart.filter { it.isDigit() }.take(3)
            return digits.takeIf { it.length == 3 }
        }

        private fun normalizeMncForOverride(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val digits = raw.filter { it.isDigit() }.take(3)
            return digits.takeIf { it.length in 2..3 }
        }
    }

    override fun onCreate(arguments: Bundle) {
        // 等待 Shizuku binder 准备好
        var index = 0
        val maxRetries = 50 // 最多等待 5 秒
        while (!Shizuku.pingBinder()) {
            index++
            Log.d(TAG, "wait for shizuku binder ready")
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                break
            }
            if (index >= maxRetries) {
                break
            }
        }
        val results = Bundle()
        if (index >= maxRetries) {
            results.putBoolean(BUNDLE_RESULT, false)
            results.putString(BUNDLE_RESULT_MSG, "shizuku binder is not ready")
            finish(Activity.RESULT_OK, results)
            return
        }
        Log.i(TAG, "shizuku binder is ready")

        try {
            overrideConfig(arguments)
            if (LogcatRepository.isCapturing()) {
                Log.i(TAG, "overrideConfig success")
            }
            results.putBoolean(BUNDLE_RESULT, true)
        } catch (t: Throwable) {
            if (LogcatRepository.isCapturing()) {
                Log.i(TAG, "overrideConfig failed")
            }
            Log.e(TAG, "failed to override config", t)
            results.putBoolean(BUNDLE_RESULT, false)
            results.putString(BUNDLE_RESULT_MSG, t.message ?: t.javaClass.simpleName)
        }
        finish(Activity.RESULT_OK, results)
    }

    @Throws(Exception::class)
    private fun overrideConfig(arguments: Bundle) {
        val binder = ServiceManager.getService(Context.ACTIVITY_SERVICE)
        val am = IActivityManager.Stub.asInterface(ShizukuBinderWrapper(binder))
        Log.i(TAG, "starting shell permission delegation")
        am.startDelegateShellPermissionIdentity(Os.getuid(), null)
        try {
            val cm = context.getSystemService(CarrierConfigManager::class.java)
            val sm = context.getSystemService(SubscriptionManager::class.java)

            val selectedSubId = arguments.getInt(BUNDLE_SELECT_SIM_ID, -1)
            arguments.remove(BUNDLE_SELECT_SIM_ID)

            val subIds: IntArray = if (selectedSubId == -1) {
                // 应用到所有 SIM 卡
                sm.javaClass.getMethod("getActiveSubscriptionIdList").invoke(sm) as IntArray
            } else {
                // 只应用到选中的 SIM 卡
                intArrayOf(selectedSubId)
            }
            val reset = arguments.getBoolean(BUNDLE_RESET, false)
            arguments.remove(BUNDLE_RESET)
            val preferPersistent = arguments.getBoolean(BUNDLE_PREFER_PERSISTENT, false)
            arguments.remove(BUNDLE_PREFER_PERSISTENT)
            // BUNDLE_COUNTRY_MCC_OVERRIDE / BUNDLE_COUNTRY_MNC_HINT 保留在 Bundle 中，
            // 通过 CarrierConfig 私有 key 路径写入（Android 10 不支持 setCarrierTestOverride）。
            val baseValues = if (reset) null else arguments.toPersistableBundle()
            for (subId in subIds) {
                val values = baseValues?.let { PersistableBundle(it) }
                Log.i(TAG, "overrideConfig for subId $subId with values $values")
                applyOverrideConfig(
                    cm,
                    subId,
                    values,
                    preferPersistent = preferPersistent
                )
            }
        } finally {
            am.stopDelegateShellPermissionIdentity()
            Log.i(TAG, "stopped shell permission delegation")
        }
    }

    @Throws(Exception::class)
    private fun applyOverrideConfig(
        cm: CarrierConfigManager,
        subId: Int,
        values: PersistableBundle?,
        preferPersistent: Boolean,
    ) {
        if (!preferPersistent) {
            invokeOverrideConfig(cm, subId, values, persistent = false)
            return
        }
        try {
            invokeOverrideConfig(cm, subId, values, persistent = true)
            Log.i(TAG, "overrideConfig persistent success for subId $subId")
        } catch (persistentError: Throwable) {
            Log.w(
                TAG,
                "overrideConfig persistent failed for subId $subId, fallback to non-persistent",
                persistentError
            )
            try {
                invokeOverrideConfig(cm, subId, values, persistent = false)
                Log.i(TAG, "overrideConfig fallback non-persistent success for subId $subId")
            } catch (fallbackError: Throwable) {
                fallbackError.addSuppressed(persistentError)
                throw fallbackError
            }
        }
    }

    @Throws(Exception::class)
    private fun invokeOverrideConfig(
        cm: CarrierConfigManager,
        subId: Int,
        values: PersistableBundle?,
        persistent: Boolean,
    ) {
        // 使用反射调用 overrideConfig：Android 11+ 有三参版（带 persistent），
        // Android 10 仅有两参版，NoSuchMethodException 时回退。
        try {
            cm.javaClass.getMethod(
                "overrideConfig",
                Int::class.javaPrimitiveType,
                PersistableBundle::class.java,
                Boolean::class.javaPrimitiveType
            ).invoke(cm, subId, values, persistent)
        } catch (_: NoSuchMethodException) {
            cm.javaClass.getMethod(
                "overrideConfig",
                Int::class.javaPrimitiveType,
                PersistableBundle::class.java
            ).invoke(cm, subId, values)
        }
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    fun Bundle.toPersistableBundle(): PersistableBundle {
        val pb = PersistableBundle()

        // 遍历 Bundle 的所有 Key
        for (key in this.keySet()) {
            val value = this.get(key)

            when (value) {
                is Int -> pb.putInt(key, value)
                is Long -> pb.putLong(key, value)
                is Double -> pb.putDouble(key, value)
                is String -> pb.putString(key, value)
                is Boolean -> pb.putBoolean(key, value)
                is IntArray -> pb.putIntArray(key, value)
                is LongArray -> pb.putLongArray(key, value)
                is DoubleArray -> pb.putDoubleArray(key, value)
                is BooleanArray -> pb.putBooleanArray(key, value)
                else -> {
                    if (value is Array<*> && value.isArrayOf<String>()) {
                        pb.putStringArray(key, value as Array<String>)
                    } else {
                        Log.i(TAG, "toPersistableBundle: unsupported type for key $key")
                    }
                }
            }
        }
        return pb
    }
}
