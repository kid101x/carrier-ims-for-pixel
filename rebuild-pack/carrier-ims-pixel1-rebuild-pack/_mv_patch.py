#!/usr/bin/env python3
# Patch MainViewModel.kt
import io, sys

p = "/tmp/carrier-ims/app/src/main/java/io/github/vvb2060/ims/viewmodel/MainViewModel.kt"
s = io.open(p, encoding="utf-8").read()
orig = s

def rep(old, new, count=1):
    global s
    n = s.count(old)
    assert n == count, f"expected {count} got {n} for:\n{old[:120]}"
    s = s.replace(old, new)

# 1) Add imports after ImsModifier import
rep(
    "import io.github.vvb2060.ims.privileged.ImsModifier\n",
    "import io.github.vvb2060.ims.privileged.ImsModifier\n"
    "import io.github.vvb2060.ims.privileged.ImsStatusReader\n"
    "import android.telephony.CarrierConfigManager\n"
    "import io.github.vvb2060.ims.model.CarrierProfile\n",
    1,
)

# 2) Add vowifi state + ProfileApplyResult after canUsePersistentOverride lazy block
rep(
    "    private val canUsePersistentOverride by lazy {\n"
    "        val flags = application.applicationInfo.flags\n"
    "        (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||\n"
    "            (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0\n"
    "    }\n",
    "    private val canUsePersistentOverride by lazy {\n"
    "        val flags = application.applicationInfo.flags\n"
    "        (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||\n"
    "            (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0\n"
    "    }\n"
    "\n"
    "    private val _vowifiPlatformAvailable = MutableStateFlow(true)\n"
    "    val vowifiPlatformAvailable: StateFlow<Boolean> = _vowifiPlatformAvailable.asStateFlow()\n"
    "\n"
    "    /** 运营商预设应用结果（CarrierConfig + APN 写入 + 回读校验 + VoWiFi 平台能力检测）。 */\n"
    "    data class ProfileApplyResult(\n"
    "        val success: Boolean,\n"
    "        val volteAvailable: Boolean,\n"
    "        val vowifiPlatformAvailable: Boolean,\n"
    "        val apnDataError: String?,\n"
    "        val apnImsError: String?,\n"
    "        val errorMessage: String?,\n"
    "    )\n",
    1,
)

# 3) Wire VoWiFi tolerance check into onApplyConfiguration success branch
rep(
    "        val resultMsg = ShizukuProvider.overrideImsConfig(application, bundle)\n"
    "        if (resultMsg == null) {\n"
    "            // 仅在应用成功后保存配置，避免本地状态与系统状态不一致\n"
    "            saveConfiguration(selectedSim.subId, map, countryMccOverride)\n"
    "        }\n"
    "        return resultMsg\n"
    "    }\n",
    "        val resultMsg = ShizukuProvider.overrideImsConfig(application, bundle)\n"
    "        if (resultMsg == null) {\n"
    "            // 仅在应用成功后保存配置，避免本地状态与系统状态不一致\n"
    "            saveConfiguration(selectedSim.subId, map, countryMccOverride)\n"
    "            refreshVowifiPlatformAvailable(selectedSim.subId)\n"
    "        }\n"
    "        return resultMsg\n"
    "    }\n",
    1,
)

# 4) Add refreshVowifiPlatformAvailable + applyCarrierProfile methods right after onApplyConfiguration closes.
#    onApplyConfiguration ends with "        return resultMsg\n    }\n" which we already patched; insert after it.
rep(
    "        val resultMsg = ShizukuProvider.overrideImsConfig(application, bundle)\n"
    "        if (resultMsg == null) {\n"
    "            // 仅在应用成功后保存配置，避免本地状态与系统状态不一致\n"
    "            saveConfiguration(selectedSim.subId, map, countryMccOverride)\n"
    "            refreshVowifiPlatformAvailable(selectedSim.subId)\n"
    "        }\n"
    "        return resultMsg\n"
    "    }\n",
    "        val resultMsg = ShizukuProvider.overrideImsConfig(application, bundle)\n"
    "        if (resultMsg == null) {\n"
    "            // 仅在应用成功后保存配置，避免本地状态与系统状态不一致\n"
    "            saveConfiguration(selectedSim.subId, map, countryMccOverride)\n"
    "            refreshVowifiPlatformAvailable(selectedSim.subId)\n"
    "        }\n"
    "        return resultMsg\n"
    "    }\n"
    "\n"
    "    /**\n"
    "     * 检测系统层 VoWiFi 平台能力并刷新状态。Task 11.2：应用配置后调用，\n"
    "     * 若返回 false 则 UI 将 Feature.VOWIFI 开关置灰并显示“当前固件不支持”文案。\n"
    "     */\n"
    "    private suspend fun refreshVowifiPlatformAvailable(subId: Int) {\n"
    "        if (subId < 0) return\n"
    "        val available = ImsStatusReader.isWfcEnabledByPlatform(application, subId)\n"
    "        _vowifiPlatformAvailable.value = available\n"
    "        Log.i(TAG, \"refreshVowifiPlatformAvailable: subId=$subId available=$available\")\n"
    "    }\n"
    "\n"
    "    /**\n"
    "     * 应用运营商预设：写入 CarrierConfig（ImsModifier instrumentation）+ 数据/IMS APN（ApnModifier\n"
    "     * instrumentation），随后回读 KEY_CARRIER_VOLTE_AVAILABLE_BOOL 校验，并检测 VoWiFi 平台能力。\n"
    "     */\n"
    "    suspend fun applyCarrierProfile(profile: CarrierProfile, subId: Int): ProfileApplyResult =\n"
    "        Companion.applyCarrierProfile(application, profile, subId)\n",
    1,
)

