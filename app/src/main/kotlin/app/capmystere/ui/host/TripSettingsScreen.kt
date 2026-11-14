package app.capmystere.ui.host

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.capmystere.R
import app.capmystere.core.model.Audience
import app.capmystere.core.model.ClueFragment
import app.capmystere.core.model.ClueKind
import app.capmystere.core.model.MediaRef
import app.capmystere.core.model.TimedClue
import app.capmystere.core.model.TravelProfile
import app.capmystere.core.model.Trip
import app.capmystere.theme.Cap
import app.capmystere.theme.CapTheme
import app.capmystere.theme.ThemeSpec
import app.capmystere.ui.common.BodyText
import app.capmystere.ui.common.ButtonKind
import app.capmystere.ui.common.CapButton
import app.capmystere.ui.common.CapChip
import app.capmystere.ui.common.CapTextField
import app.capmystere.ui.common.CapTopBar
import app.capmystere.ui.common.Glyph
import app.capmystere.ui.common.IconAction
import app.capmystere.ui.common.Illustration
import app.capmystere.ui.common.Label
import app.capmystere.ui.common.ThemedBackground
import app.capmystere.ui.common.cardShape
import app.capmystere.ui.traveler.MediaImage
import java.io.File
import java.util.UUID

fun audienceLabel(a: Audience): Int = when (a) {
    Audience.KIDS -> R.string.audience_kids
    Audience.FRIENDS -> R.string.audience_friends
    Audience.COUPLE -> R.string.audience_couple
    Audience.ADULTS -> R.string.audience_adults
}

fun profileLabel(p: TravelProfile): Int = when (p) {
    TravelProfile.CAR -> R.string.profile_car
    TravelProfile.BIKE -> R.string.profile_bike
    TravelProfile.WALK -> R.string.profile_walk
}

