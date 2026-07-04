package io.github.vvb2060.ims

import android.app.IActivityManager
import android.app.IInstrumentationWatcher
import android.app.UiAutomationConnection
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PersistableBundle
import android.os.ServiceManager
import android.provider.Settings
import android.provider.Telephony
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import com.android.internal.telephony.ISub
import com.android.internal.telephony.ITelephony
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

        // Binder 路径直接读写 Settings.Global 用的 captive portal 键与默认 CN URL，
        // 与 CaptivePortalFixer 中常量保持一致（CaptivePortalFixer 中键为 private）。
        private const val KEY_CAPTIVE_PORTAL_HTTP_URL = "captive_portal_http_url"
        private const val KEY_CAPTIVE_PORTAL_HTTPS_URL = "captive_portal_https_url"
        private const val CN_HTTP_URL = "http://connectivitycheck.gstatic.cn/generate_204"
        private const val CN_HTTPS_URL = "https://www.google.cn/generate_204"
        private const val PREFER_APN_URI_PREFIX = "content://telephony/carriers/preferapn/subId/"

        data class CaptivePortalConfig(
            val httpUrl: String,
            val httpsUrl: String,
            val isCnUrl: Boolean,
            val isOverridden: Boolean,
        )

        suspend fun overrideImsConfig(context: Context, data: Bundle): String? {
            // Android < 14 上 startInstrumentation 会 force-stop 本 App（含 UI 进程），
            // IInstrumentationWatcher 随之失效。改为直接通过 Shizuku Binder 调用
            // CarrierConfigManager.overrideConfig（反射）。
            // 失败时不回退 broker：broker 也走 instrumentation，存在同样的 force-stop 问题。
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return overrideImsConfigViaBinder(context, data)
            }
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
                return readSimInfoListViaBinder(context)
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
        private suspend fun readSimInfoListViaBinder(context: Context): List<SimSelection> =
            withContext(Dispatchers.IO) {
                try {
                    val binder = ServiceManager.getService("isub")
                    if (binder == null) {
                        Log.w(TAG, "readSimInfoListViaBinder: isub service unavailable")
                        return@withContext emptyList()
                    }
                    val sub = ISub.Stub.asInterface(ShizukuBinderWrapper(binder))
                    // ISub.getActiveSubscriptionInfoList 签名随版本演进：
                    //   API 23 (M)  : 一参 (String)
                    //   API 30 (R)  : 两参 (String, String)
                    //   API 34 (U)  : 三参 (String, String, boolean)
                    // 运行时框架的 ISub 被加载（父类加载器优先 + LSPass 绕过隐藏 API），
                    // 故直接调 stub / 反射均落到框架真实实现与正确事务码上。
                    // AIDL 不允许同名方法重载，stub 只编了三参；两参/一参签名走反射调用。
                    val rawList: List<SubscriptionInfo>? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            sub.getActiveSubscriptionInfoList(null, null, true)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val m = ISub::class.java.getMethod(
                                "getActiveSubscriptionInfoList",
                                String::class.java, String::class.java,
                            )
                            @Suppress("UNCHECKED_CAST")
                            m.invoke(sub, null, null) as? List<SubscriptionInfo>
                        } else {
                            // Android 10 (API 29) 及以下：仅有一参签名，反射调用并传入本包名，
                            // 避免 ISub 实现的包名校验拒绝 null callingPackage。
                            val m = ISub::class.java.getMethod(
                                "getActiveSubscriptionInfoList",
                                String::class.java,
                            )
                            @Suppress("UNCHECKED_CAST")
                            m.invoke(sub, context.packageName) as? List<SubscriptionInfo>
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
                    Log.e(
                        TAG,
                        "readSimInfoListViaBinder: failed: ${t.javaClass.name}: ${t.message}",
                        t,
                    )
                    emptyList()
                }
            }

        suspend fun readCarrierConfig(
            context: Context,
            subId: Int,
            keys: Array<String>,
        ): Bundle? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return readCarrierConfigViaBinder(context, subId, keys)
            }
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return dumpCarrierConfigViaBinder(context, subId)
            }
            val args = Bundle().apply {
                putInt(ConfigReader.BUNDLE_SELECT_SIM_ID, subId)
                putBoolean(ConfigReader.BUNDLE_DUMP, true)
            }
            val result = startInstrumentation(context, ConfigReader::class.java, args, true)
            return result?.getString(ConfigReader.BUNDLE_DUMP_TEXT)
        }

        suspend fun restartImsRegistration(context: Context, subId: Int): String? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return restartImsRegistrationViaBinder(context, subId)
            }
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return readImsRegistrationStatusViaBinder(context, subId)
            }
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return queryCaptivePortalConfigViaBinder(context)
            }
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return applyCaptivePortalCnUrlsViaBinder(context)
            }
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return restoreCaptivePortalDefaultUrlsViaBinder(context)
            }
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return applyApnConfigViaBinder(context, subId, config)
            }
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

        // ===== 直接 Shizuku Binder 路径（Android < 14） =====
        // Android < 14 上 startInstrumentation 因 INSTR_FLAG_NO_RESTART 未引入会
        // force-stop 本 App（含 UI 进程），导致 IInstrumentationWatcher 失效。
        // 改为直接通过 ShizukuBinderWrapper 把系统 service binder 调用路由到 Shizuku
        // shell 进程，规避 force-stop，也无需 startDelegateShellPermissionIdentity
        // （后者本身是 Android 11+ 才有的 API）。

        /**
         * 通过 ShizukuBinderWrapper 路由 ICarrierConfig binder（Android 10 上隐藏 AIDL）。
         * 反射调用 Stub.asInterface，避免依赖编译期不存在的 ICarrierConfig stub 类。
         */
        private fun getICarrierConfigViaShizuku(): Any? {
            val binder = ServiceManager.getService("carrier_config") ?: run {
                Log.w(TAG, "getICarrierConfigViaShizuku: carrier_config service unavailable")
                return null
            }
            val wrapped = ShizukuBinderWrapper(binder)
            val stubClass = Class.forName("android.telephony.ICarrierConfig\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            return asInterface.invoke(null, wrapped)
        }

        private fun getITelephonyViaShizuku(): ITelephony? {
            val binder = ServiceManager.getService(Context.TELEPHONY_SERVICE) ?: run {
                Log.w(TAG, "getITelephonyViaShizuku: telephony service unavailable")
                return null
            }
            return ITelephony.Stub.asInterface(ShizukuBinderWrapper(binder))
        }

        private fun getISubViaShizuku(): ISub? {
            val binder = ServiceManager.getService("isub") ?: run {
                Log.w(TAG, "getISubViaShizuku: isub service unavailable")
                return null
            }
            return ISub.Stub.asInterface(ShizukuBinderWrapper(binder))
        }

        /**
         * 反射 SubscriptionManager.getActiveSubscriptionIdList 取所有活跃 subId。
         * 该 API 仅需 READ_PHONE_STATE（App 已声明并运行时申请），无需 shell 委托。
         */
        private fun getActiveSubIds(sm: SubscriptionManager): IntArray =
            sm.javaClass.getMethod("getActiveSubscriptionIdList").invoke(sm) as IntArray

        /**
         * 通过 Shizuku Binder 直接调用 ICarrierConfig.overrideConfig 写 CarrierConfig。
         *
         * 复刻 ImsModifier 的核心逻辑：选 SIM、reset/preferPersistent 处理、persistent
         * 失败回退非 persistent、三参 → 两参 overrideConfig 反射回退（Android 10 仅两参）。
         * 失败时不回退 broker：broker 也走 instrumentation，存在同样的 force-stop 问题。
         */
        private suspend fun overrideImsConfigViaBinder(context: Context, data: Bundle): String? =
            withContext(Dispatchers.IO) {
                try {
                    val args = Bundle(data)
                    val sm = context.getSystemService(SubscriptionManager::class.java)
                        ?: throw IllegalStateException("SubscriptionManager unavailable")
                    val selectedSubId = args.getInt(ImsModifier.BUNDLE_SELECT_SIM_ID, -1)
                    args.remove(ImsModifier.BUNDLE_SELECT_SIM_ID)
                    val subIds: IntArray = if (selectedSubId == -1) {
                        getActiveSubIds(sm)
                    } else {
                        intArrayOf(selectedSubId)
                    }
                    val reset = args.getBoolean(ImsModifier.BUNDLE_RESET, false)
                    args.remove(ImsModifier.BUNDLE_RESET)
                    val preferPersistent = args.getBoolean(ImsModifier.BUNDLE_PREFER_PERSISTENT, false)
                    args.remove(ImsModifier.BUNDLE_PREFER_PERSISTENT)
                    val baseValues = if (reset) null else args.toPersistableBundleForOverride()
                    val iCarrierConfig = getICarrierConfigViaShizuku()
                        ?: throw IllegalStateException("ICarrierConfig unavailable")
                    for (subId in subIds) {
                        val values = baseValues?.let { PersistableBundle(it) }
                        Log.i(TAG, "overrideImsConfigViaBinder: subId=$subId values=$values")
                        applyOverrideConfigViaBinder(
                            iCarrierConfig,
                            subId,
                            values,
                            preferPersistent = preferPersistent,
                        )
                    }
                    null
                } catch (t: Throwable) {
                    Log.e(TAG, "overrideImsConfigViaBinder: failed: ${t.javaClass.name}: ${t.message}", t)
                    t.message ?: t.javaClass.simpleName
                }
            }

        private fun applyOverrideConfigViaBinder(
            iCarrierConfig: Any,
            subId: Int,
            values: PersistableBundle?,
            preferPersistent: Boolean,
        ) {
            if (!preferPersistent) {
                invokeOverrideConfigViaBinder(iCarrierConfig, subId, values, persistent = false)
                return
            }
            try {
                invokeOverrideConfigViaBinder(iCarrierConfig, subId, values, persistent = true)
                Log.i(TAG, "applyOverrideConfigViaBinder: persistent success for subId=$subId")
            } catch (persistentError: Throwable) {
                Log.w(
                    TAG,
                    "applyOverrideConfigViaBinder: persistent failed for subId=$subId, fallback non-persistent",
                    persistentError,
                )
                try {
                    invokeOverrideConfigViaBinder(iCarrierConfig, subId, values, persistent = false)
                    Log.i(TAG, "applyOverrideConfigViaBinder: fallback non-persistent success for subId=$subId")
                } catch (fallbackError: Throwable) {
                    fallbackError.addSuppressed(persistentError)
                    throw fallbackError
                }
            }
        }

        private fun invokeOverrideConfigViaBinder(
            iCarrierConfig: Any,
            subId: Int,
            values: PersistableBundle?,
            persistent: Boolean,
        ) {
            // ICarrierConfig.overrideConfig 签名随版本演进：
            //   Android 11+ : overrideConfig(int, PersistableBundle, boolean)
            //   Android 10  : overrideConfig(int, PersistableBundle)
            try {
                iCarrierConfig.javaClass.getMethod(
                    "overrideConfig",
                    Int::class.javaPrimitiveType,
                    PersistableBundle::class.java,
                    Boolean::class.javaPrimitiveType,
                ).invoke(iCarrierConfig, subId, values, persistent)
            } catch (_: NoSuchMethodException) {
                iCarrierConfig.javaClass.getMethod(
                    "overrideConfig",
                    Int::class.javaPrimitiveType,
                    PersistableBundle::class.java,
                ).invoke(iCarrierConfig, subId, values)
            }
        }

        /**
         * 通过 Shizuku Binder 直接调用 ICarrierConfig.getConfigForSubId 读取 CarrierConfig，
         * 复刻 ConfigReader 读取路径：取 PersistableBundle，按 keys 提取到结果 Bundle。
         */
        private suspend fun readCarrierConfigViaBinder(
            context: Context,
            subId: Int,
            keys: Array<String>,
        ): Bundle? = withContext(Dispatchers.IO) {
            try {
                val iCarrierConfig = getICarrierConfigViaShizuku() ?: run {
                    Log.w(TAG, "readCarrierConfigViaBinder: ICarrierConfig unavailable")
                    return@withContext null
                }
                val config = iCarrierConfig.javaClass
                    .getMethod("getConfigForSubId", Int::class.javaPrimitiveType)
                    .invoke(iCarrierConfig, subId) as? PersistableBundle
                if (config == null) {
                    Log.w(TAG, "readCarrierConfigViaBinder: null config for subId=$subId")
                    return@withContext null
                }
                val values = Bundle()
                for (key in keys) {
                    putConfigValue(values, key, config.get(key))
                }
                values
            } catch (t: Throwable) {
                Log.e(TAG, "readCarrierConfigViaBinder: failed: ${t.javaClass.name}: ${t.message}", t)
                null
            }
        }

        /**
         * 通过 Shizuku Binder 读取 CarrierConfig 并 dump 所有 KEY_ 字段，复刻 ConfigReader.dump。
         */
        private suspend fun dumpCarrierConfigViaBinder(context: Context, subId: Int): String? =
            withContext(Dispatchers.IO) {
                try {
                    val iCarrierConfig = getICarrierConfigViaShizuku() ?: run {
                        Log.w(TAG, "dumpCarrierConfigViaBinder: ICarrierConfig unavailable")
                        return@withContext null
                    }
                    val config = iCarrierConfig.javaClass
                        .getMethod("getConfigForSubId", Int::class.javaPrimitiveType)
                        .invoke(iCarrierConfig, subId) as? PersistableBundle
                        ?: return@withContext ""
                    buildConfigDumpText(config)
                } catch (t: Throwable) {
                    Log.e(TAG, "dumpCarrierConfigViaBinder: failed: ${t.javaClass.name}: ${t.message}", t)
                    null
                }
            }

        /**
         * 通过 Shizuku Binder 直接调用 ITelephony.isImsRegistered 读取 IMS 注册状态。
         */
        private suspend fun readImsRegistrationStatusViaBinder(
            context: Context,
            subId: Int,
        ): Boolean? = withContext(Dispatchers.IO) {
            try {
                if (subId < 0) {
                    Log.w(TAG, "readImsRegistrationStatusViaBinder: invalid subId=$subId")
                    return@withContext null
                }
                val telephony = getITelephonyViaShizuku() ?: run {
                    Log.w(TAG, "readImsRegistrationStatusViaBinder: ITelephony unavailable")
                    return@withContext null
                }
                val isRegistered = telephony.isImsRegistered(subId)
                Log.i(TAG, "readImsRegistrationStatusViaBinder: subId=$subId registered=$isRegistered")
                isRegistered
            } catch (t: Throwable) {
                Log.e(TAG, "readImsRegistrationStatusViaBinder: failed: ${t.javaClass.name}: ${t.message}", t)
                null
            }
        }

        /**
         * 通过 Shizuku Binder 直接调用 ITelephony.resetIms 重启 IMS 注册，复刻 ImsResetter 逻辑。
         */
        private suspend fun restartImsRegistrationViaBinder(context: Context, subId: Int): String? =
            withContext(Dispatchers.IO) {
                try {
                    val sm = context.getSystemService(SubscriptionManager::class.java)
                        ?: throw IllegalStateException("SubscriptionManager unavailable")
                    val subIds: IntArray = if (subId == -1) {
                        getActiveSubIds(sm)
                    } else {
                        intArrayOf(subId)
                    }
                    val telephony = getITelephonyViaShizuku()
                        ?: throw IllegalStateException("ITelephony unavailable")
                    val sub = getISubViaShizuku()
                    for (id in subIds) {
                        val slotIndex = sub?.getSlotIndex(id) ?: 0
                        Log.i(
                            TAG,
                            "restartImsRegistrationViaBinder: subId=$id slot=$slotIndex" +
                                " (subSvc=${if (sub == null) "fallback" else "isub"})",
                        )
                        telephony.resetIms(slotIndex)
                    }
                    null
                } catch (t: Throwable) {
                    Log.e(TAG, "restartImsRegistrationViaBinder: failed: ${t.javaClass.name}: ${t.message}", t)
                    t.message ?: t.javaClass.simpleName
                }
            }

        /**
         * 通过 contentResolver 直接写 APN，复刻 ApnModifier 的 ContentValues 构造与
         * update/insert + preferapn 设置逻辑。ShizukuProvider 调用经 Shizuku 路由，
         * 拥有 shell 权限（含 WRITE_APN_SETTINGS）足以操作 telephony/carriers。
         */
        private suspend fun applyApnConfigViaBinder(
            context: Context,
            subId: Int,
            config: ApnDraftConfig,
        ): String? = withContext(Dispatchers.IO) {
            try {
                applyApnConfigViaBinderInternal(context, subId, config)
                null
            } catch (t: Throwable) {
                Log.e(TAG, "applyApnConfigViaBinder: failed: ${t.javaClass.name}: ${t.message}", t)
                t.message ?: t.javaClass.simpleName
            }
        }

        private fun applyApnConfigViaBinderInternal(
            context: Context,
            subId: Int,
            config: ApnDraftConfig,
        ) {
            val name = config.name.trim()
            val apn = config.apn.trim()
            val type = config.type.trim().ifBlank { "default,supl,ims" }
            val mcc = config.mcc.filter { it.isDigit() }.take(3)
            val mnc = config.mnc.filter { it.isDigit() }.take(3)
            require(subId >= 0) { "invalid subId" }
            require(name.isNotBlank()) { "APN name is blank" }
            require(apn.isNotBlank()) { "APN is blank" }
            require(mcc.length == 3) { "MCC must be 3 digits" }
            require(mnc.length in 2..3) { "MNC must be 2 or 3 digits" }

            val numeric = mcc + mnc
            val values = ContentValues().apply {
                put("name", name)
                put("apn", apn)
                put("type", type)
                put("mcc", mcc)
                put("mnc", mnc)
                put("numeric", numeric)
                put("protocol", "IPV4V6")
                put("roaming_protocol", "IPV4V6")
                put("carrier_enabled", 1)
                put("edited", 1)
                put("sub_id", subId)
            }

            val resolver = context.contentResolver
            val apnUri = Telephony.Carriers.CONTENT_URI
            val existingId = findExistingApnId(resolver, apnUri, subId, numeric, apn, type)
            val apnId = if (existingId != null) {
                val updated = resolver.update(
                    Uri.withAppendedPath(apnUri, existingId.toString()),
                    values,
                    null,
                    null,
                )
                if (updated <= 0) throw IllegalStateException("update APN failed")
                existingId
            } else {
                val inserted = resolver.insert(apnUri, values)
                    ?: throw IllegalStateException("insert APN failed")
                inserted.lastPathSegment?.toLongOrNull()
                    ?: throw IllegalStateException("invalid APN id: $inserted")
            }
            val preferValues = ContentValues().apply { put("apn_id", apnId) }
            val preferredUpdated = resolver.update(
                Uri.parse("$PREFER_APN_URI_PREFIX$subId"),
                preferValues,
                null,
                null,
            )
            if (preferredUpdated <= 0) throw IllegalStateException("set preferred APN failed")
            Log.i(TAG, "applyApnConfigViaBinder: subId=$subId id=$apnId name=$name apn=$apn")
        }

        private fun findExistingApnId(
            resolver: ContentResolver,
            apnUri: Uri,
            subId: Int,
            numeric: String,
            apn: String,
            type: String,
        ): Long? {
            val queries = listOf(
                "numeric=? AND apn=? AND type=? AND sub_id=?" to
                    arrayOf(numeric, apn, type, subId.toString()),
                "numeric=? AND apn=? AND type=?" to arrayOf(numeric, apn, type),
            )
            for ((selection, args) in queries) {
                val existing = runCatching {
                    resolver.query(apnUri, arrayOf("_id"), selection, args, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getLong(0) else null
                    }
                }.getOrNull()
                if (existing != null) return existing
            }
            return null
        }

        /**
         * 直接读 Settings.Global 的 captive portal URL，复刻 CaptivePortalFixer query 路径。
         */
        private suspend fun queryCaptivePortalConfigViaBinder(context: Context): CaptivePortalConfig? =
            withContext(Dispatchers.IO) {
                try {
                    val resolver = context.contentResolver
                    val http = Settings.Global.getString(resolver, KEY_CAPTIVE_PORTAL_HTTP_URL).orEmpty()
                    val https = Settings.Global.getString(resolver, KEY_CAPTIVE_PORTAL_HTTPS_URL).orEmpty()
                    fillCaptivePortalState(http, https)
                } catch (t: Throwable) {
                    Log.e(TAG, "queryCaptivePortalConfigViaBinder: failed: ${t.javaClass.name}: ${t.message}", t)
                    null
                }
            }

        /**
         * 直接写 Settings.Global 把 captive portal URL 改为 CN URL，复刻 CaptivePortalFixer apply_cn。
         */
        private suspend fun applyCaptivePortalCnUrlsViaBinder(context: Context): String? =
            withContext(Dispatchers.IO) {
                try {
                    val resolver = context.contentResolver
                    val setHttp = Settings.Global.putString(resolver, KEY_CAPTIVE_PORTAL_HTTP_URL, CN_HTTP_URL)
                    val setHttps = Settings.Global.putString(resolver, KEY_CAPTIVE_PORTAL_HTTPS_URL, CN_HTTPS_URL)
                    val currentHttp = Settings.Global.getString(resolver, KEY_CAPTIVE_PORTAL_HTTP_URL).orEmpty()
                    val currentHttps = Settings.Global.getString(resolver, KEY_CAPTIVE_PORTAL_HTTPS_URL).orEmpty()
                    val verified =
                        setHttp && setHttps && currentHttp == CN_HTTP_URL && currentHttps == CN_HTTPS_URL
                    if (!verified) {
                        "verify failed, http=$currentHttp, https=$currentHttps, putHttp=$setHttp, putHttps=$setHttps"
                    } else {
                        null
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "applyCaptivePortalCnUrlsViaBinder: failed: ${t.javaClass.name}: ${t.message}", t)
                    t.message ?: t.javaClass.simpleName
                }
            }

        /**
         * 直接写 Settings.Global 把 captive portal URL 清空，恢复默认，复刻 CaptivePortalFixer restore。
         */
        private suspend fun restoreCaptivePortalDefaultUrlsViaBinder(context: Context): String? =
            withContext(Dispatchers.IO) {
                try {
                    val resolver = context.contentResolver
                    val setHttp = Settings.Global.putString(resolver, KEY_CAPTIVE_PORTAL_HTTP_URL, null)
                    val setHttps = Settings.Global.putString(resolver, KEY_CAPTIVE_PORTAL_HTTPS_URL, null)
                    val currentHttp = Settings.Global.getString(resolver, KEY_CAPTIVE_PORTAL_HTTP_URL).orEmpty()
                    val currentHttps = Settings.Global.getString(resolver, KEY_CAPTIVE_PORTAL_HTTPS_URL).orEmpty()
                    val verified = setHttp && setHttps && currentHttp.isBlank() && currentHttps.isBlank()
                    if (!verified) {
                        "restore failed, http=$currentHttp, https=$currentHttps, putHttp=$setHttp, putHttps=$setHttps"
                    } else {
                        null
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "restoreCaptivePortalDefaultUrlsViaBinder: failed: ${t.javaClass.name}: ${t.message}", t)
                    t.message ?: t.javaClass.simpleName
                }
            }

        private fun fillCaptivePortalState(http: String, https: String): CaptivePortalConfig =
            CaptivePortalConfig(
                httpUrl = http,
                httpsUrl = https,
                isCnUrl = http == CN_HTTP_URL && https == CN_HTTPS_URL,
                isOverridden = http.isNotBlank() || https.isNotBlank(),
            )

        @Suppress("DEPRECATION")
        private fun putConfigValue(bundle: Bundle, key: String, value: Any?) {
            when (value) {
                is Int -> bundle.putInt(key, value)
                is Long -> bundle.putLong(key, value)
                is Double -> bundle.putDouble(key, value)
                is String -> bundle.putString(key, value)
                is Boolean -> bundle.putBoolean(key, value)
                is IntArray -> bundle.putIntArray(key, value)
                is LongArray -> bundle.putLongArray(key, value)
                is DoubleArray -> bundle.putDoubleArray(key, value)
                is BooleanArray -> bundle.putBooleanArray(key, value)
                is Array<*> -> if (value.isArrayOf<String>()) {
                    @Suppress("UNCHECKED_CAST")
                    bundle.putStringArray(key, value as Array<String>)
                }
            }
        }

        private fun buildConfigDumpText(config: PersistableBundle): String {
            val fields = listOf(
                CarrierConfigManager::class.java,
                *CarrierConfigManager::class.java.declaredClasses,
            ).flatMap { it.declaredFields.asList() }
                .filter { it.name != "KEY_PREFIX" && it.name.startsWith("KEY_") }
                .sortedBy { it.name }
            val lines = fields.map { field ->
                val key = try {
                    field.get(field) as String
                } catch (_: Throwable) {
                    null
                }
                if (key == null) {
                    "${field.name}: (invalid)"
                } else {
                    val value = config.get(key)
                    "${field.name}: ${configValueToString(value)}"
                }
            }
            return lines.joinToString("\n")
        }

        private fun configValueToString(value: Any?): String = when (value) {
            null -> "null"
            is IntArray -> value.joinToString(",")
            is LongArray -> value.joinToString(",")
            is DoubleArray -> value.joinToString(",")
            is BooleanArray -> value.joinToString(",")
            is Array<*> -> value.joinToString(",")
            else -> value.toString()
        }

        @Suppress("DEPRECATION")
        private fun Bundle.toPersistableBundleForOverride(): PersistableBundle {
            val pb = PersistableBundle()
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
                            @Suppress("UNCHECKED_CAST")
                            pb.putStringArray(key, value as Array<String>)
                        } else {
                            Log.i(TAG, "toPersistableBundleForOverride: unsupported type for key $key")
                        }
                    }
                }
            }
            return pb
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