# 5) Add companion.applyCarrierProfile + ProfileApplyResult factory inside companion object.
#    Anchor: companion object starts with "    companion object {\n        private const val TAG = \"MainViewModel\""
rep(
    "    companion object {\n"
    "        private const val TAG = \"MainViewModel\"\n",
    "    companion object {\n"
    "        private const val TAG = \"MainViewModel\"\n"
    "\n"
    "        /**\n"
    "         * 静态入口：基于 [CarrierProfile] 写入 CarrierConfig + APN 并回读校验。\n"
    "         * 供 [CarrierProfileViewModel] 直接复用，无需持有 MainViewModel 实例。\n"
    "         */\n"
    "        suspend fun applyCarrierProfile(\n"
    "            app: Application,\n"
    "            profile: CarrierProfile,\n"
    "            subId: Int,\n"
    "        ): ProfileApplyResult {\n"
    "            if (subId < 0) {\n"
    "                return ProfileApplyResult(false, false, false, null, null, \"invalid subId\")\n"
    "            }\n"
    "            val flags = app.applicationInfo.flags\n"
    "            val canUsePersistent = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||\n"
    "                (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0\n"
    "            val bundle = ImsModifier.buildBundle(\n"
    "                profile = profile,\n"
    "                enableVoLTE = true,\n"
    "                enableVoWiFi = true,\n"
    "                enableVT = true,\n"
    "                enableUT = true,\n"
    "                enableCrossSIM = false,\n"
    "            )\n"
    "            bundle.putInt(ImsModifier.BUNDLE_SELECT_SIM_ID, subId)\n"
    "            bundle.putBoolean(ImsModifier.BUNDLE_PREFER_PERSISTENT, canUsePersistent)\n"
    "            val imsError = ShizukuProvider.overrideImsConfig(app, bundle)\n"
    "            if (imsError != null) {\n"
    "                Log.w(TAG, \"applyCarrierProfile: overrideImsConfig failed: $imsError\")\n"
    "                return ProfileApplyResult(false, false, false, null, null, imsError)\n"
    "            }\n"
    "            val apnDataError = ShizukuProvider.applyApnConfig(app, subId, profile.dataApn)\n"
    "            val apnImsError = ShizukuProvider.applyApnConfig(app, subId, profile.imsApn)\n"
    "            val readback = runCatching {\n"
    "                ShizukuProvider.readCarrierConfig(\n"
    "                    app,\n"
    "                    subId,\n"
    "                    arrayOf(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL),\n"
    "                )\n"
    "            }.getOrNull()\n"
    "            val volteAvailable = readback\n"
    "                ?.getBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL, false) ?: false\n"
    "            val vowifiPlatform = ImsStatusReader.isWfcEnabledByPlatform(app, subId)\n"
    "            Log.i(TAG, \"applyCarrierProfile: subId=$subId volte=$volteAvailable vowifi=$vowifiPlatform\")\n"
    "            return ProfileApplyResult(\n"
    "                success = true,\n"
    "                volteAvailable = volteAvailable,\n"
    "                vowifiPlatformAvailable = vowifiPlatform,\n"
    "                apnDataError = apnDataError,\n"
    "                apnImsError = apnImsError,\n"
    "                errorMessage = null,\n"
    "            )\n"
    "        }\n",
    1,
)

# 6) Remove vonr diagnostic entries (two lines)
rep(
    "            \"vonr_enabled_bool\" to \"VoNR\",\n"
    "            \"vonr_setting_visibility_bool\" to \"VoNR 开关可见\",\n",
    "",
    1,
)

if s == orig:
    print("NO CHANGE", file=sys.stderr); sys.exit(1)
io.open(p, "w", encoding="utf-8").write(s)
print("OK MainViewModel patched, delta bytes:", len(s) - len(orig))