@Composable
fun TripSettingsScreen(vm: EditorViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val trip = state.trip ?: return
    val theme = remember(trip.themeId) { vm.themes.get(trip.themeId) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.setClueImage(uri) }
    CapTheme(theme, trip.audience) {
        ThemedBackground(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                CapTopBar(stringResource(R.string.trip_settings_title), onBack = onBack)
                TripSettingsContent(
                    trip = trip, themes = vm.themes.themes, resolve = vm::resolve,
                    onEdit = { reroute, f -> vm.edit(reroute, f) },
                    onPickClueImage = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onClearClueImage = { vm.setClueImage(null) },
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TripSettingsContent(
    trip: Trip, themes: List<ThemeSpec>, resolve: suspend (MediaRef) -> File?,
    onEdit: (Boolean, (Trip) -> Trip) -> Unit, onPickClueImage: () -> Unit, onClearClueImage: () -> Unit, modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(22.dp)) {
        CapTextField(trip.title, { v -> onEdit(false) { it.copy(title = v) } }, stringResource(R.string.trip_title_label), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))

        // Thème : chaque carte est rendue dans son propre thème, pour juger sur pièce.
        Column {
            Label(stringResource(R.string.trip_theme), color = Cap.colors.primary)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (t in themes) ThemePreviewCard(t, trip.audience, selected = t.id == trip.themeId) { onEdit(false) { it.copy(themeId = t.id) } }
            }
        }

        Column {
            Label(stringResource(R.string.trip_audience), color = Cap.colors.primary)
            Spacer(Modifier.height(4.dp))
            BodyText(stringResource(R.string.trip_audience_sub), color = Cap.colors.textMuted)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in Audience.entries) CapChip(stringResource(audienceLabel(a)), trip.audience == a, { onEdit(false) { it.copy(audience = a) } })
            }
        }

        Column {
            Label(stringResource(R.string.trip_profile), color = Cap.colors.primary)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in TravelProfile.entries) CapChip(stringResource(profileLabel(p)), trip.profile == p, { onEdit(true) { it.copy(profile = p) } })
            }
        }

        // Indices sur la destination
        Column {
            Label(stringResource(R.string.trip_clue_word), color = Cap.colors.primary)
            Spacer(Modifier.height(4.dp))
            BodyText(stringResource(R.string.trip_clue_word_sub), color = Cap.colors.textMuted)
            Spacer(Modifier.height(10.dp))
            CapTextField(
                trip.clueWord, { v -> onEdit(false) { it.copy(clueWord = v.uppercase().take(24)) } }, stringResource(R.string.trip_clue_word_label),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
            )
        }

        Column {
            Label(stringResource(R.string.trip_clue_image), color = Cap.colors.primary)
            Spacer(Modifier.height(4.dp))
            BodyText(stringResource(R.string.trip_clue_image_sub, trip.clueGrid * trip.clueGrid), color = Cap.colors.textMuted)
            Spacer(Modifier.height(10.dp))
            if (trip.cluePieces.isNotEmpty()) {
                Column(Modifier.size(180.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (r in 0 until trip.clueGrid) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (c in 0 until trip.clueGrid) trip.cluePieces.getOrNull(r * trip.clueGrid + c)?.let { MediaImage(it, resolve, Modifier.weight(1f).fillMaxSize()) }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CapButton(stringResource(if (trip.cluePieces.isEmpty()) R.string.pick_image else R.string.change_image), onPickClueImage, kind = ButtonKind.GHOST)
                if (trip.cluePieces.isNotEmpty()) CapButton(stringResource(R.string.remove), onClearClueImage, kind = ButtonKind.GHOST)
            }
        }

        // Indices programmés
        Column {
            Label(stringResource(R.string.trip_timed), color = Cap.colors.primary)
            Spacer(Modifier.height(4.dp))
            BodyText(stringResource(R.string.trip_timed_sub), color = Cap.colors.textMuted)
            for (tc in trip.timedClues) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CapTextField(
                    tc.afterMinutes.toString(), { v -> onEdit(false) { t -> t.copy(timedClues = t.timedClues.map { if (it.id == tc.id) it.copy(afterMinutes = v.filter(Char::isDigit).take(4).toIntOrNull() ?: 0) else it }) } },
                    stringResource(R.string.trip_timed_minutes), Modifier.width(96.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                CapTextField(
                    tc.fragment.text, { v -> onEdit(false) { t -> t.copy(timedClues = t.timedClues.map { if (it.id == tc.id) it.copy(fragment = it.fragment.copy(text = v)) else it }) } },
                    stringResource(R.string.trip_timed_text), Modifier.weight(1f), singleLine = false,
                )
                IconAction(Glyph.TRASH, { onEdit(false) { t -> t.copy(timedClues = t.timedClues.filterNot { it.id == tc.id }) } }, contentDescription = stringResource(R.string.delete), tint = Cap.colors.textMuted)
            }
            Spacer(Modifier.height(10.dp))
            CapButton(stringResource(R.string.trip_timed_add), {
                onEdit(false) { t -> t.copy(timedClues = t.timedClues + TimedClue(UUID.randomUUID().toString(), 60, ClueFragment(UUID.randomUUID().toString(), ClueKind.TEXT))) }
            }, kind = ButtonKind.GHOST)
        }

        Column {
            Label(stringResource(R.string.trip_secrecy), color = Cap.colors.primary)
            Spacer(Modifier.height(4.dp))
            BodyText(stringResource(R.string.trip_street_names_sub), color = Cap.colors.textMuted)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CapChip(stringResource(R.string.trip_street_names_off), !trip.showStreetNames, { onEdit(false) { it.copy(showStreetNames = false) } })
                CapChip(stringResource(R.string.trip_street_names_on), trip.showStreetNames, { onEdit(false) { it.copy(showStreetNames = true) } })
            }
        }

        Column {
            Label(stringResource(R.string.trip_interval), color = Cap.colors.primary)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in listOf(5, 10, 30, 60)) CapChip(stringResource(R.string.seconds_n, s), trip.positionIntervalS == s, { onEdit(false) { it.copy(positionIntervalS = s) } })
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** Vignette d'un thème, rendue avec sa palette, ses polices, sa forme de carte et son emblème. */
@Composable
fun ThemePreviewCard(spec: ThemeSpec, audience: Audience, selected: Boolean, onClick: () -> Unit) {
    val outer = Cap.colors
    CapTheme(spec, audience) {
        val shape = cardShape()
        Column(
            Modifier.width(190.dp).clip(shape).background(Cap.colors.background)
                .border(if (selected) 2.5.dp else 1.dp, if (selected) outer.primary else Cap.colors.outline, shape)
                .clickable(onClick = onClick).padding(16.dp),
        ) {
            Illustration("emblem", size = 56.dp)
            Spacer(Modifier.height(10.dp))
            Text(if (Cap.type.displayUppercase) spec.name.uppercase() else spec.name, style = Cap.type.title, color = Cap.colors.text)
            Spacer(Modifier.height(4.dp))
            Text(spec.tagline, style = Cap.type.body.copy(fontSize = Cap.type.body.fontSize * 0.85f), color = Cap.colors.textMuted, maxLines = 3)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in listOf(Cap.colors.primary, Cap.colors.secondary, Cap.colors.text, Cap.colors.surfaceHigh)) Box(Modifier.size(18.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c))
            }
        }
    }
}
