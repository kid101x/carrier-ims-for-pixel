#!/usr/bin/env python3
# Patch three files for Task 14:
#   1. AndroidManifest.xml: remove SIM2 services, register CarrierProfileActivity
#   2. values/strings.xml: delete tiktok/region_tiktok/sim_2 strings, add carrier_profile_*
#   3. values-zh-rCN/strings.xml: same as above
import io


def patch(path, edits):
    """edits: list of (old, new, expected_count)."""
    with io.open(path, "r", encoding="utf-8") as f:
        src = f.read()
    for old, new, expected in edits:
        actual = src.count(old)
        assert actual == expected, (
            "PATCH FAIL %s: anchor count=%d expected=%d\nANCHOR:\n%s"
            % (path, actual, expected, old[:200])
        )
        src = src.replace(old, new, expected)
    with io.open(path, "w", encoding="utf-8") as f:
        f.write(src)
    print("OK patched %s (%d edits)" % (path, len(edits)))


# ---------- 1. AndroidManifest.xml ----------
manifest = "/tmp/carrier-ims/app/src/main/AndroidManifest.xml"

# Insert CarrierProfileActivity registration after DumpActivity block
dump_block = (
    '        <activity\n'
    '            android:name="io.github.vvb2060.ims.ui.DumpActivity"\n'
    '            android:exported="false"\n'
    '            android:theme="@style/Theme.TurbolIms" />\n'
)
carrier_activity = (
    dump_block +
    '        <activity\n'
    '            android:name="io.github.vvb2060.ims.ui.CarrierProfileActivity"\n'
    '            android:exported="false"\n'
    '            android:theme="@style/Theme.TurbolIms" />\n'
)

# SIM2VoLTETileService service block (lines 44-54)
sim2_volte_service = (
    '        <service\n'
    '            android:name="io.github.vvb2060.ims.tiles.SIM2VoLTETileService"\n'
    '            android:exported="true"\n'
    '            android:label="@string/qs_toggle_tile_title_sim_2"\n'
    '            android:icon="@mipmap/ic_launcher"\n'
    '            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">\n'
    '            <intent-filter>\n'
    '                <action android:name="android.service.quicksettings.action.QS_TILE" />\n'
    '            </intent-filter>\n'
    '            <meta-data android:name="android.service.quicksettings.TOGGLEABLE_TILE" android:value="true" />\n'
    '        </service>\n'
)

# SIM2IMSStatusTileService service block (lines 65-74)
sim2_ims_service = (
    '        <service\n'
    '            android:name="io.github.vvb2060.ims.tiles.SIM2IMSStatusTileService"\n'
    '            android:exported="true"\n'
    '            android:label="@string/qs_status_tile_title_sim_2"\n'
    '            android:icon="@mipmap/ic_launcher"\n'
    '            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">\n'
    '            <intent-filter>\n'
    '                <action android:name="android.service.quicksettings.action.QS_TILE" />\n'
    '            </intent-filter>\n'
    '        </service>\n'
)

patch(manifest, [
    (dump_block, carrier_activity, 1),
    (sim2_volte_service, '', 1),
    (sim2_ims_service, '', 1),
])


# ---------- 2. values/strings.xml ----------
en_strings = "/tmp/carrier-ims/app/src/main/res/values/strings.xml"

en_deletions = [
    '    <string name="tiktok_network_fix">Fix TikTok No Network</string>\n',
    '    <string name="tiktok_network_fix_desc">Domestic SIM only: ON uses random numeric ISO; OFF restores country ISO by SIM home profile.</string>\n',
    '    <string name="tiktok_network_fix_unavailable">Available only for mainland China SIMs.</string>\n',
    '    <string name="region_tiktok_applicable">TikTok Fix</string>\n',
    '    <string name="qs_add_volte_sim_2">VoLTE SIM 2</string>\n',
    '    <string name="qs_add_ims_sim_2">IMS SIM 2</string>\n',
    '    <string name="qs_toggle_tile_title_sim_2">VoLTE Toggle (SIM 2)</string>\n',
    '    <string name="qs_status_tile_title_sim_2">IMS Status (SIM 2)</string>\n',
]

