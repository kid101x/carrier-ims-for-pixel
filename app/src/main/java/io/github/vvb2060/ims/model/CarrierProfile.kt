package io.github.vvb2060.ims.model

/**
 * 中国运营商 VoLTE 配置预设。
 *
 * 配合 [CarrierProfileRegistry] 使用，由 [io.github.vvb2060.ims.privileged.ImsModifier]
 * 的 `buildBundle(profile, ...)` 重载写入 CarrierConfig，再由
 * [io.github.vvb2060.ims.ShizukuProvider.applyApnConfig] 写入数据 / IMS APN。
 *
 * @param name 运营商显示名（如“中国联通”），同时作为 `KEY_CARRIER_NAME_STRING` 写入。
 * @param mcc 3 位数字 MCC
 * @param mnc 2-3 位数字 MNC
 * @param dataApn 数据 APN 写入参数
 * @param imsApn IMS APN 写入参数
 * @param carrierConfigKeys CarrierConfig 键值对，值类型限定为 Boolean / Int / String / Long / IntArray；
 *                           底层 `buildBundle` 已写入标准 VoLTE/VoWiFi/VT/UT 键，此处用于显式声明与 UI 摘要展示。
 * @param experimental 实验性预设（如电信），UI 写入前要求用户二次确认。
 */
data class CarrierProfile(
    val name: String,
    val mcc: String,
    val mnc: String,
    val dataApn: ApnDraftConfig,
    val imsApn: ApnDraftConfig,
    val carrierConfigKeys: Map<String, Any>,
    val experimental: Boolean = false,
)
