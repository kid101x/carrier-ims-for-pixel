package io.github.vvb2060.ims.model

import android.telephony.CarrierConfigManager

/**
 * 中国三家运营商 VoLTE 配置预设注册表。
 *
 * - 中国联通 (CUCC)：MCC=460, MNC=01, dataApn=wonet, imsApn=ims（首发优先）
 * - 中国移动 (CMCC)：MCC=460, MNC=00, dataApn=cmnet, imsApn=ims
 * - 中国电信 (CTCC)：MCC=460, MNC=11, dataApn=ctnet, imsApn=ims（实验性，基带固件限制可能无法注册）
 *
 * 详见 spec.md “中国运营商 VoLTE 配置矩阵” 与 docs/CHINA_VOLTE_PROFILES.md。
 */
object CarrierProfileRegistry {

    /** 中国联通（首发优先）。 */
    val CUCC = CarrierProfile(
        name = "中国联通",
        mcc = "460",
        mnc = "01",
        dataApn = ApnDraftConfig(
            name = "中国联通",
            apn = "wonet",
            type = "default,supl",
            mcc = "460",
            mnc = "01",
        ),
        imsApn = ApnDraftConfig(
            name = "IMS",
            apn = "ims",
            type = "ims",
            mcc = "460",
            mnc = "01",
        ),
        carrierConfigKeys = standardChinaCarrierConfigKeys(),
        experimental = false,
    )

    /** 中国移动。 */
    val CMCC = CarrierProfile(
        name = "中国移动",
        mcc = "460",
        mnc = "00",
        dataApn = ApnDraftConfig(
            name = "中国移动",
            apn = "cmnet",
            type = "default,supl",
            mcc = "460",
            mnc = "00",
        ),
        imsApn = ApnDraftConfig(
            name = "IMS",
            apn = "ims",
            type = "ims",
            mcc = "460",
            mnc = "00",
        ),
        carrierConfigKeys = standardChinaCarrierConfigKeys(),
        experimental = false,
    )

    /** 中国电信（实验性，Pixel 1 基带固件限制可能无法注册 IMS）。 */
    val CTCC = CarrierProfile(
        name = "中国电信",
        mcc = "460",
        mnc = "11",
        dataApn = ApnDraftConfig(
            name = "中国电信",
            apn = "ctnet",
            type = "default,supl",
            mcc = "460",
            mnc = "11",
        ),
        imsApn = ApnDraftConfig(
            name = "IMS",
            apn = "ims",
            type = "ims",
            mcc = "460",
            mnc = "11",
        ),
        carrierConfigKeys = standardChinaCarrierConfigKeys(),
        experimental = true,
    )

    /** 三家运营商预设（顺序即 UI 展示顺序：联通 → 移动 → 电信）。 */
    val all: List<CarrierProfile> = listOf(CUCC, CMCC, CTCC)

    /**
     * 按 MCC/MNC 匹配预设。MCC/MNC 可为 null 或含非数字字符，方法内部会归一化（仅保留数字、
     * MCC 取前 3 位、MNC 取前 3 位）。未匹配返回 null。
     */
    fun match(mcc: String?, mnc: String?): CarrierProfile? {
        val normalizedMcc = mcc?.trim()?.filter { it.isDigit() }?.take(3)?.takeIf { it.length == 3 }
            ?: return null
        val normalizedMnc = mnc?.trim()?.filter { it.isDigit() }?.take(3)?.takeIf { it.length in 2..3 }
            ?: return null
        return all.firstOrNull { it.mcc == normalizedMcc && it.mnc == normalizedMnc }
    }

    /**
     * 三家中国运营商共用的标准 CarrierConfig 键值对。
     *
     * 与 [io.github.vvb2060.ims.privileged.ImsModifier.buildBundle] 在
     * `enableVoLTE=true, enableVoWiFi=true, enableVT=true, enableUT=true` 时写入的键一致，
     * 此处显式列出供 [io.github.vvb2060.ims.ui.CarrierProfileActivity] 摘要展示；
     * 底层 `buildBundle(profile, ...)` 重载会先按标志位写入，再遍历此 Map 幂等覆盖，无副作用。
     */
    private fun standardChinaCarrierConfigKeys(): Map<String, Any> = mapOf(
        CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL to true,
        CarrierConfigManager.KEY_EDITABLE_ENHANCED_4G_LTE_BOOL to true,
        CarrierConfigManager.KEY_HIDE_ENHANCED_4G_LTE_BOOL to false,
        CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL to true,
        CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL to true,
        CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL to true,
        CarrierConfigManager.KEY_CARRIER_WFC_SUPPORTS_WIFI_ONLY_BOOL to true,
        CarrierConfigManager.KEY_EDITABLE_WFC_MODE_BOOL to true,
        CarrierConfigManager.KEY_EDITABLE_WFC_ROAMING_MODE_BOOL to true,
        // wfc_spn_format_idx_int 不在 CarrierConfigManager 公开常量中，使用字面量。
        "wfc_spn_format_idx_int" to 6,
    )
}
