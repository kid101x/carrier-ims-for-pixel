#!/usr/bin/env python3
# Patch MainActivity.kt
import io, sys

p = "/tmp/carrier-ims/app/src/main/java/io/github/vvb2060/ims/ui/MainActivity.kt"
s = io.open(p, encoding="utf-8").read()
orig = s

def rep(old, new, count=1):
    global s
    n = s.count(old)
    assert n == count, f"expected {count} got {n} for:\n{old[:160]}"
    s = s.replace(old, new)

# 1) Remove SIM2 tile imports (2 lines)
rep(
    "import io.github.vvb2060.ims.tiles.SIM1IMSStatusTileService\n"
    "import io.github.vvb2060.ims.tiles.SIM1VoLTETileService\n"
    "import io.github.vvb2060.ims.tiles.SIM2IMSStatusTileService\n"
    "import io.github.vvb2060.ims.tiles.SIM2VoLTETileService\n",
    "import io.github.vvb2060.ims.tiles.SIM1IMSStatusTileService\n"
    "import io.github.vvb2060.ims.tiles.SIM1VoLTETileService\n",
    1,
)

# 2) Collect vowifiPlatformAvailable state alongside other viewModel states
rep(
    "        val issueFailureLogs by viewModel.issueFailureLogs.collectAsStateWithLifecycle()\n",
    "        val issueFailureLogs by viewModel.issueFailureLogs.collectAsStateWithLifecycle()\n"
    "        val vowifiPlatformAvailable by viewModel.vowifiPlatformAvailable.collectAsStateWithLifecycle()\n",
    1,
)

# 3+4) FeaturesCard call: replace showTikTokFix arg with vowifiPlatformAvailable arg
rep(
    "                        showTikTokFix = false,\n",
    "                        vowifiPlatformAvailable = vowifiPlatformAvailable,\n",
    1,
)

# 5) Insert CarrierProfileEntryCard invocation inside IMS block (after FeaturesCard call)
rep(
    "                    )\n"
    "                }\n"
    "                if (selectedTab == MainTab.EXTRA) {\n",
    "                    )\n"
    "                    CarrierProfileEntryCard(\n"
    "                        onClick = {\n"
    "                            startActivity(\n"
    "                                Intent(this@MainActivity, CarrierProfileActivity::class.java)\n"
    "                            )\n"
    "                        },\n"
    "                    )\n"
    "                }\n"
    "                if (selectedTab == MainTab.EXTRA) {\n",
    1,
)

# 6) Remove tiktokEnabled arg from ExtraToolsPage call
rep(
    "                        allSimList = extraSimList,\n"
    "                        tiktokEnabled = false,\n"
    "                        featureSwitchesEnabled = !applyingConfiguration,\n",
    "                        allSimList = extraSimList,\n"
    "                        featureSwitchesEnabled = !applyingConfiguration,\n",
    1,
)

# 7) Remove onTikTokFixChange arg from ExtraToolsPage call
rep(
    "                        onFixCaptivePortal = fixCaptivePortalAction,\n"
    "                        onTikTokFixChange = { },\n"
    "                        onCheckNetworkExit = {\n",
    "                        onFixCaptivePortal = fixCaptivePortalAction,\n"
    "                        onCheckNetworkExit = {\n",
    1,
)

# 8) Remove tiktokEnabled param from ExtraToolsPage signature
rep(
    "    allSimList: List<SimSelection>,\n"
    "    tiktokEnabled: Boolean,\n"
    "    featureSwitchesEnabled: Boolean,\n",
    "    allSimList: List<SimSelection>,\n"
    "    featureSwitchesEnabled: Boolean,\n",
    1,
)

# 9) Remove onTikTokFixChange param from ExtraToolsPage signature
rep(
    "    onFixCaptivePortal: () -> Unit,\n"
    "    onTikTokFixChange: (Boolean) -> Unit,\n"
    "    onCheckNetworkExit: () -> Unit,\n",
    "    onFixCaptivePortal: () -> Unit,\n"
    "    onCheckNetworkExit: () -> Unit,\n",
    1,
)

# 10) Remove TiktokFixCard invocation inside ExtraToolsPage body
rep(
    "    TiktokFixCard(\n"
    "        enabled = tiktokEnabled,\n"
    "        available = isDomestic,\n"
    "        switchEnabled = featureSwitchesEnabled && (selectedSim?.subId ?: -1) >= 0,\n"
    "        onCheckedChange = onTikTokFixChange,\n"
    "    )\n",
    "",
    1,
)

# 11) Remove the "TikTok Fix" KeyValueRow in RegionCompatibilityCard
rep(
    "            KeyValueRow(\n"
    "                stringResource(R.string.region_tiktok_applicable),\n"
    "                stringResource(\n"
    "                    if (isDomestic) {\n"
    "                        R.string.region_status_applicable\n"
    "                    } else {\n"
    "                        R.string.region_status_not_applicable\n"
    "                    }\n"
    "                ),\n"
    "            )\n",
    "",
    1,
)

