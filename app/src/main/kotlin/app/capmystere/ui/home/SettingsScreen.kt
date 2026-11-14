package app.capmystere.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.capmystere.R
import app.capmystere.data.NightMode
import app.capmystere.data.Prefs
import app.capmystere.data.Settings
import app.capmystere.theme.Cap
import app.capmystere.ui.common.BodyText
import app.capmystere.ui.common.CapChip
import app.capmystere.ui.common.CapTopBar
import app.capmystere.ui.common.Label
import app.capmystere.ui.common.ThemedBackground
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val prefs: Prefs) : ViewModel() {
    val settings = prefs.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Settings())
    fun voice(v: Boolean) = viewModelScope.launch { prefs.setVoice(v) }
    fun haptics(v: Boolean) = viewModelScope.launch { prefs.setHaptics(v) }
    fun sounds(v: Boolean) = viewModelScope.launch { prefs.setSounds(v) }
    fun night(v: NightMode) = viewModelScope.launch { prefs.setNightMode(v) }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    ThemedBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            CapTopBar(stringResource(R.string.settings_title), onBack = onBack)
            SettingsContent(s, vm::voice, vm::haptics, vm::sounds, vm::night, Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(
    s: Settings, onVoice: (Boolean) -> Unit, onHaptics: (Boolean) -> Unit, onSounds: (Boolean) -> Unit, onNight: (NightMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Group(stringResource(R.string.settings_voice), stringResource(R.string.settings_voice_sub)) {
            CapChip(stringResource(R.string.on), s.voice == true, { onVoice(true) })
            CapChip(stringResource(R.string.off), s.voice == false, { onVoice(false) })
        }
        Group(stringResource(R.string.settings_sounds), stringResource(R.string.settings_sounds_sub)) {
            CapChip(stringResource(R.string.on), s.sounds, { onSounds(true) })
            CapChip(stringResource(R.string.off), !s.sounds, { onSounds(false) })
        }
        Group(stringResource(R.string.settings_haptics), stringResource(R.string.settings_haptics_sub)) {
            CapChip(stringResource(R.string.on), s.haptics, { onHaptics(true) })
            CapChip(stringResource(R.string.off), !s.haptics, { onHaptics(false) })
        }
        Group(stringResource(R.string.settings_night), stringResource(R.string.settings_night_sub)) {
            CapChip(stringResource(R.string.night_auto), s.nightMode == NightMode.AUTO, { onNight(NightMode.AUTO) })
            CapChip(stringResource(R.string.night_night), s.nightMode == NightMode.NIGHT, { onNight(NightMode.NIGHT) })
            CapChip(stringResource(R.string.night_day), s.nightMode == NightMode.DAY, { onNight(NightMode.DAY) })
        }
        Spacer(Modifier.height(18.dp))
        BodyText(stringResource(R.string.settings_credits), color = Cap.colors.textMuted)
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Group(title: String, subtitle: String, chips: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 14.dp)) {
        Label(title, color = Cap.colors.primary)
        Spacer(Modifier.height(4.dp))
        BodyText(subtitle, color = Cap.colors.textMuted)
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { chips() }
    }
}
