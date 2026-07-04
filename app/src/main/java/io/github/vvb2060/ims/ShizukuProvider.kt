package io.github.vvb2060.ims

import android.app.IActivityManager
import android.app.IInstrumentationWatcher
import android.app.UiAutomationConnection
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.ServiceManager
import android.telephony.SubscriptionInfo
import android.util.Log
import com.android.internal.telephony.ISub
import io.github.vvb2060.ims.model.SimSelection
import io.github.vvb2060.ims.model.ApnDraftConfig
import io.github.vvb2060.ims.privileged.ApnModifier
import io.github.vvb2060.ims.privileged.BrokerInstrumentation
import io.github.vvb2060.ims.privileged.CaptivePortalFixer
import io.github.vvb2060.ims.privileged.ConfigReader
import io.github.vvb2060.ims.privileged.ImsResetter
import io.github.vvb2060.ims.privileged.ImsStatusReader
import io.github.vvb2060.ims.privileged.ImsModifier
import io.github.vvb2060.ims.privileged.SimReader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lsposed.hiddenapibypass.LSPass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.ShizukuProvider

class ShizukuProvider : ShizukuProvider() {
    override fun onCreate(): Boolean {
        LSPass.setHiddenApiExemptions("")
        // 不再自动触发，只在用户手动点击"应用配置"时才执行
        return super.onCreate()
    }

    companion object {
        private const val TAG = "ShizukuProvider"
        private const val INSTRUMENTATION_RESULT_TIMEOUT_MS = 15_000L
        private val instrumentationMutex = Mutex()

        data class CaptivePortalConfig(
            val httpUrl: String,
            val httpsUrl: String,
            val isCnUrl: Boolean,
            val isOverridden: Boolean,
        )

        suspend fun overrideImsConfig(context: Context, data: Bundle): String? {
            val primaryArgs = Bundle(data)
            val result = startInstrumentation(context, ImsModifier::class.java, primaryArgs, true)
            if (result == null) {
                Log.w(TAG, "overrideImsConfig: failed with empty result")
                return tryOverrideWithBroker(context, data, "failed with empty result")
            }
            if (result.getBoolean(ImsModifier.BUNDLE_RESULT)) {
                return null
            }
            val msg = result.getString(ImsModifier.BUNDLE_RESULT_MSG) ?: "unknown error"
            // Retry via broker when persistent override is restricted or result is empty.
            return tryOverrideWithBroker(context, data, msg)
        }

        suspend fun readSimInfoList(context: Context): List<SimSelection> {
            // INSTR_FLAG_NO_RESTART（值 8）在 Android 14（API 34）才加入。
            // Android 10–13 上 startInstrumentation 会 force-stop 目标包（即本 App），
            // 杀死 UI 进程，IInstrumentationWatcher 也随之失效，结果无法回传——
            // 表现为"打开 App 一闪即退"。
            // 在这些版本上改用直接 Shizuku Binder 调用 ISub，不经 instrumentation，
            // 避免 force-stop，同时也不存在 FD 问题（不经过 finish() 回传）。
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return readSimInfoListViaBinder()
            }
            val result = startInstrumentation(context, SimReader::class.java, null, true)
            if (result == null) {
                Log.w(TAG, "readSimInfoList: failed with empty result")
                return emptyList()
            }
            // Android 10/11 上结果 Bundle 不能携带 SubscriptionInfo（含 Bitmap → FD），
            // 否则 finishInstrumentation 会因 allowFds=false 静默失败。
            // SimReader 已改为只回传基本类型字段，按同一套 key 重建 SimSelection。
            val count = result.getInt(SimReader.BUNDLE_COUNT, 0)
            if (count <= 0) {
                val msg = result.getString(SimReader.BUNDLE_RESULT_MSG)
                Log.w(TAG, "readSimInfoList: empty list" + (msg?.let { ": $it" } ?: ""))
                return emptyList()
            }
            val resultList = ArrayList<SimSelection>(count)
            for (i in 0 until count) {
                val prefix = "${i}_"
                val subId = result.getInt("${prefix}subId", -1)
                val slot = result.getInt("${prefix}slot", -1)
                val displayName = result.getString("${prefix}displayName") ?: ""
                val carrierName = result.getString("${prefix}carrierName") ?: ""
                resultList.add(
                    SimSelection(
                        subId = subId,
                        displayName = displayName,
                        carrierName = carrierName,
                        simSlotIndex = slot,
                        countryIso = result.getString("${prefix}countryIso") ?: "",
                        mcc = result.getString("${prefix}mcc") ?: "",
                        mnc = result.getString("${prefix}mnc") ?: "",
                        iccId = result.getString("${prefix}iccId") ?: "",
                    )
                )
            }
            return resultList
        }

