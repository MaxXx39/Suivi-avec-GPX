package app.capmystere.ui.traveler

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.capmystere.R
import app.capmystere.core.model.Audience
import app.capmystere.core.model.ClueFragment
import app.capmystere.core.model.ClueKind
import app.capmystere.core.model.MediaRef
import app.capmystere.core.model.TravelerSnapshot
import app.capmystere.theme.Cap
import app.capmystere.theme.CapTheme
import app.capmystere.ui.common.BodyText
import app.capmystere.ui.common.CapCard
import app.capmystere.ui.common.CapTopBar
import app.capmystere.ui.common.Glyph
import app.capmystere.ui.common.GlyphIcon
import app.capmystere.ui.common.Label
import app.capmystere.ui.common.ThemedBackground
import app.capmystere.ui.common.buttonShape
import java.io.File

@Composable
fun ClueBookScreen(onBack: () -> Unit, vm: TravelViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    val theme = remember(snapshot?.themeId) { vm.themes.get(snapshot?.themeId) }
    CapTheme(theme, snapshot?.audience ?: Audience.FRIENDS, night = state.night) {
        ThemedBackground(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                CapTopBar(Cap.tone.word("clueBook", stringResource(R.string.word_clue_book)), onBack = onBack, overline = snapshot?.title)
                if (snapshot != null) ClueBookContent(snapshot, vm::resolve, Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp))
            }
        }
    }
}

/** Carnet d'indices : mot mystère à trous, image en puzzle, indices écrits, étapes franchies. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClueBookContent(snapshot: TravelerSnapshot, resolve: suspend (MediaRef) -> File?, modifier: Modifier = Modifier) {
    val letters = snapshot.clues.filter { it.kind == ClueKind.LETTER }.associateBy { it.slot }
    val pieces = snapshot.clues.filter { it.kind == ClueKind.IMAGE_PIECE }.associateBy { it.slot }
    val texts = snapshot.clues.filter { it.kind == ClueKind.TEXT } + snapshot.messages.filter { it.isClue }.map { ClueFragment(it.id, ClueKind.TEXT, it.text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(22.dp)) {
        if (snapshot.clues.isEmpty() && texts.isEmpty()) BodyText(stringResource(R.string.clues_empty), color = Cap.colors.textMuted, style = Cap.type.bodyLarge)

        if (snapshot.clueWordLength > 0) Column {
            Label(stringResource(R.string.clues_word), color = Cap.colors.primary)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(snapshot.clueWordLength) { i ->
                    val l = letters[i]
                    Box(
                        Modifier.size(width = 42.dp, height = 54.dp).clip(buttonShape())
                            .background(if (l != null) Cap.colors.surfaceHigh else Cap.colors.surface)
                            .border(1.dp, if (l != null) Cap.colors.primary else Cap.colors.outline, buttonShape()),
                        contentAlignment = Alignment.Center,
                    ) { Text(l?.text?.uppercase() ?: "·", style = Cap.type.title, color = if (l != null) Cap.colors.text else Cap.colors.textMuted) }
                }
            }
        }

        if (pieces.isNotEmpty()) Column {
            Label(stringResource(R.string.clues_image), color = Cap.colors.primary)
            Spacer(Modifier.height(10.dp))
            val grid = snapshot.clueGrid.coerceIn(2, 5)
            Column(Modifier.fillMaxWidth().aspectRatio(1f).border(1.dp, Cap.colors.outline), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (row in 0 until grid) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (col in 0 until grid) {
                        val piece = pieces[row * grid + col]?.image
                        if (piece != null) MediaImage(piece, resolve, Modifier.weight(1f).fillMaxSize())
                        else Box(Modifier.weight(1f).fillMaxSize().background(Cap.colors.surface), contentAlignment = Alignment.Center) {
                            GlyphIcon(Glyph.LOCK, tint = Cap.colors.outline, size = 20.dp)
                        }
                    }
                }
            }
        }

        if (texts.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label(stringResource(R.string.clues_texts), color = Cap.colors.primary)
            for (t in texts) CapCard(Modifier.fillMaxWidth()) { Text(t.text, style = Cap.type.quote, color = Cap.colors.text) }
        }

        if (snapshot.unlocked.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label(stringResource(R.string.clues_journal), color = Cap.colors.primary)
            for (st in snapshot.unlocked.asReversed()) CapCard(Modifier.fillMaxWidth()) {
                Label("${Cap.tone.word("stage", stringResource(R.string.word_stage))} ${st.index + 1}")
                if (st.title.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(st.title, style = Cap.type.title, color = Cap.colors.text) }
                if (st.message.text.isNotBlank()) { Spacer(Modifier.height(6.dp)); BodyText(st.message.text, color = Cap.colors.textMuted) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
