package io.github.vvb2060.ims.model

import android.os.Bundle
import android.telephony.CarrierConfigManager

object FeatureConfigMapper {
    private const val KEY_SIM_COUNTRY_ISO_OVERRIDE = "sim_country_iso_override_string"

    val readKeys: Array<String> = linkedSetOf(
        CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL,
        CarrierConfigManager.KEY_CARRIER_NAME_STRING,
        CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL,
        CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL,
        CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL,
        CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL,
        CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL,
        CarrierConfigManager.KEY_ENABLE_CROSS_SIM_CALLING_ON_OPPORTUNISTIC_DATA_BOOL,
        KEY_SIM_COUNTRY_ISO_OVERRIDE,
    ).toTypedArray()

    fun fromBundle(bundle: Bundle): Map<Feature, FeatureValue> {
        val map = linkedMapOf<Feature, FeatureValue>()

        val carrierNameOverride = bundle.getBooleanOrDefault(
            CarrierConfigManager.KEY_CARRIER_NAME_OVERRIDE_BOOL,
            false
        )
        val carrierName = if (carrierNameOverride) {
            bundle.getStringOrDefault(CarrierConfigManager.KEY_CARRIER_NAME_STRING, "")
        } else {
            ""
        }
        map[Feature.CARRIER_NAME] = FeatureValue(carrierName, FeatureValueType.STRING)

        val countryIso = bundle.getStringOrDefault(KEY_SIM_COUNTRY_ISO_OVERRIDE, "")
        map[Feature.COUNTRY_ISO] = FeatureValue(countryIso, FeatureValueType.STRING)

        map[Feature.VOLTE] = FeatureValue(
            bundle.getBooleanOrDefault(
                CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL,
                Feature.VOLTE.defaultValue as Boolean
            ),
            FeatureValueType.BOOLEAN
        )

        map[Feature.VOWIFI] = FeatureValue(
            bundle.getBooleanOrDefault(
                CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL,
                Feature.VOWIFI.defaultValue as Boolean
            ),
            FeatureValueType.BOOLEAN
        )

        map[Feature.VT] = FeatureValue(
            bundle.getBooleanOrDefault(
                CarrierConfigManager.KEY_CARRIER_VT_AVAILABLE_BOOL,
                Feature.VT.defaultValue as Boolean
            ),
            FeatureValueType.BOOLEAN
        )

        val crossSimEnabled = bundle.getBooleanOrDefault(
            CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL,
            false
        ) && bundle.getBooleanOrDefault(
            CarrierConfigManager.KEY_ENABLE_CROSS_SIM_CALLING_ON_OPPORTUNISTIC_DATA_BOOL,
            false
        )
        map[Feature.CROSS_SIM] = FeatureValue(crossSimEnabled, FeatureValueType.BOOLEAN)

        map[Feature.UT] = FeatureValue(
            bundle.getBooleanOrDefault(
                CarrierConfigManager.KEY_CARRIER_SUPPORTS_SS_OVER_UT_BOOL,
                Feature.UT.defaultValue as Boolean
            ),
            FeatureValueType.BOOLEAN
        )

        return map
    }

    private fun Bundle.getBooleanOrDefault(key: String, default: Boolean): Boolean {
        return if (containsKey(key)) getBoolean(key) else default
    }

    private fun Bundle.getStringOrDefault(key: String, default: String): String {
        return if (containsKey(key)) getString(key, default) else default
    }
}