        /**
         * 直接通过 Shizuku Binder 调用 ISub.getActiveSubscriptionInfoList 读取 SIM 列表。
         *
         * 不经 instrumentation，因此不会触发 force-stop（Android 10–13 的固有行为），
         * 也不存在结果 Bundle 携带 FD 的问题。调用经 ShizukuBinderWrapper 路由到
         * Shizuku shell 进程，具备 shell 权限，足以读取 SIM 信息。
         */
        private suspend fun readSimInfoListViaBinder(): List<SimSelection> =
            withContext(Dispatchers.IO) {
                try {
                    val binder = ServiceManager.getService("isub")
                    if (binder == null) {
                        Log.w(TAG, "readSimInfoListViaBinder: isub service unavailable")
                        return@withContext emptyList()
                    }
                    val sub = ISub.Stub.asInterface(ShizukuBinderWrapper(binder))
                    // Android 10/11: 两参 getActiveSubscriptionInfoList(String, String)
                    // Android 12+:   三参 getActiveSubscriptionInfoList(String, String, boolean)
                    // stub 只编了三参，Android 10/11 走反射调用两参签名。
                    val rawList: List<SubscriptionInfo>? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            sub.getActiveSubscriptionInfoList(null, null, true)
                        } else {
                            val m = ISub::class.java.getMethod(
                                "getActiveSubscriptionInfoList",
                                String::class.java, String::class.java,
                            )
                            @Suppress("UNCHECKED_CAST")
                            m.invoke(sub, null, null) as? List<SubscriptionInfo>
                        }
                    if (rawList.isNullOrEmpty()) {
                        Log.i(TAG, "readSimInfoListViaBinder: empty list")
                        return@withContext emptyList()
                    }
                    Log.i(TAG, "readSimInfoListViaBinder: read sim info list size: ${rawList.size}")
                    // 复用 SimReader.toRaw 提取纯字段，避免 SubscriptionInfo 的 Bitmap/FD
                    rawList.map { info ->
                        val raw = SimReader.toRaw(info)
                        SimSelection(
                            subId = raw.subId,
                            displayName = raw.displayName ?: "",
                            carrierName = raw.carrierName ?: "",
                            simSlotIndex = raw.slot,
                            countryIso = raw.countryIso ?: "",
                            mcc = raw.mcc ?: "",
                            mnc = raw.mnc ?: "",
                            iccId = raw.iccId ?: "",
                        )
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "readSimInfoListViaBinder: failed", t)
                    emptyList()
                }
            }

        suspend fun readCarrierConfig(
            context: Context,
            subId: Int,
            keys: Array<String>,
        ): Bundle? {
            val args = Bundle().apply {
                putInt(ConfigReader.BUNDLE_SELECT_SIM_ID, subId)
                putStringArray(ConfigReader.BUNDLE_KEYS, keys)
            }
            val result = startInstrumentation(context, ConfigReader::class.java, args, true)
            if (result == null) return null
            val value = result.rawValue(ConfigReader.BUNDLE_RESULT)
            if (value == null) return null
            if (value !is Bundle) {
                Log.w(
                    TAG,
                    "readCarrierConfig: unexpected result type ${value.javaClass.name} for subId=$subId"
                )
                return null
            }
            return value
        }

        suspend fun dumpCarrierConfig(context: Context, subId: Int): String? {
            val args = Bundle().apply {
                putInt(ConfigReader.BUNDLE_SELECT_SIM_ID, subId)
                putBoolean(ConfigReader.BUNDLE_DUMP, true)
            }
            val result = startInstrumentation(context, ConfigReader::class.java, args, true)
            return result?.getString(ConfigReader.BUNDLE_DUMP_TEXT)
        }

        suspend fun restartImsRegistration(context: Context, subId: Int): String? {
            val args = Bundle().apply {
                putInt(ImsResetter.BUNDLE_SELECT_SIM_ID, subId)
            }
            val result = startInstrumentation(context, ImsResetter::class.java, args, true)
            if (result == null) {
                return "failed with empty result"
            }
            return if (result.getBoolean(ImsResetter.BUNDLE_RESULT)) {
                null
            } else {
                result.getString(ImsResetter.BUNDLE_RESULT_MSG) ?: "unknown error"
            }
        }

        suspend fun readImsRegistrationStatus(context: Context, subId: Int): Boolean? {
            val args = Bundle().apply {
                putInt(ImsStatusReader.BUNDLE_SELECT_SIM_ID, subId)
            }
            val result = startInstrumentation(context, ImsStatusReader::class.java, args, true)
            if (result == null) return null
            val msg = result.getString(ImsStatusReader.BUNDLE_RESULT_MSG)
            if (msg != null) {
                Log.w(TAG, "readImsRegistrationStatus: failed for subId=$subId: $msg")
                return null
            }
            val value = result.rawValue(ImsStatusReader.BUNDLE_RESULT)
            if (value !is Boolean) {
                Log.w(
                    TAG,
                    "readImsRegistrationStatus: missing or invalid result for subId=$subId"
                )
                return null
            }
            Log.i(TAG, "readImsRegistrationStatus: subId=$subId registered=$value")
            return value
        }

        suspend fun updateCarrierConfigBoolean(
            context: Context,
            subId: Int,
            key: String,
            value: Boolean,
        ): String? {
            val appInfo = context.applicationInfo
            val canUsePersistentOverride =
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val bundle = Bundle().apply {
                putInt(ImsModifier.BUNDLE_SELECT_SIM_ID, subId)
                putBoolean(key, value)
                putBoolean(ImsModifier.BUNDLE_PREFER_PERSISTENT, canUsePersistentOverride)
            }
            return overrideImsConfig(context, bundle)
        }

        suspend fun queryCaptivePortalConfig(context: Context): CaptivePortalConfig? {
            val args = Bundle().apply {
                putString(CaptivePortalFixer.BUNDLE_ACTION, CaptivePortalFixer.actionQuery())
            }
            val result = startInstrumentation(context, CaptivePortalFixer::class.java, args, true)
            if (result == null || !result.getBoolean(CaptivePortalFixer.BUNDLE_RESULT, false)) {
                return null
            }
            return CaptivePortalConfig(
                httpUrl = result.getString(CaptivePortalFixer.BUNDLE_HTTP_URL).orEmpty(),
                httpsUrl = result.getString(CaptivePortalFixer.BUNDLE_HTTPS_URL).orEmpty(),
                isCnUrl = result.getBoolean(CaptivePortalFixer.BUNDLE_IS_CN_URL, false),
                isOverridden = result.getBoolean(CaptivePortalFixer.BUNDLE_IS_OVERRIDDEN, false)
            )
        }

        suspend fun applyCaptivePortalCnUrls(context: Context): String? {
            val args = Bundle().apply {
                putString(CaptivePortalFixer.BUNDLE_ACTION, CaptivePortalFixer.actionApplyCn())
            }
            val result = startInstrumentation(context, CaptivePortalFixer::class.java, args, true)
            if (result == null) {
                return "failed with empty result"
            }
            return if (result.getBoolean(CaptivePortalFixer.BUNDLE_RESULT)) {
                null
            } else {
                result.getString(CaptivePortalFixer.BUNDLE_RESULT_MSG) ?: "unknown error"
            }
        }

        suspend fun restoreCaptivePortalDefaultUrls(context: Context): String? {
            val args = Bundle().apply {
                putString(CaptivePortalFixer.BUNDLE_ACTION, CaptivePortalFixer.actionRestoreDefault())
            }
            val result = startInstrumentation(context, CaptivePortalFixer::class.java, args, true)
            if (result == null) {
                return "failed with empty result"
            }
            return if (result.getBoolean(CaptivePortalFixer.BUNDLE_RESULT)) {
                null
            } else {
                result.getString(CaptivePortalFixer.BUNDLE_RESULT_MSG) ?: "unknown error"
            }
        }

        suspend fun applyApnConfig(
            context: Context,
            subId: Int,
            config: ApnDraftConfig,
        ): String? {
            val args = Bundle().apply {
                putInt(ApnModifier.BUNDLE_SELECT_SIM_ID, subId)
                putString(ApnModifier.BUNDLE_NAME, config.name)
                putString(ApnModifier.BUNDLE_APN, config.apn)
                putString(ApnModifier.BUNDLE_TYPE, config.type)
                putString(ApnModifier.BUNDLE_MCC, config.mcc)
                putString(ApnModifier.BUNDLE_MNC, config.mnc)
            }
            val result = startInstrumentation(context, ApnModifier::class.java, args, true)
            if (result == null) {
                return "failed with empty result"
            }
            return if (result.getBoolean(ApnModifier.BUNDLE_RESULT)) {
                null
            } else {
                result.getString(ApnModifier.BUNDLE_RESULT_MSG) ?: "unknown error"
            }
        }

        private suspend fun startInstrumentation(
            context: Context,
            cls: Class<*>,
            args: Bundle?,
            receiveResult: Boolean,
        ): Bundle? = instrumentationMutex.withLock {
            val deferredResult = CompletableDeferred<Bundle?>()
            var watcher: IInstrumentationWatcher.Stub? = null
            if (receiveResult) {
                watcher = object : IInstrumentationWatcher.Stub() {
                    override fun instrumentationStatus(
                        name: ComponentName?,
                        resultCode: Int,
                        results: Bundle?
                    ) {
                    }

                    override fun instrumentationFinished(
                        name: ComponentName?,
                        resultCode: Int,
                        results: Bundle?
                    ) {
                        deferredResult.complete(results)
                    }
                }
            }

            val binder = ServiceManager.getService(Context.ACTIVITY_SERVICE)
            val am = IActivityManager.Stub.asInterface(ShizukuBinderWrapper(binder))
            val name = ComponentName(context, cls)
            val flags = 8 // ActivityManager.INSTR_FLAG_NO_RESTART
            val connection = UiAutomationConnection()
            var started = false
            try {
                Log.d(TAG, "startInstrumentation: call with component: $name")
                am.startInstrumentation(name, null, flags, args, watcher, connection, 0, null)
                started = true
                Log.i(TAG, "instrumentation started successfully")
                if (receiveResult) {
                    return withTimeoutOrNull(INSTRUMENTATION_RESULT_TIMEOUT_MS) {
                        deferredResult.await()
                    }
                }
                return null
            } catch (e: CancellationException) {
                if (started && receiveResult) {
                    withContext(NonCancellable) {
                        withTimeoutOrNull(INSTRUMENTATION_RESULT_TIMEOUT_MS) {
                            deferredResult.await()
                        }
                    }
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "failed to start instrumentation", e)
                return null
            }
        }

        private suspend fun tryOverrideWithBroker(
            context: Context,
            data: Bundle,
            msg: String,
        ): String? {
            if (!shouldRetryWithBroker(msg)) {
                return msg
            }
            val brokerArgs = Bundle(data)
            val brokerResult =
                startInstrumentation(context, BrokerInstrumentation::class.java, brokerArgs, true)
            if (brokerResult == null) {
                Log.w(TAG, "overrideImsConfig: broker failed with empty result")
                return msg
            }
            if (brokerResult.getBoolean(ImsModifier.BUNDLE_RESULT)) {
                return null
            }
            return brokerResult.getString(ImsModifier.BUNDLE_RESULT_MSG) ?: msg
        }

        private fun shouldRetryWithBroker(message: String): Boolean {
            val lower = message.lowercase()
            return lower.contains("persistent=true") ||
                lower.contains("system app") ||
                lower.contains("securityexception") ||
                lower.contains("security exception") ||
                lower.contains("empty result")
        }

        @Suppress("DEPRECATION")
        private fun Bundle.rawValue(key: String): Any? = get(key)
    }
}
