package app.capmystere.ui.host

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.capmystere.R
import app.capmystere.core.model.Trip
import app.capmystere.data.TripRepository
import app.capmystere.data.backend.TripBackend
import app.capmystere.theme.Cap
import app.capmystere.theme.ThemeRepository
import app.capmystere.ui.common.BodyText
import app.capmystere.ui.common.CapButton
import app.capmystere.ui.common.CapCard
import app.capmystere.ui.common.CapTopBar
import app.capmystere.ui.common.DisplayText
import app.capmystere.ui.common.Glyph
import app.capmystere.ui.common.GlyphIcon
import app.capmystere.ui.common.IconAction
import app.capmystere.ui.common.Label
import app.capmystere.ui.common.ThemedBackground
import app.capmystere.ui.traveler.ConfirmDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TripListViewModel @Inject constructor(private val repo: TripRepository, private val backend: TripBackend, val themes: ThemeRepository) : ViewModel() {
    val trips = repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun create(onCreated: (String) -> Unit) = viewModelScope.launch { onCreated(repo.create(themes.get(null).id).id) }

    fun delete(trip: Trip) = viewModelScope.launch {
        if (trip.published) runCatching { backend.unpublish(trip.id) }
        repo.delete(trip.id)
    }
}

@Composable
fun TripListScreen(onBack: () -> Unit, onOpen: (String) -> Unit, vm: TripListViewModel = hiltViewModel()) {
    val trips by vm.trips.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<Trip?>(null) }
    ThemedBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            CapTopBar(stringResource(R.string.host_title), onBack = onBack, overline = Cap.tone.word("host", stringResource(R.string.word_host)))
            TripListContent(trips, { id -> vm.themes.get(id).name }, onOpen, { toDelete = it }, Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp))
            CapButton(
                stringResource(R.string.host_new), { vm.create(onOpen) }, Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
                leading = { GlyphIcon(Glyph.PLUS, size = 22.dp, tint = Cap.colors.onPrimary) },
            )
        }
        toDelete?.let { trip ->
            ConfirmDialog(
                stringResource(R.string.host_delete_title), stringResource(R.string.host_delete_body), stringResource(R.string.delete),
                onConfirm = { vm.delete(trip); toDelete = null }, onDismiss = { toDelete = null },
            )
        }
    }
}

@Composable
fun TripListContent(trips: List<Trip>, themeName: (String) -> String, onOpen: (String) -> Unit, onDelete: (Trip) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (trips.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            DisplayText(stringResource(R.string.host_empty_title))
            BodyText(stringResource(R.string.host_empty_body), color = Cap.colors.textMuted, style = Cap.type.bodyLarge)
        }
        for (trip in trips) CapCard(Modifier.fillMaxWidth(), onClick = { onOpen(trip.id) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label(if (trip.published) stringResource(R.string.host_published, trip.code) else stringResource(R.string.host_draft), color = if (trip.published) Cap.colors.success else Cap.colors.textMuted)
                    Spacer(Modifier.height(4.dp))
                    DisplayText(trip.title.ifBlank { stringResource(R.string.host_untitled) }, style = Cap.type.title, maxLines = 2)
                    Spacer(Modifier.height(4.dp))
                    BodyText(stringResource(R.string.host_trip_summary, themeName(trip.themeId), trip.stages.size), color = Cap.colors.textMuted)
                }
                IconAction(Glyph.TRASH, { onDelete(trip) }, contentDescription = stringResource(R.string.delete), tint = Cap.colors.textMuted)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