en_new_block = (
    '    <!-- Carrier Profile -->\n'
    '    <string name="carrier_profile_title">Carrier Profile</string>\n'
    '    <string name="carrier_profile_apply">Apply Profile</string>\n'
    '    <string name="carrier_profile_experimental_warning">This profile is experimental. On Pixel 1 (Android 10), the carrier may not support VoLTE IMS registration. Continue?</string>\n'
    '    <string name="carrier_profile_apply_success">Profile applied. VoLTE is now available.</string>\n'
    '    <string name="carrier_profile_apply_failed">Profile apply failed: VoLTE not available after write.</string>\n'
    '    <string name="carrier_profile_vowifi_unavailable">VoWiFi platform capability is unavailable on this firmware.</string>\n'
    '    <string name="carrier_profile_data_apn_label">Data APN</string>\n'
    '    <string name="carrier_profile_ims_apn_label">IMS APN</string>\n'
    '    <string name="carrier_profile_carrier_config_label">Carrier Config Keys</string>\n'
    '\n'
)

# Insert new carrier_profile block right before QS Tiles section header
en_qs_anchor = '    <!-- QS Tiles -->\n'

# Build edits: delete 8 strings + insert new block before QS Tiles
en_edits = [(s, '', 1) for s in en_deletions]
en_edits.append((en_qs_anchor, en_new_block + en_qs_anchor, 1))
patch(en_strings, en_edits)


# ---------- 3. values-zh-rCN/strings.xml ----------
zh_strings = "/tmp/carrier-ims/app/src/main/res/values-zh-rCN/strings.xml"

zh_deletions = [
    '    <string name="tiktok_network_fix">修复 TikTok 无网络</string>\n',
    '    <string name="tiktok_network_fix_desc">仅大陆SIM有此选项，海外SIM无需修复</string>\n',
    '    <string name="tiktok_network_fix_unavailable">仅大陆 SIM 适用</string>\n',
    '    <string name="region_tiktok_applicable">TikTok 修复</string>\n',
    '    <string name="qs_add_volte_sim_2">VoLTE SIM 2</string>\n',
    '    <string name="qs_add_ims_sim_2">IMS SIM 2</string>\n',
    '    <string name="qs_toggle_tile_title_sim_2">VoLTE 开关（SIM 2）</string>\n',
    '    <string name="qs_status_tile_title_sim_2">IMS 状态（SIM 2）</string>\n',
]

zh_new_block = (
    '    <!-- Carrier Profile -->\n'
    '    <string name="carrier_profile_title">运营商配置</string>\n'
    '    <string name="carrier_profile_apply">应用预设</string>\n'
    '    <string name="carrier_profile_experimental_warning">此预设为实验性。Pixel 1（Android 10）下电信运营商可能不支持 VoLTE IMS 注册，是否继续？</string>\n'
    '    <string name="carrier_profile_apply_success">预设已应用，VoLTE 已可用。</string>\n'
    '    <string name="carrier_profile_apply_failed">预设应用失败：写入后 VoLTE 仍不可用。</string>\n'
    '    <string name="carrier_profile_vowifi_unavailable">当前固件不支持 VoWiFi 平台能力。</string>\n'
    '    <string name="carrier_profile_data_apn_label">数据 APN</string>\n'
    '    <string name="carrier_profile_ims_apn_label">IMS APN</string>\n'
    '    <string name="carrier_profile_carrier_config_label">CarrierConfig 键</string>\n'
    '\n'
)

zh_qs_anchor = '    <!-- QS Tiles -->\n'

zh_edits = [(s, '', 1) for s in zh_deletions]
zh_edits.append((zh_qs_anchor, zh_new_block + zh_qs_anchor, 1))
patch(zh_strings, zh_edits)

print("DONE")