# 12+13) Replace TiktokFixCard Composable definition with CarrierProfileEntryCard
rep(
    "\n\n@Composable\n"
    "private fun TiktokFixCard(\n"
    "    enabled: Boolean,\n"
    "    available: Boolean,\n"
    "    switchEnabled: Boolean,\n"
    "    onCheckedChange: (Boolean) -> Unit,\n"
    ") {\n"
    "    Card(\n"
    "        modifier = Modifier\n"
    "            .fillMaxWidth()\n"
    "            .padding(horizontal = 16.dp)\n"
    "            .padding(bottom = 16.dp)\n"
    "    ) {\n"
    "        Column(modifier = Modifier.padding(12.dp)) {\n"
    "            BooleanFeatureItem(\n"
    "                title = stringResource(R.string.tiktok_network_fix),\n"
    "                description = if (available) {\n"
    "                    stringResource(R.string.tiktok_network_fix_desc)\n"
    "                } else {\n"
    "                    stringResource(R.string.tiktok_network_fix_unavailable)\n"
    "                },\n"
    "                checked = enabled && available,\n"
    "                enabled = switchEnabled && available,\n"
    "                onCheckedChange = onCheckedChange,\n"
    "            )\n"
    "        }\n"
    "    }\n"
    "}\n",
    "\n\n@Composable\n"
    "private fun CarrierProfileEntryCard(\n"
    "    onClick: () -> Unit,\n"
    ") {\n"
    "    Card(\n"
    "        modifier = Modifier\n"
    "            .fillMaxWidth()\n"
    "            .padding(horizontal = 16.dp)\n"
    "            .padding(bottom = 16.dp)\n"
    "    ) {\n"
    "        Column(\n"
    "            modifier = Modifier\n"
    "                .fillMaxWidth()\n"
    "                .clickable(onClick = onClick)\n"
    "                .padding(16.dp),\n"
    "        ) {\n"
    "            Text(\n"
    "                text = stringResource(R.string.carrier_profile_title),\n"
    "                fontSize = 16.sp,\n"
    "                fontWeight = FontWeight.SemiBold,\n"
    "            )\n"
    "            Text(\n"
    "                text = stringResource(R.string.carrier_profile_apply),\n"
    "                fontSize = 12.sp,\n"
    "                color = MaterialTheme.colorScheme.outline,\n"
    "            )\n"
    "        }\n"
    "    }\n"
    "}\n",
    1,
)

# 14) Remove SIM2 QuickTileButton row in QuickSettingsGuideCard
rep(
    "            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n"
    "                QuickTileButton(\n"
    "                    label = stringResource(R.string.qs_add_volte_sim_2),\n"
    "                    modifier = Modifier.weight(1f),\n"
    "                    onClick = {\n"
    "                        onAddQuickTile(\n"
    "                            SIM2VoLTETileService::class.java,\n"
    "                            R.string.qs_toggle_tile_title_sim_2\n"
    "                        )\n"
    "                    },\n"
    "                )\n"
    "                QuickTileButton(\n"
    "                    label = stringResource(R.string.qs_add_ims_sim_2),\n"
    "                    modifier = Modifier.weight(1f),\n"
    "                    onClick = {\n"
    "                        onAddQuickTile(\n"
    "                            SIM2IMSStatusTileService::class.java,\n"
    "                            R.string.qs_status_tile_title_sim_2\n"
    "                        )\n"
    "                    },\n"
    "                )\n"
    "            }\n",
    "",
    1,
)

# 15+16) FeaturesCard signature: replace showTikTokFix param with vowifiPlatformAvailable
rep(
    "    onRunDiagnostics: () -> Unit,\n"
    "    showTikTokFix: Boolean = true,\n",
    "    onRunDiagnostics: () -> Unit,\n"
    "    vowifiPlatformAvailable: Boolean = true,\n",
    1,
)

# 17) BOOLEAN branch: grey out VoWiFi switch when platform unavailable
rep(
    "                    FeatureValueType.BOOLEAN -> {\n"
    "                        BooleanFeatureItem(\n"
    "                            title = title,\n"
    "                            description = description,\n"
    "                            checked = (featureSwitches[feature]?.data ?: feature.defaultValue) as Boolean,\n"
    "                            enabled = featureSwitchesEnabled,\n"
    "                            onCheckedChange = {\n"
    "                                onFeatureSwitchChange(\n"
    "                                    feature,\n"
    "                                    FeatureValue(it, feature.valueType)\n"
    "                                )\n"
    "                            }\n"
    "                        )\n"
    "                    }\n",
    "                    FeatureValueType.BOOLEAN -> {\n"
    "                        val vowifiUnavailable = feature == Feature.VOWIFI && !vowifiPlatformAvailable\n"
    "                        BooleanFeatureItem(\n"
    "                            title = title,\n"
    "                            description = if (vowifiUnavailable) {\n"
    "                                stringResource(R.string.carrier_profile_vowifi_unavailable)\n"
    "                            } else {\n"
    "                                description\n"
    "                            },\n"
    "                            checked = (featureSwitches[feature]?.data ?: feature.defaultValue) as Boolean,\n"
    "                            enabled = featureSwitchesEnabled && !vowifiUnavailable,\n"
    "                            onCheckedChange = {\n"
    "                                onFeatureSwitchChange(\n"
    "                                    feature,\n"
    "                                    FeatureValue(it, feature.valueType)\n"
    "                                )\n"
    "                            }\n"
    "                        )\n"
    "                    }\n",
    1,
)

if s == orig:
    print("NO CHANGE", file=sys.stderr); sys.exit(1)
io.open(p, "w", encoding="utf-8").write(s)
print("OK MainActivity patched, delta bytes:", len(s) - len(orig))
