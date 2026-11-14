package app.capmystere.ui.home

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.capmystere.R
import app.capmystere.data.Prefs
import app.capmystere.data.backend.TripBackend
import app.capmystere.theme.Cap
import app.capmystere.ui.common.BodyText
import app.capmystere.ui.common.CapCard
import app.capmystere.ui.common.DisplayText
import app.capmystere.ui.common.Dot
import app.capmystere.ui.common.Glyph
import app.capmystere.ui.common.GlyphIcon
import app.capmystere.ui.common.IconAction
import app.capmystere.ui.common.Illustration
import app.capmystere.ui.common.Label
import app.capmystere.ui.common.ThemedBackground
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(prefs: Prefs, backend: TripBackend) : ViewModel() {
    val resumable = prefs.settings.map { it.activeSessionId }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val isRemote = backend.isRemote
}

@Composable
fun HomeScreen(onTravel: () -> Unit, onResume: (String) -> Unit, onHost: () -> Unit, onSettings: () -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val resumable by vm.resumable.collectAsStateWithLifecycle()
    HomeContent(resumable, vm.isRemote, onTravel, onResume, onHost, onSettings)
}

@Composable
fun HomeContent(resumable: String?, isRemote: Boolean, onTravel: () -> Unit, onResume: (String) -> Unit, onHost: () -> Unit, onSettings: () -> Unit) {
    val sway = if (LocalInspectionMode.current) 0f else {
        val t = rememberInfiniteTransition(label = "compass")
        val v by t.animateFloat(-7f, 7f, infiniteRepeatable(tween(5200, easing = EaseInOutSine), RepeatMode.Reverse), label = "sway")
        v
    }
    ThemedBackground(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(if (isRemote) Cap.colors.success else Cap.colors.textMuted)
                Spacer(Modifier.width(8.dp))
                Label(stringResource(if (isRemote) R.string.home_mode_remote else R.string.home_mode_local))
                Spacer(Modifier.weight(1f))
                IconAction(Glyph.SETTINGS, onSettings, contentDescription = stringResource(R.string.settings_title))
            }
            Spacer(Modifier.height(20.dp))
            Illustration("emblem", Modifier.graphicsLayer { rotationZ = sway }, size = 132.dp)
            Spacer(Modifier.height(18.dp))
            DisplayText(stringResource(R.string.home_title), style = Cap.type.hero)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.home_tagline), style = Cap.type.quote, color = Cap.colors.textMuted)
            Spacer(Modifier.height(34.dp))

            if (resumable != null) {
                ChoiceCard(stringResource(R.string.home_resume_title), stringResource(R.string.home_resume_sub), Glyph.PLAY, highlighted = true) { onResume(resumable) }
                Spacer(Modifier.height(14.dp))
            }
            ChoiceCard(stringResource(R.string.home_travel_title), stringResource(R.string.home_travel_sub), Glyph.PIN, highlighted = resumable == null, onClick = onTravel)
            Spacer(Modifier.height(14.dp))
            ChoiceCard(stringResource(R.string.home_host_title), stringResource(R.string.home_host_sub), Glyph.EDIT, highlighted = false, onClick = onHost)
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ChoiceCard(title: String, subtitle: String, glyph: Glyph, highlighted: Boolean, onClick: () -> Unit) {
    CapCard(Modifier.fillMaxWidth(), onClick = onClick, highlighted = highlighted) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GlyphIcon(glyph, size = 30.dp, tint = if (highlighted) Cap.colors.primary else Cap.colors.textMuted)
            Column(Modifier.weight(1f)) {
                DisplayText(title, style = Cap.type.title)
                Spacer(Modifier.height(2.dp))
                BodyText(subtitle, color = Cap.colors.textMuted)
            }
        }
    }
}
