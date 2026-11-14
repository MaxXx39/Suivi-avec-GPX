package app.capmystere.ui.traveler

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import app.capmystere.R
import app.capmystere.theme.Cap
import app.capmystere.travel.TravelController
import app.capmystere.travel.TravelService
import app.capmystere.ui.common.BodyText
import app.capmystere.ui.common.ButtonKind
import app.capmystere.ui.common.CapButton
import app.capmystere.ui.common.CapCard
import app.capmystere.ui.common.CapTopBar
import app.capmystere.ui.common.DisplayText
import app.capmystere.ui.common.Glyph
import app.capmystere.ui.common.GlyphIcon
import app.capmystere.ui.common.ThemedBackground
import app.capmystere.ui.common.hasPermission
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PermissionsViewModel @Inject constructor(val controller: TravelController) : ViewModel()

/**
 * Demande des autorisations, une par une, avec une explication claire avant chaque boîte système.
 * Seule la localisation précise est indispensable ; le reste améliore l'expérience.
 */
@Composable
fun PermissionsScreen(sessionId: String, onBack: () -> Unit, onReady: () -> Unit, vm: PermissionsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var fine by remember { mutableStateOf(context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) }
    var notifications by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 || context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) }
    var background by remember { mutableStateOf(Build.VERSION.SDK_INT < 29 || context.hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) }

    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { fine = it[Manifest.permission.ACCESS_FINE_LOCATION] == true }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifications = it }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { background = it }

    fun go() {
        vm.controller.start(sessionId)
        TravelService.start(context)
        onReady()
    }

    // Tout est déjà accordé (reprise d'un voyage) : on ne montre pas l'écran.
    LaunchedEffect(Unit) { if (fine && notifications) go() }

    ThemedBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            CapTopBar(stringResource(R.string.perm_title), onBack = onBack)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                DisplayText(stringResource(R.string.perm_headline))
                BodyText(stringResource(R.string.perm_intro), color = Cap.colors.textMuted, style = Cap.type.bodyLarge)
                Spacer(Modifier.height(4.dp))
                PermissionCard(
                    Glyph.PIN, stringResource(R.string.perm_location_title), stringResource(R.string.perm_location_body), fine, required = true,
                ) { fineLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
                if (Build.VERSION.SDK_INT >= 33) PermissionCard(
                    Glyph.LIVE, stringResource(R.string.perm_notif_title), stringResource(R.string.perm_notif_body), notifications, required = false,
                ) { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                if (Build.VERSION.SDK_INT >= 29) PermissionCard(
                    Glyph.LOCK, stringResource(R.string.perm_background_title), stringResource(R.string.perm_background_body), background, required = false, enabled = fine,
                ) { bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
                BodyText(stringResource(R.string.perm_camera_later), color = Cap.colors.textMuted)
                Spacer(Modifier.height(8.dp))
            }
            CapButton(stringResource(R.string.perm_go), ::go, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), enabled = fine)
        }
    }
}

@Composable
private fun PermissionCard(glyph: Glyph, title: String, body: String, granted: Boolean, required: Boolean, enabled: Boolean = true, onRequest: () -> Unit) {
    CapCard(Modifier.fillMaxWidth(), highlighted = !granted && required) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlyphIcon(if (granted) Glyph.CHECK else glyph, size = 26.dp, tint = if (granted) Cap.colors.success else Cap.colors.primary)
            DisplayText(title, Modifier.weight(1f), style = Cap.type.title)
        }
        Spacer(Modifier.height(8.dp))
        BodyText(body, color = Cap.colors.textMuted)
        if (!granted) {
            Spacer(Modifier.height(14.dp))
            CapButton(
                stringResource(if (required) R.string.perm_allow else R.string.perm_allow_optional), onRequest,
                kind = if (required) ButtonKind.PRIMARY else ButtonKind.GHOST, enabled = enabled,
            )
        }
    }
}
