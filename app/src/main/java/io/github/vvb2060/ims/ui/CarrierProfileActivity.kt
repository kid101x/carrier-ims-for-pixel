package io.github.vvb2060.ims.ui

import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.vvb2060.ims.R
import io.github.vvb2060.ims.model.CarrierProfile
import io.github.vvb2060.ims.viewmodel.MainViewModel

/**
 * 运营商配置页：列出三家运营商预设，按当前 SIM 自动匹配高亮，
 * 选中后展示待写入的 CarrierConfig 摘要与 APN 列表（只读），
 * 点击"应用"触发 ImsModifier + ApnModifier 写入并回读校验。
 *
 * 电信预设（experimental=true）选中应用时弹出二次确认警告。
 */
class CarrierProfileActivity : BaseActivity() {

    private val viewModel: CarrierProfileViewModel by viewModels()

    @Composable
    override fun Content() {
        val simList by viewModel.simList.collectAsStateWithLifecycle()
        val selectedSim by viewModel.selectedSim.collectAsStateWithLifecycle()
        val matched by viewModel.matchedProfile.collectAsStateWithLifecycle()
        val selected by viewModel.selectedProfile.collectAsStateWithLifecycle()
        val applying by viewModel.applying.collectAsStateWithLifecycle()
        val result by viewModel.result.collectAsStateWithLifecycle()
        val showExperimental by viewModel.showExperimentalDialog.collectAsStateWithLifecycle()

        Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = { finish() }) {
                        Icon(Icons.Rounded.Close, contentDescription = null)
                    }
                    Text(
                        text = stringResource(R.string.carrier_profile_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                val sim = selectedSim
                if (sim == null || sim.subId < 0) {
                    Text(
                        text = stringResource(R.string.select_single_sim),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    Text(
                        text = "SIM：${sim.showTitle}（${sim.mcc}/${sim.mnc}）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }

                viewModel.profiles.forEach { profile ->
                    ProfileRow(
                        profile = profile,
                        isSelected = selected?.name == profile.name,
                        isMatched = matched?.name == profile.name,
                        onSelect = { viewModel.selectProfile(profile) },
                    )
                }

                selected?.let { profile -> ProfileDetailCard(profile) }

                Button(
                    onClick = { viewModel.applyProfile() },
                    enabled = !applying && selected != null && (selectedSim?.subId ?: -1) >= 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (applying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.carrier_profile_apply))
                    }
                }

                result?.let { r -> ResultBlock(r) }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        if (showExperimental && selected != null) {
            AlertDialog(
                onDismissRequest = { viewModel.cancelApply() },
                title = { Text(stringResource(R.string.carrier_profile_title)) },
                text = { Text(stringResource(R.string.carrier_profile_experimental_warning)) },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmApply() }) {
                        Text(stringResource(R.string.carrier_profile_apply))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.cancelApply() }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun ProfileRow(
    profile: CarrierProfile,
    isSelected: Boolean,
    isMatched: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(selected = isSelected, onClick = onSelect)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name + if (profile.experimental) "（实验性）" else "",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "MCC ${profile.mcc} / MNC ${profile.mnc}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (isMatched) {
                    Text(
                        text = "当前 SIM 匹配",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileDetailCard(profile: CarrierProfile) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.carrier_profile_carrier_config_label),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            profile.carrierConfigKeys.keys.forEach { key ->
                Text(
                    text = "• $key",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.carrier_profile_data_apn_label),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "APN：${profile.dataApn.apn}　类型：${profile.dataApn.type}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                text = stringResource(R.string.carrier_profile_ims_apn_label),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "APN：${profile.imsApn.apn}　类型：${profile.imsApn.type}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun ResultBlock(r: MainViewModel.ProfileApplyResult) {
    if (!r.success) {
        Text(
            text = stringResource(R.string.carrier_profile_apply_failed),
            color = MaterialTheme.colorScheme.error,
            fontSize = 13.sp,
        )
        r.errorMessage?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
        }
    } else if (r.volteAvailable) {
        Text(
            text = stringResource(R.string.carrier_profile_apply_success),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 13.sp,
        )
    } else {
        Text(
            text = stringResource(R.string.carrier_profile_apply_failed),
            color = MaterialTheme.colorScheme.error,
            fontSize = 13.sp,
        )
    }
    if (r.success && !r.vowifiPlatformAvailable) {
        Text(
            text = stringResource(R.string.carrier_profile_vowifi_unavailable),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    r.apnDataError?.let {
        Text("数据 APN：$it", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
    }
    r.apnImsError?.let {
        Text("IMS APN：$it", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
    }
}
