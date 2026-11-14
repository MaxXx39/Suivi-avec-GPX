package app.capmystere.ui.host

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.capmystere.R
import app.capmystere.core.guidance.Distances
import app.capmystere.core.model.Stage
import app.capmystere.core.model.Trip
import app.capmystere.map.HostMap
import app.capmystere.map.StageMarker
import app.capmystere.theme.Cap
import app.capmystere.theme.CapTheme
import app.capmystere.ui.common.BodyText
import app.capmystere.ui.common.ButtonKind
import app.capmystere.ui.common.CapButton
import app.capmystere.ui.common.CapChip
import app.capmystere.ui.common.CapTextField
import app.capmystere.ui.common.DisplayText
import app.capmystere.ui.common.Glyph
import app.capmystere.ui.common.GlyphIcon
import app.capmystere.ui.common.Hairline
import app.capmystere.ui.common.IconAction
import app.capmystere.ui.common.Label
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(vm: EditorViewModel, onBack: () -> Unit, onStage: (String) -> Unit, onSettings: () -> Unit, onShare: () -> Unit, onPreview: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val trip = state.trip
    val theme = remember(trip?.themeId) { vm.themes.get(trip?.themeId) }
    val scope = rememberCoroutineScope()
    var moveMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val gpxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { vm.preview(vm.readGpx(uri), onPreview) }
    }

    CapTheme(theme, trip?.audience ?: app.capmystere.core.model.Audience.FRIENDS) {
        Box(Modifier.fillMaxSize().background(Cap.colors.background)) {
            if (trip != null) {
                val markers = remember(trip.start, trip.stages, state.selected) {
                    listOfNotNull(trip.start?.let { StageMarker("D", it, 0, state.selected == EditorViewModel.START) }) +
                        trip.stages.mapIndexed { i, st -> StageMarker("${i + 1}", st.position, st.radiusM, st.id == state.selected) }
                }
                val line = remember(state.legs) { state.legs.flatMap { it.points } }
                HostMap(
                    stages = markers, route = line, modifier = Modifier.fillMaxSize(), focus = state.focus, fitKey = state.fitKey,
                    onMapClick = { p -> vm.onMapTap(p, moveMode); moveMode = false },
                    onStageClick = { i ->
                        val offset = if (trip.start != null) 1 else 0
                        vm.select(if (trip.start != null && i == 0) EditorViewModel.START else trip.stages.getOrNull(i - offset)?.id)
                    },
                )
            }

            // Barre haute : titre, recherche de lieu.
            Column(Modifier.fillMaxWidth().background(Cap.colors.background.copy(alpha = 0.92f)).statusBarsPadding().padding(start = 4.dp, end = 8.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconAction(Glyph.BACK, onBack, contentDescription = stringResource(R.string.back))
                    Column(Modifier.weight(1f)) {
                        Label(theme.name)
                        DisplayText(trip?.title?.ifBlank { null } ?: stringResource(R.string.host_untitled), style = Cap.type.title, maxLines = 1)
                    }
                    IconAction(Glyph.SETTINGS, onSettings, contentDescription = stringResource(R.string.trip_settings_title))
                    IconAction(Glyph.SHARE, onShare, contentDescription = stringResource(R.string.share_title), enabled = trip?.stages?.isNotEmpty() == true)
                }
                CapTextField(
                    query, { query = it }, stringResource(R.string.editor_search), Modifier.padding(horizontal = 8.dp),
                    textStyle = Cap.type.body, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
                if (query.isNotBlank()) Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CapChip(stringResource(if (state.searching) R.string.editor_searching else R.string.editor_search_go), false, { keyboard?.hide(); vm.search(query) })
                    CapChip(stringResource(R.string.cancel), false, { query = ""; vm.search("") })
                }
                for (place in state.places) Column(Modifier.fillMaxWidth().clickable { vm.goTo(place); query = ""; keyboard?.hide() }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    BodyText(place.name, style = Cap.type.bodyLarge)
                    if (place.detail.isNotBlank()) BodyText(place.detail, color = Cap.colors.textMuted)
                }
            }

            if (trip != null) EditorPanel(
                trip = trip, state = state, moveMode = moveMode,
                onSelect = vm::select, onMove = vm::moveStage, onRemove = vm::removeStage, onRadius = vm::setRadius,
                onContent = onStage, onMoveMode = { moveMode = !moveMode }, onRetryRoute = vm::retryRoute,
                onPreview = { vm.preview(null, onPreview) }, onPreviewGpx = { gpxPicker.launch(arrayOf("*/*")) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** Panneau bas : estimation (réservée à l'hôte), liste ordonnée des étapes, réglages de l'étape sélectionnée. */
@Composable
fun EditorPanel(
    trip: Trip, state: EditorState, moveMode: Boolean,
    onSelect: (String?) -> Unit, onMove: (String, Int) -> Unit, onRemove: (String) -> Unit, onRadius: (String, Int) -> Unit,
    onContent: (String) -> Unit, onMoveMode: () -> Unit, onRetryRoute: () -> Unit, onPreview: () -> Unit, onPreviewGpx: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Cap.colors
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(colors.surface)
            .border(1.dp, colors.outline, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).navigationBarsPadding().padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        // Estimation
        Row(verticalAlignment = Alignment.Bottom) {
            when {
                trip.start == null -> BodyText(stringResource(R.string.editor_hint_start), Modifier.weight(1f), style = Cap.type.bodyLarge)
                trip.stages.isEmpty() -> BodyText(stringResource(R.string.editor_hint_stage), Modifier.weight(1f), style = Cap.type.bodyLarge)
                state.routeError -> Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    BodyText(stringResource(R.string.editor_route_error), Modifier.weight(1f), color = colors.danger)
                    CapChip(stringResource(R.string.retry), false, onRetryRoute)
                }
                else -> Column(Modifier.weight(1f)) {
                    Label(stringResource(R.string.editor_estimate))
                    Text(
                        if (state.routing) stringResource(R.string.editor_routing) else "${Distances.format(state.distanceM)} · ${Distances.formatDuration(state.durationS)}",
                        style = Cap.type.distance, color = colors.primary,
                    )
                }
            }
        }
        if (moveMode) { Spacer(Modifier.height(6.dp)); BodyText(stringResource(R.string.editor_move_hint), color = colors.secondary) }
        Spacer(Modifier.height(10.dp)); Hairline(); Spacer(Modifier.height(6.dp))

        Column(Modifier.heightIn(max = 250.dp).verticalScroll(rememberScrollState())) {
            if (trip.start != null) StageRow("D", stringResource(R.string.editor_start), null, state.selected == EditorViewModel.START) { onSelect(EditorViewModel.START) }
            if (state.selected == EditorViewModel.START) Row(Modifier.padding(start = 44.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CapChip(stringResource(R.string.editor_move), moveMode, onMoveMode)
                CapChip(stringResource(R.string.delete), false, { onRemove(EditorViewModel.START) })
            }
            trip.stages.forEachIndexed { i, st ->
                val selected = st.id == state.selected
                StageRow("${i + 1}", st.title.ifBlank { stringResource(R.string.editor_stage_n, i + 1) }, stageSummary(st), selected) { onSelect(st.id) }
                if (selected) Column(Modifier.padding(start = 44.dp, bottom = 10.dp)) {
                    Label(stringResource(R.string.editor_radius, st.radiusM))
                    Slider(
                        value = st.radiusM.toFloat(), onValueChange = { onRadius(st.id, (it / 10).toInt() * 10) }, valueRange = 20f..500f,
                        colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary, inactiveTrackColor = colors.outline),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CapChip(stringResource(R.string.editor_content), true, { onContent(st.id) })
                        CapChip(stringResource(R.string.editor_move), moveMode, onMoveMode)
                        IconAction(Glyph.UP, { onMove(st.id, -1) }, contentDescription = stringResource(R.string.editor_up), enabled = i > 0)
                        IconAction(Glyph.DOWN, { onMove(st.id, 1) }, contentDescription = stringResource(R.string.editor_down), enabled = i < trip.stages.lastIndex)
                        IconAction(Glyph.TRASH, { onRemove(st.id) }, contentDescription = stringResource(R.string.delete), tint = colors.danger)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CapButton(
                stringResource(R.string.editor_preview), onPreview, Modifier.weight(1f), enabled = trip.stages.isNotEmpty(),
                leading = { GlyphIcon(Glyph.EYE, size = 22.dp, tint = colors.onPrimary) },
            )
            CapButton(stringResource(R.string.editor_preview_gpx), onPreviewGpx, kind = ButtonKind.GHOST, enabled = trip.stages.isNotEmpty())
        }
    }
}

@Composable
private fun stageSummary(st: Stage): String {
    val parts = ArrayList<String>()
    if (st.message.text.isNotBlank() || st.message.image != null || st.message.audio != null) parts += stringResource(R.string.editor_has_message)
    if (st.challenges.isNotEmpty()) parts += stringResource(R.string.editor_challenges_n, st.challenges.size)
    return parts.joinToString(" · ")
}

@Composable
private fun StageRow(badge: String, title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    val colors = Cap.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(if (selected) colors.secondary else colors.primary), contentAlignment = Alignment.Center) {
            Text(badge, style = Cap.type.label, color = colors.onPrimary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BodyText(title, style = Cap.type.bodyLarge, color = if (selected) colors.primary else colors.text)
            if (!subtitle.isNullOrBlank()) BodyText(subtitle, color = colors.textMuted)
        }
    }
}
