package io.github.vvb2060.ims.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.vvb2060.ims.ShizukuProvider
import io.github.vvb2060.ims.model.CarrierProfile
import io.github.vvb2060.ims.model.CarrierProfileRegistry
import io.github.vvb2060.ims.model.SimSelection
import io.github.vvb2060.ims.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 运营商配置页 ViewModel。
 *
 * - 列出 [CarrierProfileRegistry.all] 三家运营商预设
 * - 启动时读取当前 SIM 列表，按 MCC/MNC 自动匹配并高亮
 * - [applyProfile] 触发 ImsModifier + ApnModifier 写入（委托 [MainViewModel.applyCarrierProfile]）
 * - 实验性预设（电信）写入前要求用户二次确认
 */
class CarrierProfileViewModel(application: Application) : AndroidViewModel(application) {

    val profiles: List<CarrierProfile> = CarrierProfileRegistry.all

    private val _simList = MutableStateFlow<List<SimSelection>>(emptyList())
    val simList: StateFlow<List<SimSelection>> = _simList.asStateFlow()

    private val _selectedSim = MutableStateFlow<SimSelection?>(null)
    val selectedSim: StateFlow<SimSelection?> = _selectedSim.asStateFlow()

    private val _matchedProfile = MutableStateFlow<CarrierProfile?>(null)
    val matchedProfile: StateFlow<CarrierProfile?> = _matchedProfile.asStateFlow()

    private val _selectedProfile = MutableStateFlow<CarrierProfile?>(null)
    val selectedProfile: StateFlow<CarrierProfile?> = _selectedProfile.asStateFlow()

    private val _applying = MutableStateFlow(false)
    val applying: StateFlow<Boolean> = _applying.asStateFlow()

    private val _result = MutableStateFlow<MainViewModel.ProfileApplyResult?>(null)
    val result: StateFlow<MainViewModel.ProfileApplyResult?> = _result.asStateFlow()

    private val _showExperimentalDialog = MutableStateFlow(false)
    val showExperimentalDialog: StateFlow<Boolean> = _showExperimentalDialog.asStateFlow()

    init {
        loadSimInfo()
    }

    private fun loadSimInfo() {
        viewModelScope.launch {
            val sims = withContext(Dispatchers.IO) {
                ShizukuProvider.readSimInfoList(getApplication())
            }
            _simList.value = sims
            val active = sims.firstOrNull { it.subId >= 0 }
            _selectedSim.value = active
            val matched = active?.let { CarrierProfileRegistry.match(it.mcc, it.mnc) }
            _matchedProfile.value = matched
            if (_selectedProfile.value == null) {
                _selectedProfile.value = matched ?: profiles.first()
            }
        }
    }

    fun selectProfile(profile: CarrierProfile) {
        _selectedProfile.value = profile
        _result.value = null
    }

    fun applyProfile() {
        val profile = _selectedProfile.value ?: return
        if (profile.experimental) {
            _showExperimentalDialog.value = true
            return
        }
        doApply(profile)
    }

    fun confirmApply() {
        _showExperimentalDialog.value = false
        _selectedProfile.value?.let { doApply(it) }
    }

    fun cancelApply() {
        _showExperimentalDialog.value = false
    }

    private fun doApply(profile: CarrierProfile) {
        val subId = _selectedSim.value?.subId ?: -1
        viewModelScope.launch {
            _applying.value = true
            _result.value = null
            val r = withContext(Dispatchers.IO) {
                MainViewModel.applyCarrierProfile(getApplication(), profile, subId)
            }
            _result.value = r
            _applying.value = false
        }
    }
}
